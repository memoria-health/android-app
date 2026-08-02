package com.memoria.caregiver.ui.enrollment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.ui.geometry.Rect
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Camera + face-detection engine for guided face enrollment.
 *
 * Streams frames from the chosen camera (front or rear), runs on-device **ML
 * Kit** face detection to get the face box and head pose (yaw/pitch), scores
 * capture quality through an injected metric, and feeds the shared coverage
 * model through [EnrollmentCoverageSink].
 *
 * Spec: `Features/Onboarding/Enrollment/EnrollmentVisionCamera.swift`,
 * `PARITY.md` §26. CameraX `ImageAnalysis` replaces `AVCaptureVideoDataOutput`
 * and ML Kit replaces `VNDetectFaceRectanglesRequest` revision 3.
 *
 * ## The one thing not to get backwards
 *
 * **The preview is mirrored; the captured data is not.** Mirroring happens on
 * the preview surface only. The embedded JPEG is the true un-mirrored face, so
 * the gallery matches what the wall cam actually sees. Mirror the analysis
 * stream instead and every yaw sign flips and the re-ID gallery fills with
 * mirror-image faces — silently.
 *
 * ## Threading
 *
 * CameraX delivers frames on a private single-thread executor; detection,
 * framing, luma and JPEG encoding all happen there. The observable state below
 * is `StateFlow`, which is safe to write from that thread. The sink's `ingest` /
 * `noteFaceLost` are posted to the main thread; its `qualityFloor` is called
 * inline, which is why [EnrollmentCoverageSink] requires it to be thread-safe.
 *
 * @param qualityScorer stands in for Vision's `faceCaptureQuality`. See
 *   [EnrollmentQualityScorer] and `QUESTIONS.md` Q2.
 * @param nowNanos monotonic clock source, injectable for tests.
 */
class EnrollmentCamera(
    context: Context,
    val facing: EnrollmentFacing,
    private val sink: EnrollmentCoverageSink,
    private val qualityScorer: EnrollmentQualityScorer,
    private val nowNanos: () -> Long = System::nanoTime,
) {

    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val rateLimiter = EnrollmentRateLimiter()

    /**
     * FAST mode, no landmarks, no contours, no classification: the head Euler
     * angles come free, and everything else is per-frame cost the dial never
     * reads. Tracking is off — iOS re-detects every processed frame too.
     */
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build(),
    )

    private val _isConfigured = MutableStateFlow(false)

    /** True once the camera has successfully bound at least once. */
    val isConfigured: StateFlow<Boolean> = _isConfigured.asStateFlow()

    private val _setupError = MutableStateFlow<String?>(null)
    val setupError: StateFlow<String?> = _setupError.asStateFlow()

    private val _faceBox = MutableStateFlow<Rect?>(null)

    /**
     * Latest face rect, normalised with a **top-left** origin relative to the
     * upright portrait frame. Null when no face. Feed it to
     * [EnrollmentGeometry.viewRect] to place the rear tracking ring.
     */
    val faceBox: StateFlow<Rect?> = _faceBox.asStateFlow()

    private val _faceVisible = MutableStateFlow(false)
    val faceVisible: StateFlow<Boolean> = _faceVisible.asStateFlow()

    private val _quality = MutableStateFlow(0f)
    val quality: StateFlow<Float> = _quality.asStateFlow()

    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var bound = false

    // ── lifecycle ─────────────────────────────────────────────────────

    /**
     * Bind the camera to [owner]. Idempotent, so the enrollment shell may call
     * it on appear and again on a permission grant.
     *
     * iOS separates `configure()` from `start()`; CameraX fuses the two into
     * `bindToLifecycle`, so [isConfigured] is set on the first successful bind
     * and — as on iOS — stays true across a later [stop].
     */
    fun start(owner: LifecycleOwner) {
        if (bound) return
        bound = true
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener(
            {
                try {
                    bind(future.get(), owner)
                } catch (t: Throwable) {
                    bound = false
                    _setupError.value = "Camera input failed: ${t.message}"
                }
            },
            mainExecutor,
        )
    }

    /** Unbind. Safe to call when not started. */
    fun stop() {
        provider?.unbindAll()
        bound = false
    }

    /** Release the detector and the analysis thread. The camera is dead after this. */
    fun close() {
        stop()
        detector.close()
        analysisExecutor.shutdown()
    }

    /**
     * Point the preview use case at a surface. Main thread only; called by
     * [EnrollmentCameraPreview].
     */
    fun attachPreview(surface: Preview.SurfaceProvider) {
        // Re-setting the same provider tears the surface down and rebuilds it,
        // which blinks the preview. `update` runs on every recomposition, so
        // this guard is what keeps it to once.
        if (surfaceProvider === surface) return
        surfaceProvider = surface
        preview?.setSurfaceProvider(surface)
    }

    /** Drop the preview surface, leaving analysis running. Main thread only. */
    fun detachPreview() {
        surfaceProvider = null
        preview?.setSurfaceProvider(null)
    }

    private fun bind(cameraProvider: ProcessCameraProvider, owner: LifecycleOwner) {
        val selector = if (facing == EnrollmentFacing.Front) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        val available = try {
            cameraProvider.hasCamera(selector)
        } catch (t: Throwable) {
            false
        }
        if (!available) {
            bound = false
            val which = if (facing == EnrollmentFacing.Front) "front" else "rear"
            _setupError.value = "No $which camera available"
            return
        }

        // iOS sets the session preset to .hd1280x720, which governs the frames
        // Vision sees. Here that constraint belongs on the ANALYSIS stream: the
        // preview surface size is CameraX's business (it fits the display), the
        // data resolution is ours.
        val resolution = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(EnrollmentTuning.CAPTURE_WIDTH, EnrollmentTuning.CAPTURE_HEIGHT),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            // iOS: output.alwaysDiscardsLateVideoFrames = true
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analysisExecutor) { analyze(it) }

        val previewUseCase = Preview.Builder().build()
        surfaceProvider?.let { previewUseCase.setSurfaceProvider(it) }

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(owner, selector, previewUseCase, analysis)

        provider = cameraProvider
        preview = previewUseCase
        _setupError.value = null
        _isConfigured.value = true
    }

    // ── per-frame work (analysis thread) ──────────────────────────────

    @androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
    private fun analyze(proxy: ImageProxy) {
        try {
            // rate-limit
            if (!rateLimiter.shouldProcess(nowNanos())) return
            val media = proxy.image ?: return
            val rotation = proxy.imageInfo.rotationDegrees

            val faces: List<Face> =
                Tasks.await(detector.process(InputImage.fromMediaImage(media, rotation)))

            // ML Kit reports boxes in the UPRIGHT frame — the one produced by
            // applying `rotation` — so the normalising denominators are the
            // upright dimensions, not the sensor's.
            val frameWidth = uprightWidth(proxy.width, proxy.height, rotation)
            val frameHeight = uprightHeight(proxy.width, proxy.height, rotation)

            if (faces.isEmpty()) {
                publish(box = null, quality = 0f, visible = false)
                mainExecutor.execute { sink.noteFaceLost() }
                return
            }

            val boxes = faces.map {
                val b = it.boundingBox
                normalizeBox(b.left, b.top, b.right, b.bottom, frameWidth, frameHeight)
            }
            val index = indexOfLargestBox(boxes)
            val face = faces[index]
            val box = boxes[index]

            val frame = LumaPlaneFrame(proxy, rotation)
            val q = qualityScorer.score(frame, box)
            val decision = decideFrame(
                box = box,
                headEulerAngleY = face.headEulerAngleY,
                headEulerAngleX = face.headEulerAngleX,
                facing = facing,
                quality = q,
                qualityFloor = sink::qualityFloor,
            )

            // Encode only when the frame is worth keeping, and measure scene
            // brightness only when it has something to explain. Both skips are
            // load-bearing at 15 Hz.
            val jpeg = if (decision.shouldEncodeJpeg) encodeJpeg(proxy, rotation) else null
            val brightness = if (decision.shouldComputeSceneLuma) {
                sceneLuma(frame)
            } else {
                ENROLLMENT_BRIGHTNESS_NOT_MEASURED
            }

            publish(box = box, quality = q, visible = true)
            mainExecutor.execute {
                sink.ingest(
                    yawDeg = decision.yawDeg,
                    pitchDeg = decision.pitchDeg,
                    quality = q,
                    framed = decision.framed,
                    brightness = brightness,
                    faceHeight = box.height.toDouble(),
                    jpeg = jpeg,
                )
            }
        } catch (t: Throwable) {
            // One bad frame must not take the analyser down for the rest of the
            // session — the next frame is 66ms away. Nothing is logged: the only
            // things in scope here are pixels of a resident's face.
            if (t is InterruptedException) Thread.currentThread().interrupt()
        } finally {
            proxy.close()
        }
    }

    private fun publish(box: Rect?, quality: Float, visible: Boolean) {
        _faceBox.value = box
        _quality.value = quality
        _faceVisible.value = visible
    }

    /**
     * The upright, **un-mirrored** frame as JPEG, downscaled to
     * [EnrollmentTuning.JPEG_TARGET_WIDTH] wide.
     */
    private fun encodeJpeg(proxy: ImageProxy, rotationDegrees: Int): ByteArray? {
        val source: Bitmap = try {
            proxy.toBitmap()
        } catch (t: Throwable) {
            return null
        }
        val scale = jpegScale(uprightWidth(proxy.width, proxy.height, rotationDegrees))
        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
            if (scale < 1f) postScale(scale, scale)
        }
        val upright = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        val out = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, EnrollmentTuning.JPEG_QUALITY, out)
        if (upright !== source) upright.recycle()
        source.recycle()
        return out.toByteArray()
    }
}

/**
 * [EnrollmentFrame] over an `ImageProxy`'s luma plane, addressed in upright
 * coordinates.
 *
 * Reads the plane in place rather than materialising a rotated copy: the two
 * consumers sample a few hundred pixels between them, so a whole-frame rotation
 * would cost far more than the arithmetic.
 */
private class LumaPlaneFrame(proxy: ImageProxy, rotationDegrees: Int) : EnrollmentFrame {
    private val plane = proxy.planes[0]
    private val buffer = plane.buffer
    private val rowStride = plane.rowStride
    private val pixelStride = plane.pixelStride
    private val sourceWidth = proxy.width
    private val sourceHeight = proxy.height
    private val rotation = rotationDegrees

    override val width: Int = uprightWidth(sourceWidth, sourceHeight, rotationDegrees)
    override val height: Int = uprightHeight(sourceWidth, sourceHeight, rotationDegrees)

    override fun luma(x: Int, y: Int): Int {
        val sx = sourceX(x, y, sourceWidth, sourceHeight, rotation)
        val sy = sourceY(x, y, sourceWidth, sourceHeight, rotation)
        val index = sy * rowStride + sx * pixelStride
        if (index < 0 || index >= buffer.limit()) return 0
        return buffer.get(index).toInt() and 0xFF
    }
}
