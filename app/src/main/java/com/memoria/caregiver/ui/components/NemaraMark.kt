package com.memoria.caregiver.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.R

/**
 * The corner radius of the mark as a fraction of its side — the iOS app-icon
 * squircle ratio, carried verbatim from NemaraMark.swift (`size * 0.2237`).
 *
 * Pulled out as a named constant so the value is unit-testable, because a
 * silently rounded ratio (0.22, 0.225) is invisible in review and visible only
 * in a screenshot diff.
 */
internal const val NEMARA_MARK_CORNER_RATIO = 0.2237f

/** Corner radius for a mark of side [size]. See [NEMARA_MARK_CORNER_RATIO]. */
internal fun nemaraMarkCornerRadius(size: Dp): Dp = size * NEMARA_MARK_CORNER_RATIO

/** Default side length, matching NemaraMark.swift's `init(size: CGFloat = 44)`. */
internal val NemaraMarkDefaultSize: Dp = 44.dp

/**
 * The Memoria brand mark — the terra-rust square with the cream "m",
 * mirroring the app icon. Using an image instead of live-rendering Text keeps
 * the in-app mark in pixel-parity with the launcher icon at every size and
 * removes any runtime font-registration risk.
 *
 * The asset is an opaque 1024x1024 square (no alpha channel), so its corners
 * render as a hard square on any background. It is clipped to a rounded rect at
 * the iOS app-icon squircle ratio so the mark reads as a rounded square,
 * matching the previous brand mark.
 *
 * **The bitmap is a parameter, not a resource lookup inside this file.** The
 * Swift says `Image("BrandMark")`, and the corresponding Android drawable does
 * not exist in this repo yet — `app/src/main/res` has no `drawable/` directory.
 * Adding it is outside this component's scope, so the mark is supplied by the
 * call site, exactly as the ported components take an `ImageVector` instead of
 * inventing an icon dependency. Once the asset lands, every call site reads:
 *
 * ```
 * NemaraMark(ImageBitmap.imageResource(R.drawable.brand_mark), size = 44.dp)
 * ```
 *
 * `ImageBitmap` rather than `Painter` is deliberate: only the bitmap overload of
 * [Image] accepts [FilterQuality], which is how SwiftUI's `.interpolation(.high)`
 * is expressed here. That interpolation is load-bearing — a 1024px source drawn
 * at 20-56dp is a large downscale, and the default filtering visibly aliases the
 * "m".
 */
@Composable
fun NemaraMark(
    modifier: Modifier = Modifier,
    size: Dp = NemaraMarkDefaultSize,
) {
    Image(
        // The component owns its asset, exactly as iOS does with
        // Image("BrandMark"). An earlier draft took the bitmap as a required
        // parameter because res/drawable did not exist yet; that pushed an
        // asset-loading concern onto every call site and, since nothing
        // supplied it, meant the mark could never render at all.
        bitmap = ImageBitmap.imageResource(R.drawable.brand_mark),
        contentDescription = null,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(nemaraMarkCornerRadius(size))),
        // SwiftUI's `.resizable()` under a fixed square frame fills the frame
        // without preserving aspect ratio; FillBounds is that behavior. The asset
        // is square, so this is identical to Fit today and stays faithful if the
        // asset is ever replaced with a non-square one.
        contentScale = ContentScale.FillBounds,
        // `.interpolation(.high)` on iOS. Compose's default is Low; High adds
        // mipmapped sampling, which is what a 1024 -> 44 downscale needs.
        filterQuality = FilterQuality.High,
    )
}
