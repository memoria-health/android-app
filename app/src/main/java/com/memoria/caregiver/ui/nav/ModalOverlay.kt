package com.memoria.caregiver.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.memoria.caregiver.ui.theme.Paper

/**
 * A full-surface screen drawn over whatever is already up.
 *
 * iOS presents these as `.sheet` / `.fullScreenCover`, which covers the
 * presenter and — crucially — makes it untouchable while it is up. Compose has
 * no such guarantee: a sibling later in a `Box` is drawn on top, but hit
 * testing walks *through* it to the shell underneath wherever the overlay's own
 * content has no pointer handler of its own. Without this, the patient tab bar
 * and the roster rows stay tappable behind a form, and a stray tap on the strip
 * beside a button switches tabs under a half-filled screen.
 *
 * The fix is a scrim beneath the content and above everything else: it paints
 * the background and swallows any pointer event that the content did not take.
 * Content is drawn after it, so the content still gets every event first —
 * Compose hit-tests the topmost child of a `Box` before its earlier siblings.
 *
 * Overlays that predate this — the contact-support sheet and the event stack —
 * do not use it and have the same gap.
 */
@Composable
fun ModalOverlay(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        Spacer(
            Modifier
                .fillMaxSize()
                .background(Paper)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        // What protects the content is the ORDER, not the pass:
                        // the scrim is an earlier sibling, so it is under the
                        // content and never sees a pointer the content took.
                        // (Verified by mutation — moving `content()` above this
                        // Spacer breaks all three of ModalOverlayTest's cases.)
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                },
        )
        content()
    }
}
