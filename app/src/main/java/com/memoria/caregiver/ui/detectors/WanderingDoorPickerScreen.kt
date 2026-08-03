package com.memoria.caregiver.ui.detectors

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.data.model.DetectedDoor
import com.memoria.caregiver.data.model.Detector
import com.memoria.caregiver.data.model.WanderingDoorInput
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Radius
import com.memoria.caregiver.ui.theme.Spacing
import com.memoria.caregiver.ui.theme.Terra
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Exit-door picker for the Wandering detector.
 * Spec: `Features/Patient/Settings/WanderingDoorPickerView.swift`, PARITY.md §23.
 *
 * Shows a captured camera frame (cached, so it does not re-analyze on every open
 * — use Rescan to refresh), highlights the doorways the VLM found as tappable
 * dots, and lets the caregiver:
 *   - tap highlighted doors to select/deselect MULTIPLE exits (source = "vlm"),
 *   - tap empty space to place their own door (source = "manual"), tap it again
 *     to remove it,
 *   - or declare "No door leads outside" (turns the detector off).
 *
 * Saved via `POST /v1/cameras/{id}/wandering-door`, which regenerates the
 * wandering prompt so only leaving THROUGH one of the chosen doors counts.
 *
 * ## What this file owns and what the host owns
 *
 * Per `ui/auth/AuthUiState.kt`, the screen is a pure `@Composable` over
 * [WanderingDoorPickerState] plus callbacks: no ViewModel, no I/O, no cache.
 * The frame fetch, the door-detection call, and both saves are the host's.
 *
 * The selection is **hoisted** rather than kept in `remember`, unlike iOS where
 * it lives in `@State` on the view. It decides the bytes that go on the wire, so
 * a second copy inside the composition would be a second source of truth that no
 * test of the state type could reach. Every transition that touches it is a pure
 * function here — [tapped], [existingSelection], [dropStackedDetections],
 * [wanderingDoorInputs] — so the host wires them without re-deriving the rules,
 * and they are unit-tested without a composition.
 *
 * **Two of those the host must call at the right moment**, because they happen on
 * load rather than on tap and iOS runs them inside its own load path:
 *  1. when the frame (and any cached doors) arrive: `existingSelection(...)`,
 *     once — iOS guards it with `didApplyExisting`.
 *  2. when background door detection returns: `dropStackedDetections(...)` before
 *     storing the result.
 */
@Composable
fun WanderingDoorPickerScreen(
    state: WanderingDoorPickerState,
    onCancel: () -> Unit,
    onRescan: () -> Unit,
    onSelectionChange: (WanderingDoorSelection) -> Unit,
    onSave: (List<WanderingDoorInput>) -> Unit,
    onNoExitDoor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(Paper)) {

        NavigationBar(
            title = state.navigationTitle,
            rescanEnabled = !state.isBusy,
            onCancel = onCancel,
            onRescan = onRescan,
        )

        // Everything below the bar gets the rest of the screen, which is what
        // SwiftUI's ZStack over `NemaraColor.paper.ignoresSafeArea()` gives the
        // content there. Without the weight a Column child is measured against
        // an unbounded height, and both the centred status view and the
        // aspect-fit canvas depend on knowing how much room they have.
        val fill = Modifier.weight(1f)

        when (val phase = state.phase) {
            // iOS renders `.saving` with a fixed message rather than one carried
            // on the case, so the copy lives in WanderingDoorCopy, not the phase.
            is WanderingDoorPhase.Loading ->
                StatusView(phase.message, spinner = true, modifier = fill)
            WanderingDoorPhase.Saving ->
                StatusView(WanderingDoorCopy.SAVING, spinner = true, modifier = fill)
            is WanderingDoorPhase.Blocked -> StatusView(
                message = phase.message,
                spinner = false,
                modifier = fill,
                retry = true,
                onRetry = onRescan,
            )
            is WanderingDoorPhase.Failed -> StatusView(
                message = phase.message,
                spinner = false,
                modifier = fill,
                retry = true,
                onRetry = onRescan,
            )
            WanderingDoorPhase.Ready -> ReadyView(
                state = state,
                onSelectionChange = onSelectionChange,
                onSave = onSave,
                onNoExitDoor = onNoExitDoor,
                modifier = fill,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

/** iOS: `private enum Phase`. */
sealed interface WanderingDoorPhase {
    /**
     * The message differs by entry point — [WanderingDoorCopy.LOADING_INITIAL] on
     * open, [WanderingDoorCopy.LOADING_RELOAD] on Rescan — and the host picks it,
     * because the host is what knows which of the two it started.
     */
    data class Loading(val message: String) : WanderingDoorPhase

    data object Ready : WanderingDoorPhase

    /** The frame could not be pulled for a reason the caregiver can act on. */
    data class Blocked(val message: String) : WanderingDoorPhase

    data class Failed(val message: String) : WanderingDoorPhase

    data object Saving : WanderingDoorPhase
}

/**
 * A door the caregiver placed by tapping empty space. iOS carries a `UUID` for
 * identity; here a manual door is identified by its index in
 * [WanderingDoorSelection.manualDoors], which is the only thing the id was ever
 * used for (removal from that one array) and keeps the type comparable in tests.
 */
data class ManualDoor(
    /** `[x, y, w, h]` as fractions of the frame. */
    val box: List<Double>,
)

/** iOS: `selectedDoorIDs` + `manualDoors`. */
data class WanderingDoorSelection(
    /** [DetectedDoor.id]s of the VLM-found doors the caregiver has selected. */
    val selectedDoorIds: Set<String> = emptySet(),
    val manualDoors: List<ManualDoor> = emptyList(),
) {
    /** iOS: `selectionCount`. Drives the CTA's title and its disabled state. */
    val count: Int get() = selectedDoorIds.size + manualDoors.size
}

/** Everything `WanderingDoorPickerView` keeps in `@State`, hoisted. */
data class WanderingDoorPickerState(
    /** Only for the navigation title; iOS takes it as a `let`. */
    val cameraName: String = "",
    val phase: WanderingDoorPhase = WanderingDoorPhase.Loading(WanderingDoorCopy.LOADING_INITIAL),
    /** The cached camera frame. Null until the frame load returns. */
    val frame: ImageBitmap? = null,
    /** The doorways the VLM found, already run through [dropStackedDetections]. */
    val detectedDoors: List<DetectedDoor> = emptyList(),
    /** The background door-detection call is in flight; the eyebrow says so. */
    val isDetectingDoors: Boolean = false,
    val selection: WanderingDoorSelection = WanderingDoorSelection(),
) {
    /** iOS: `isBusy` — true while loading or saving. Disables Rescan. */
    val isBusy: Boolean
        get() = phase is WanderingDoorPhase.Loading || phase is WanderingDoorPhase.Saving

    /**
     * iOS: `cameraName.isEmpty ? "Exit doors" : "\(cameraName) · exit doors"`.
     * `isEmpty`, not blank — a name of one space keeps the interpolated form.
     */
    val navigationTitle: String
        get() = if (cameraName.isEmpty()) "Exit doors" else "$cameraName · exit doors"

    /** iOS: `frameAspect`. Falls back to 16:9 until the frame is known. */
    val frameAspect: Float
        get() {
            val image = frame ?: return DEFAULT_FRAME_ASPECT
            if (image.height <= 0) return DEFAULT_FRAME_ASPECT
            return image.width.toFloat() / image.height.toFloat()
        }
}

/**
 * Every user-facing string this screen produces that is NOT rendered by it.
 *
 * The two loading messages and the two save-failure sentences are set by the
 * host (it owns the calls that fail), so they would otherwise be reinvented at
 * the call site and drift from the Swift. They are the picker's copy, so they
 * live with the picker.
 */
object WanderingDoorCopy {
    /** First open. */
    const val LOADING_INITIAL: String = "Loading the camera view…"

    /** Rescan, and "Try again". */
    const val LOADING_RELOAD: String = "Getting a fresh view…"

    const val SAVING: String = "Saving…"

    /** `saveSelection` failure. */
    const val SAVE_FAILED: String = "Couldn't save the exit doors. Please try again."

    /** `saveNoExitDoor` failure. Deliberately the shorter sentence, as on iOS. */
    const val SAVE_NO_EXIT_DOOR_FAILED: String = "Couldn't save. Please try again."
}

// ---------------------------------------------------------------------------
// Tuning constants, carried from WanderingDoorPickerView.swift
// ---------------------------------------------------------------------------

/** A tap within this many points of a dot hits it (select/remove). */
internal val HitRadius = 30.dp

/** Default width of a tap-placed door box, as a fraction of the frame. */
internal const val MANUAL_DOOR_WIDTH: Double = 0.12

/** Default height of a tap-placed door box, as a fraction of the frame. */
internal const val MANUAL_DOOR_HEIGHT: Double = 0.20

/**
 * A detected door overlapping an already-placed manual door by more than this is
 * dropped, so two dots never stack.
 */
internal const val STACKED_DETECTION_IOU: Double = 0.4

/** A stored door matches a detected one above this, and is restored as a selection. */
internal const val EXISTING_MATCH_IOU: Double = 0.5

/** `frameAspect`'s fallback while no frame is loaded: 16:9. */
internal const val DEFAULT_FRAME_ASPECT: Float = 16f / 9f

private val SelectedDotDiameter = 30.dp
private val UnselectedDotDiameter = 24.dp
private val SelectedDotStroke = 2.5.dp
private val UnselectedDotStroke = 2.dp

/** `.shadow(color: .black.opacity(0.35), radius: 3, y: 1)` on every dot. */
private val DotShadow = 3.dp

/** The checkmark / walking figure inside a selected dot: `.system(size: 13, weight: .bold)`. */
private val DotGlyphSize = 13.dp

/** `video.slash` on the blocked and failed states: `.system(size: 34, weight: .light)`. */
private val StatusGlyphSize = 34.dp

/**
 * `.lineSpacing(3)` on the two 13pt paragraphs.
 *
 * Inter's natural line height is 1.21 x the point size, so at the bodySmall
 * token's 13sp that is 15.73 and SwiftUI's `+3` is 18.73. Same derivation as
 * `ui/home/EmptyRosterScreen.kt`.
 */
private val BodySmallLineHeightPlus3 = 18.73.sp

// ---------------------------------------------------------------------------
// Pure logic
// ---------------------------------------------------------------------------

/**
 * iOS: `handleTap`. The three behaviors, evaluated in order:
 *  1. within [hitRadius] of a VLM dot -> toggle its selection,
 *  2. within [hitRadius] of a manual dot -> remove it,
 *  3. empty space inside the frame -> place a new manual door.
 *
 * A tap outside the fitted frame rect places nothing, because `normalizedPoint`
 * returns nil there — that is what keeps the letterbox bars inert.
 *
 * @param tap in the canvas's coordinates, pixels.
 * @param fit the fitted frame rect, from [fittedRect].
 * @param hitRadius [HitRadius] in pixels — a dp is not a pixel, and this
 *   function is where the conversion has to have already happened.
 */
internal fun WanderingDoorSelection.tapped(
    tap: Offset,
    fit: Rect,
    doors: List<DetectedDoor>,
    hitRadius: Float,
): WanderingDoorSelection {
    val hitDoor = nearestIndex(doors.map { boxCenter(it.box, fit) }, tap, hitRadius)
    if (hitDoor != null) {
        val id = doors[hitDoor].id
        return copy(
            selectedDoorIds =
                if (selectedDoorIds.contains(id)) selectedDoorIds - id else selectedDoorIds + id,
        )
    }

    val hitManual = nearestIndex(manualDoors.map { boxCenter(it.box, fit) }, tap, hitRadius)
    if (hitManual != null) {
        return copy(manualDoors = manualDoors.filterIndexed { index, _ -> index != hitManual })
    }

    val n = normalizedPoint(tap, fit) ?: return this
    // min(max(v, 0), 1 - size): clamped so a door placed at the edge stays whole
    // and inside the frame.
    val x = (n.x.toDouble() - MANUAL_DOOR_WIDTH / 2).coerceIn(0.0, 1.0 - MANUAL_DOOR_WIDTH)
    val y = (n.y.toDouble() - MANUAL_DOOR_HEIGHT / 2).coerceIn(0.0, 1.0 - MANUAL_DOOR_HEIGHT)
    return copy(
        manualDoors = manualDoors + ManualDoor(
            listOf(x, y, MANUAL_DOOR_WIDTH, MANUAL_DOOR_HEIGHT),
        ),
    )
}

/**
 * iOS: `nearest(_:to:within:)`, returning the position rather than an id.
 *
 * Ties keep the FIRST candidate, matching Swift's strict `d < best!.1`. Two dots
 * exactly equidistant is not hypothetical — a door and the manual door placed on
 * top of it sit at the same centre.
 */
internal fun nearestIndex(centers: List<Offset>, tap: Offset, radius: Float): Int? {
    var bestIndex: Int? = null
    var bestDistance = 0f
    centers.forEachIndexed { index, c ->
        val d = hypot(c.x - tap.x, c.y - tap.y)
        if (d <= radius && (bestIndex == null || d < bestDistance)) {
            bestIndex = index
            bestDistance = d
        }
    }
    return bestIndex
}

/**
 * iOS: `applyExistingSelectionIfNeeded`. Pre-selects the doors already saved for
 * this camera so the picker opens reflecting the current configuration.
 *
 * A stored box that matches a detected door above [EXISTING_MATCH_IOU] comes back
 * as a selection; anything unmatched comes back as a manual door, so a
 * configuration saved before the VLM moved on is never silently lost.
 *
 * `noExitDoor` restores nothing: the caregiver said there is no exit here, and
 * pre-selecting a door would contradict the thing they saved.
 */
internal fun existingSelection(
    existing: Detector.DoorConfig?,
    doors: List<DetectedDoor>,
): WanderingDoorSelection {
    if (existing == null || existing.noExitDoor) return WanderingDoorSelection()
    val selected = LinkedHashSet<String>()
    val manual = mutableListOf<ManualDoor>()
    for (stored in existing.doors) {
        val box = stored.box ?: continue
        if (box.size != 4) continue
        val match = doors.firstOrNull { iou(it.box, box) > EXISTING_MATCH_IOU }
        if (match != null) selected.add(match.id) else manual.add(ManualDoor(box))
    }
    return WanderingDoorSelection(selectedDoorIds = selected, manualDoors = manual)
}

/**
 * iOS: the filter inside `detectDoorsInBackground`. Drops any detected door that
 * is basically on top of a door the caregiver already placed, so two dots never
 * stack.
 *
 * Run **once, against the manual doors at the moment detection returns** — not on
 * every render. A later manual placement does not retire a dot on iOS, and it
 * cannot land on one anyway: a tap within [HitRadius] of a dot toggles that dot
 * instead of placing anything.
 */
internal fun dropStackedDetections(
    found: List<DetectedDoor>,
    manualDoors: List<ManualDoor>,
): List<DetectedDoor> = found.filter { d ->
    manualDoors.none { iou(it.box, d.box) > STACKED_DETECTION_IOU }
}

/**
 * iOS: the payload built in `saveSelection`. Selected VLM doors first, in
 * detection order and carrying their labels, then the manual ones.
 *
 * The `source` strings are the wire contract, not display text: the backend
 * regenerates the wandering prompt from them.
 */
internal fun wanderingDoorInputs(
    doors: List<DetectedDoor>,
    selection: WanderingDoorSelection,
): List<WanderingDoorInput> {
    val payload = mutableListOf<WanderingDoorInput>()
    for (door in doors) {
        if (selection.selectedDoorIds.contains(door.id)) {
            payload.add(WanderingDoorInput(box = door.box, label = door.label, source = "vlm"))
        }
    }
    for (m in selection.manualDoors) {
        payload.add(WanderingDoorInput(box = m.box, label = null, source = "manual"))
    }
    return payload
}

/** iOS: `setButtonTitle`. */
internal fun setExitDoorsTitle(count: Int): String = when (count) {
    0 -> "Set exit doors"
    1 -> "Set 1 exit door"
    else -> "Set $count exit doors"
}

// ---- Geometry -------------------------------------------------------------

/**
 * iOS: `fittedRect(imageSize:in:)` — the aspect-fit rect of an image inside a
 * container, centred, in the container's coordinates.
 *
 * A zero-sized image falls back to the whole container, which is also what iOS
 * passes when no frame has loaded (`loaded?.image.size ?? geo.size`), so an
 * empty canvas is still fully tappable.
 */
internal fun fittedRect(imageSize: Size, container: Size): Rect {
    if (imageSize.width <= 0f || imageSize.height <= 0f) {
        return Rect(Offset.Zero, container)
    }
    val scale = min(container.width / imageSize.width, container.height / imageSize.height)
    val w = imageSize.width * scale
    val h = imageSize.height * scale
    return Rect(Offset((container.width - w) / 2f, (container.height - h) / 2f), Size(w, h))
}

/** Centre of a normalized `[x, y, w, h]` box in view coordinates. */
internal fun boxCenter(box: List<Double>, fit: Rect): Offset {
    if (box.size != 4) return fit.center
    return Offset(
        fit.left + ((box[0] + box[2] / 2) * fit.width).toFloat(),
        fit.top + ((box[1] + box[3] / 2) * fit.height).toFloat(),
    )
}

/** A view point as a fraction of the frame, or null when it is outside the frame. */
internal fun normalizedPoint(loc: Offset, fit: Rect): Offset? {
    if (!fit.contains(loc) || fit.width <= 0f || fit.height <= 0f) return null
    return Offset((loc.x - fit.left) / fit.width, (loc.y - fit.top) / fit.height)
}

/** Intersection over union of two normalized `[x, y, w, h]` boxes. */
internal fun iou(a: List<Double>, b: List<Double>): Double {
    if (a.size != 4 || b.size != 4) return 0.0
    val ax2 = a[0] + a[2]
    val ay2 = a[1] + a[3]
    val bx2 = b[0] + b[2]
    val by2 = b[1] + b[3]
    val ix1 = maxOf(a[0], b[0])
    val iy1 = maxOf(a[1], b[1])
    val ix2 = minOf(ax2, bx2)
    val iy2 = minOf(ay2, by2)
    val iw = maxOf(0.0, ix2 - ix1)
    val ih = maxOf(0.0, iy2 - iy1)
    val inter = iw * ih
    val union = a[2] * a[3] + b[2] * b[3] - inter
    return if (union > 0) inter / union else 0.0
}

// ---------------------------------------------------------------------------
// Chrome
// ---------------------------------------------------------------------------

/**
 * The inline navigation bar: centred title, "Cancel" leading, Rescan trailing.
 *
 * Compose has no navigation bar, so it is drawn the way `TermsGateScreen` and
 * `CountryPickerSheet` draw theirs (DIFFERENCES.md D9).
 */
@Composable
private fun NavigationBar(
    title: String,
    rescanEnabled: Boolean,
    onCancel: () -> Unit,
    onRescan: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            // No sans token is 17, so the inline nav title (SF Pro 17 semibold)
            // takes the nearest: Inter 16 semibold. Same choice as TermsGate.
            style = NemaraType.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = Ink,
            maxLines = 1,
        )
        Text(
            text = "Cancel",
            modifier = Modifier
                .align(Alignment.CenterStart)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                ) { onCancel() },
            style = NemaraType.bodyLarge,
            color = Ink2,
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .testTag(RescanTestTag)
                // A plain SwiftUI toolbar button — unlike NemaraButton, which
                // ignores isEnabled by design — dims itself while disabled, so
                // the alpha here is carrying iOS behavior, not adding polish.
                .alpha(if (rescanEnabled) 1f else DisabledToolbarAlpha)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = rescanEnabled,
                    role = Role.Button,
                ) { onRescan() }
                // A drawn glyph with no label. iOS's toolbar `Image(systemName:
                // "arrow.clockwise")` gives VoiceOver a name from the symbol;
                // a Canvas has none, so it is stated.
                .semantics { contentDescription = "Rescan" },
        ) {
            ArrowClockwiseGlyph(size = NavGlyphSize, color = Terra)
        }
    }
}

/** Toolbar symbols render at the navigation bar's 17pt body size. */
private val NavGlyphSize = 17.dp

/**
 * SwiftUI's own dimming for a disabled control. The framework does not publish
 * the value; 0.3 is the measured one, so this is an approximation of a platform
 * default rather than a constant carried from the Swift.
 */
private const val DisabledToolbarAlpha = 0.3f

/** Lets a test reach the Rescan button, which is a symbol with no label. */
internal const val RescanTestTag = "wandering-door-rescan"

/** iOS: `statusView(_:spinner:retry:)`. Fills the space under the nav bar. */
@Composable
private fun StatusView(
    message: String,
    spinner: Boolean,
    modifier: Modifier = Modifier,
    retry: Boolean = false,
    onRetry: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (spinner) {
            // iOS: ProgressView().controlSize(.large).tint(terra). Compose's
            // default indicator is 40dp, which is the same order as a large
            // UIActivityIndicator, so only the tint is carried explicitly.
            CircularProgressIndicator(color = Terra)
        } else {
            VideoSlashGlyph(size = StatusGlyphSize, color = Ink3)
        }
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = Spacing.xxl),
            style = NemaraType.bodySmall,
            color = Ink2,
            textAlign = TextAlign.Center,
            // iOS: .lineSpacing(3)
            lineHeight = BodySmallLineHeightPlus3,
        )
        if (retry) {
            NemaraButton(
                title = "Try again",
                variant = NemaraButtonVariant.Secondary,
                onClick = onRetry,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The picker
// ---------------------------------------------------------------------------

@Composable
private fun ReadyView(
    state: WanderingDoorPickerState,
    onSelectionChange: (WanderingDoorSelection) -> Unit,
    onSave: (List<WanderingDoorInput>) -> Unit,
    onNoExitDoor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Instructions(isDetectingDoors = state.isDetectingDoors)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                // The canvas is aspect-fit inside whatever height is left after
                // the instructions and the controls, exactly as SwiftUI's
                // .aspectRatio(_, contentMode: .fit) behaves inside a VStack.
                .weight(1f, fill = false)
                .padding(horizontal = Spacing.md),
            contentAlignment = Alignment.Center,
        ) {
            FrameCanvas(
                state = state,
                onSelectionChange = onSelectionChange,
                modifier = Modifier.aspectRatio(state.frameAspect),
            )
        }

        Controls(
            state = state,
            onSave = onSave,
            onNoExitDoor = onNoExitDoor,
        )
    }
}

@Composable
private fun Instructions(isDetectingDoors: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // iOS branches three ways but two branches are the same string: the
        // eyebrow only changes while detection is running.
        NemaraEyebrow(
            text = if (isDetectingDoors) {
                "TAP EACH EXIT DOOR · FINDING DOORS…"
            } else {
                "TAP EACH EXIT DOOR"
            },
        )
        Text(
            text = "Tap every door that leads outside. Tap anywhere else to place one " +
                "yourself; tap a marker again to remove it.",
            modifier = Modifier.padding(horizontal = Spacing.xl),
            style = NemaraType.bodySmall,
            color = Ink2,
            textAlign = TextAlign.Center,
            // iOS: .lineSpacing(3)
            lineHeight = BodySmallLineHeightPlus3,
        )
    }
}

/**
 * The frame, the dots, and the tap target.
 *
 * The whole canvas is the tap target — including the letterbox bars, which
 * [normalizedPoint] then rejects — because iOS puts `.contentShape(Rectangle())`
 * and the tap gesture on the full `GeometryReader`, not on the image.
 */
@Composable
private fun FrameCanvas(
    state: WanderingDoorPickerState,
    onSelectionChange: (WanderingDoorSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val hitRadiusPx = with(density) { HitRadius.toPx() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // The gesture block is installed once; these keep it reading the current
    // state and callback instead of the ones captured on the first composition.
    val latestState by rememberUpdatedState(state)
    val latestOnSelectionChange by rememberUpdatedState(onSelectionChange)

    val container = Size(canvasSize.width.toFloat(), canvasSize.height.toFloat())
    val imageSize = state.frame
        ?.let { Size(it.width.toFloat(), it.height.toFloat()) }
        ?: container
    val fit = fittedRect(imageSize, container)

    Box(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .testTag(FrameCanvasTestTag)
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val current = latestState
                    val bounds = Size(this.size.width.toFloat(), this.size.height.toFloat())
                    val currentImage = current.frame
                        ?.let { Size(it.width.toFloat(), it.height.toFloat()) }
                        ?: bounds
                    latestOnSelectionChange(
                        current.selection.tapped(
                            tap = tap,
                            fit = fittedRect(currentImage, bounds),
                            doors = current.detectedDoors,
                            hitRadius = hitRadiusPx,
                        ),
                    )
                }
            },
    ) {
        val frame = state.frame
        if (frame != null && canvasSize != IntSize.Zero) {
            Image(
                bitmap = frame,
                contentDescription = null,
                modifier = Modifier
                    .offset { IntOffset(fit.left.roundToInt(), fit.top.roundToInt()) }
                    .size(
                        width = with(density) { fit.width.toDp() },
                        height = with(density) { fit.height.toDp() },
                    )
                    .clip(RoundedCornerShape(Radius.md)),
                // iOS: .resizable().frame(width:height:) — the fitted rect is
                // already the image's own aspect, so this never distorts.
                contentScale = ContentScale.FillBounds,
            )
        }

        if (canvasSize != IntSize.Zero) {
            // VLM-detected doors (selectable dots).
            for (door in state.detectedDoors) {
                DoorDot(
                    center = boxCenter(door.box, fit),
                    selected = state.selection.selectedDoorIds.contains(door.id),
                    manual = false,
                )
            }
            // Manual (tap-placed) doors, always drawn selected.
            for (m in state.selection.manualDoors) {
                DoorDot(center = boxCenter(m.box, fit), selected = true, manual = true)
            }
        }
    }
}

internal const val FrameCanvasTestTag = "wandering-door-canvas"

/**
 * One dot. iOS: `doorDot(selected:manual:)`.
 *
 * The dots are drawn, never tapped — iOS marks them `.allowsHitTesting(false)`
 * so the canvas's own gesture decides what a tap meant. Here they simply carry
 * no pointer modifier, which has the same effect.
 *
 * The box is a stroke wider than the circle because SwiftUI's `.stroke` is
 * centred on the path and spills half its width outside the 30pt frame.
 */
@Composable
private fun DoorDot(center: Offset, selected: Boolean, manual: Boolean) {
    val diameter = if (selected) SelectedDotDiameter else UnselectedDotDiameter
    val strokeWidth = if (selected) SelectedDotStroke else UnselectedDotStroke
    val boxSide = diameter + strokeWidth
    val density = LocalDensity.current
    val halfBoxPx = with(density) { boxSide.toPx() } / 2f

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (center.x - halfBoxPx).roundToInt(),
                    (center.y - halfBoxPx).roundToInt(),
                )
            }
            .size(boxSide),
        contentAlignment = Alignment.Center,
    ) {
        // iOS: .shadow(color: .black.opacity(0.35), radius: 3, y: 1). Compose's
        // elevation shadow is the platform's own and takes no offset, so this is
        // a stand-in of the same weight rather than a match.
        Box(
            modifier = Modifier
                .size(diameter)
                .shadow(
                    elevation = DotShadow,
                    shape = CircleShape,
                    clip = false,
                    ambientColor = Color.Black,
                    spotColor = Color.Black,
                ),
        )
        Canvas(modifier = Modifier.size(boxSide)) {
            val radius = diameter.toPx() / 2f
            drawCircle(
                color = if (selected) Terra else Terra.copy(alpha = 0.35f),
                radius = radius,
                center = this.center,
            )
            drawCircle(
                color = Color.White,
                radius = radius,
                center = this.center,
                style = Stroke(width = strokeWidth.toPx()),
            )
        }
        if (selected) {
            if (manual) {
                FigureWalkGlyph(size = DotGlyphSize, color = Color.White)
            } else {
                CheckmarkGlyph(size = DotGlyphSize, color = Color.White)
            }
        }
    }
}

@Composable
private fun Controls(
    state: WanderingDoorPickerState,
    onSave: (List<WanderingDoorInput>) -> Unit,
    onNoExitDoor: () -> Unit,
) {
    val count = state.selection.count
    Column(
        modifier = Modifier.padding(Spacing.md),
        // iOS: VStack(spacing: 10). 10 is not on the spacing scale.
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NemaraButton(
            title = setExitDoorsTitle(count),
            // iOS pairs .disabled with an explicit .opacity, because its
            // NemaraButton does not dim on its own (and neither does ours).
            modifier = Modifier.alpha(if (count == 0) 0.5f else 1f),
            variant = NemaraButtonVariant.Terra,
            block = true,
            icon = CheckmarkIcon,
            enabled = count != 0,
        ) {
            // iOS: `guard !payload.isEmpty`. The button is already inert at zero;
            // this holds if a selection is ever counted without producing input.
            val payload = wanderingDoorInputs(state.detectedDoors, state.selection)
            if (payload.isNotEmpty()) onSave(payload)
        }

        NemaraButton(
            title = "No door leads outside",
            variant = NemaraButtonVariant.Ghost,
            block = true,
            onClick = onNoExitDoor,
        )
    }
}

// ---------------------------------------------------------------------------
// Symbols
// ---------------------------------------------------------------------------
//
// Four SF Symbols are named here — "checkmark", "figure.walk",
// "arrow.clockwise" and "video.slash". There is no icon dependency in the
// version catalog and this unit may not add one, so each is drawn with `Canvas`
// the way `DetectorGlyphs.kt`, `SettingsChrome.kt` and `CountryPicker.kt` draw
// theirs. `NemaraGlyph` in this package draws none of the four (its
// "figure.walk.motion" is the motion-line variant, a different symbol), so these
// are local.
//
// Geometry is written as fractions of the glyph box, so one description serves
// every point size.

/** Stroke weight as a fraction of the box, for an SF Symbol at `.bold`. */
private const val BOLD_STROKE_RATIO = 0.16f

/** Same, for `.light` — the weight the status symbol is drawn at. */
private const val LIGHT_STROKE_RATIO = 0.06f

/** Same, for `.semibold` — the toolbar's default weight. */
private const val SEMIBOLD_STROKE_RATIO = 0.145f

/** `checkmark`, bold. The tick inside a selected VLM dot. */
@Composable
private fun CheckmarkGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawPolyline(
            points = listOf(
                Offset(w * 0.12f, w * 0.55f),
                Offset(w * 0.38f, w * 0.82f),
                Offset(w * 0.88f, w * 0.20f),
            ),
            color = color,
            strokeWidth = w * BOLD_STROKE_RATIO,
        )
    }
}

/**
 * `figure.walk`, bold — the mark on a door the caregiver placed themselves.
 *
 * Head, torso, two legs mid-stride, two arms. Not Apple's artwork, which is not
 * licensable off-platform; it reads as a walking person at 13dp, which is the
 * whole job.
 */
@Composable
private fun FigureWalkGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * BOLD_STROKE_RATIO
        drawCircle(color, radius = w * 0.13f, center = Offset(w * 0.52f, w * 0.13f))
        // Torso.
        drawPolyline(
            points = listOf(Offset(w * 0.52f, w * 0.30f), Offset(w * 0.46f, w * 0.58f)),
            color = color,
            strokeWidth = stroke,
        )
        // Leading leg, then trailing leg.
        drawPolyline(
            points = listOf(
                Offset(w * 0.46f, w * 0.58f),
                Offset(w * 0.68f, w * 0.74f),
                Offset(w * 0.70f, w * 0.96f),
            ),
            color = color,
            strokeWidth = stroke,
        )
        drawPolyline(
            points = listOf(
                Offset(w * 0.46f, w * 0.58f),
                Offset(w * 0.30f, w * 0.76f),
                Offset(w * 0.30f, w * 0.96f),
            ),
            color = color,
            strokeWidth = stroke,
        )
        // Arms.
        drawPolyline(
            points = listOf(Offset(w * 0.50f, w * 0.38f), Offset(w * 0.74f, w * 0.48f)),
            color = color,
            strokeWidth = stroke,
        )
        drawPolyline(
            points = listOf(Offset(w * 0.50f, w * 0.38f), Offset(w * 0.26f, w * 0.50f)),
            color = color,
            strokeWidth = stroke,
        )
    }
}

/** `arrow.clockwise` — the Rescan toolbar action. */
@Composable
private fun ArrowClockwiseGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * SEMIBOLD_STROKE_RATIO
        val inset = stroke / 2f
        // An almost-closed ring, open at the top right for the arrowhead.
        drawArc(
            color = color,
            startAngle = -60f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(inset + w * 0.08f, inset + w * 0.08f),
            size = Size(w - 2f * inset - w * 0.16f, w - 2f * inset - w * 0.16f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        // Arrowhead at the ring's open end, pointing clockwise.
        drawPolyline(
            points = listOf(
                Offset(w * 0.62f, w * 0.10f),
                Offset(w * 0.90f, w * 0.24f),
                Offset(w * 0.70f, w * 0.44f),
            ),
            color = color,
            strokeWidth = stroke,
        )
    }
}

/**
 * `video.slash`, light — the blocked/failed state's symbol. A camcorder body
 * with its lens tab, struck through.
 */
@Composable
private fun VideoSlashGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * LIGHT_STROKE_RATIO
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.06f, w * 0.28f),
            size = Size(w * 0.56f, w * 0.44f),
            cornerRadius = CornerRadius(w * 0.10f),
            style = Stroke(width = stroke),
        )
        // Lens tab: the wedge on the right of the body.
        drawPolyline(
            points = listOf(
                Offset(w * 0.66f, w * 0.42f),
                Offset(w * 0.94f, w * 0.28f),
                Offset(w * 0.94f, w * 0.72f),
                Offset(w * 0.66f, w * 0.58f),
            ),
            color = color,
            strokeWidth = stroke,
        )
        // The slash.
        drawPolyline(
            points = listOf(Offset(w * 0.08f, w * 0.86f), Offset(w * 0.92f, w * 0.14f)),
            color = color,
            strokeWidth = stroke,
        )
    }
}

/**
 * `checkmark` again, as an [ImageVector], because `NemaraButton` takes one for
 * its leading icon — this is the `systemImage: "checkmark"` on the CTA. Same
 * path as [CheckmarkGlyph], on a 24-unit viewport.
 */
private val CheckmarkIcon: ImageVector = ImageVector.Builder(
    name = "checkmark",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2.6f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(2.9f, 13.2f)
        lineTo(9.1f, 19.7f)
        lineTo(21.1f, 4.8f)
    }
}.build()

/** Round-capped segments through [points]. See `CountryPicker.kt` for the rationale. */
private fun DrawScope.drawPolyline(
    points: List<Offset>,
    color: Color,
    strokeWidth: Float,
) {
    for (i in 0 until points.size - 1) {
        drawLine(
            color = color,
            start = points[i],
            end = points[i + 1],
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}
