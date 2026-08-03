package com.memoria.caregiver.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.memoria.caregiver.legal.LegalConstants
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonSize
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.plainSelectable
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.PaperDim
import com.memoria.caregiver.ui.theme.Radius
import com.memoria.caregiver.ui.theme.Spacing
import com.memoria.caregiver.ui.theme.Terra
import kotlinx.coroutines.flow.first

// ---------------------------------------------------------------------------
// The documents
// ---------------------------------------------------------------------------

/**
 * iOS: `LegalConstants.documents`.
 *
 * These were declared privately here while this file was being written, because
 * that port task owned only two files. They now come from [LegalConstants],
 * which is where the version string the sign-up call sends lives too — the
 * document a caregiver reads and the version recorded against their account have
 * to be two halves of one definition, or they can drift.
 */
private val LegalDocuments = LegalConstants.documents

// ---------------------------------------------------------------------------
// The gate
// ---------------------------------------------------------------------------

/**
 * Review-and-accept gate for the Terms of Service + Privacy Notice, presented as
 * a sheet from signup. Spec: `Features/Legal/TermsConsentView.swift`, PARITY.md §16.
 *
 * "I agree" unlocks only after the reader scrolls to the end of BOTH documents;
 * **there is no accept-without-reading path.** Cancel returns to the form without
 * agreeing, so signup stays blocked, matching "you cannot complete signup unless
 * you agree."
 *
 * ## Where the scroll gate lives
 *
 * iOS keeps `reached: Set<String>` in `@State` and derives `allReviewed` from it.
 * Here the *authority* is [TermsGateState.canAccept], because that is what the
 * contract hoists. This composable still tracks which documents have been read —
 * it has to, to draw the per-tab checkmark — but it does not gate the button on
 * its own copy. It reports the transition through [onAllDocumentsReviewed] and
 * the host flips `canAccept`. A screen that trusted its local set instead would
 * be a second, invisible gate that no test of the state type could reach.
 *
 * ## Why the PDF pages arrive as a parameter
 *
 * iOS rasterises the bundled PDF inside `PDFDocumentScroll` with PDFKit. Android
 * has `PdfRenderer`, but it needs a `Context`, a `ParcelFileDescriptor` and a
 * file on disk — I/O, which an auth screen does not do. [pageSource] is that
 * seam, the same shape as the injected avatar renderer in DIFFERENCES.md D8.
 *
 * **The default returns no pages**, which lands on the same `.failed` branch iOS
 * takes when the bundle resource is missing. It is a test seam and a fallback,
 * not the shipping path: the sign-up flow passes [PdfPageLoader], which reads the
 * two PDFs out of `assets/legal/`. A caller that forgets to pass one gets a gate
 * that shows its error state and can never be satisfied, so nothing that reaches
 * a caregiver may rely on the default.
 *
 * @param onCancel the sheet's Cancel action. iOS dismisses without agreeing.
 * @param onAllDocumentsReviewed fires once, when the last unread document
 *   reaches its end. The host answers by setting `canAccept = true`.
 * @param onAgree iOS runs `onAgree()` **and then** dismisses the sheet; the host
 *   owns dismissal here, so it must do both.
 * @param pageSource rendered pages of a document, by resource name, top to
 *   bottom. An empty list means the document could not be loaded.
 */
@Composable
fun TermsGateScreen(
    state: TermsGateState,
    onCancel: () -> Unit,
    onAllDocumentsReviewed: () -> Unit,
    onAgree: () -> Unit,
    modifier: Modifier = Modifier,
    pageSource: suspend (resource: String) -> List<ImageBitmap> = { emptyList() },
) {
    var selected by remember { mutableStateOf(LegalDocuments.first().id) }
    val reached = remember { mutableStateListOf<String>() }
    var announced by remember { mutableStateOf(false) }

    // Called from the scroll listener, never from composition.
    fun markReached(id: String) {
        if (id !in reached) reached.add(id)
        if (!announced && reached.size >= LegalDocuments.size) {
            announced = true
            onAllDocumentsReviewed()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Paper),
    ) {

        // ---- Inline navigation bar: centered title, Cancel leading ----
        // iOS: NavigationStack + .navigationTitle("Terms & Privacy") +
        // .navigationBarTitleDisplayMode(.inline) + a .cancellationAction item.
        // Compose has no navigation bar, so it is drawn the same way
        // CountryPickerSheet draws its own (DIFFERENCES.md D9).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Terms & Privacy",
                // No sans token is 17, so the inline nav title (SF Pro 17
                // semibold) takes the nearest: Inter 16 semibold. Same choice as
                // CountryPicker.
                style = NemaraType.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Ink,
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
                color = Ink,
            )
        }

        DocumentPicker(
            documents = LegalDocuments,
            selected = selected,
            reviewed = reached,
            onSelect = { selected = it },
            // iOS: .padding([.horizontal, .top], 16).
            modifier = Modifier.padding(horizontal = Spacing.md).padding(top = Spacing.md),
        )

        // Keep every document mounted (so each remembers its scroll + reviewed
        // state); show only the selected one.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            for (doc in LegalDocuments) {
                val isSelected = doc.id == selected
                PdfDocumentScroll(
                    resource = doc.resource,
                    pageSource = pageSource,
                    onReachedEnd = { markReached(doc.id) },
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (isSelected) 1f else 0f)
                        // iOS uses .allowsHitTesting(selected == doc.id). Compose
                        // has no such modifier; raising the selected document
                        // above the others is equivalent here, because every
                        // document fills the box and the topmost scrollable
                        // consumes the gesture.
                        .zIndex(if (isSelected) 1f else 0f),
                )
            }
        }

        Column(
            modifier = Modifier.padding(Spacing.md),
            // iOS: VStack(spacing: 10). 10 is not on the spacing scale.
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Not in the Swift: TermsConsentView has no error surface, because
            // agreeing is a local state flip there. AuthFormState carries an
            // errorMessage for every auth screen and the contract says it renders
            // verbatim, so it renders here rather than being silently dropped.
            state.form.errorMessage?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.fillMaxWidth(),
                    style = NemaraType.bodySmall,
                    color = Terra,
                    textAlign = TextAlign.Center,
                )
            }

            if (!state.canAccept) {
                Text(
                    text = "Scroll to the end of each document to continue.",
                    modifier = Modifier.fillMaxWidth(),
                    style = NemaraType.caption,
                    color = Ink2,
                    textAlign = TextAlign.Center,
                )
            }

            NemaraButton(
                title = "I agree",
                // iOS applies .opacity(allReviewed ? 1 : 0.5) at this call site.
                // NemaraButton itself never dims — that is deliberate and matches
                // NemaraButton.swift — so the dimming is applied here, as it is
                // there.
                modifier = Modifier.alpha(if (state.canAccept) 1f else 0.5f),
                variant = NemaraButtonVariant.Primary,
                size = NemaraButtonSize.Lg,
                block = true,
                // isBusy is not in the Swift (nothing here is async). It is in
                // AuthFormState, and leaving it out would let a double tap fire
                // two agreements while the host is mid-signup.
                enabled = state.canAccept && !state.form.isBusy,
                onClick = onAgree,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The document picker
// ---------------------------------------------------------------------------

/**
 * iOS: `Picker(...).pickerStyle(.segmented)`.
 *
 * Hand-built for the reason CountryPicker's chrome is (DIFFERENCES.md D9): the
 * segmented control is system chrome on iOS, and material3's `SegmentedButton`
 * brings its own shape, border and color roles. The geometry below — a 2dp track
 * inset, a `Radius.sm` thumb inside a `Radius.md` track — is **judgement, not a
 * ported value**, so it wants the screenshot pass.
 *
 * The reviewed marker is a literal "✓" appended to the title with two spaces, as
 * in the Swift. It is text, not an SF Symbol, so it ports as text.
 */
@Composable
private fun DocumentPicker(
    documents: List<LegalConstants.Doc>,
    selected: String,
    reviewed: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(PaperDim)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (doc in documents) {
            val isSelected = doc.id == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(if (isSelected) Card else Color.Transparent)
                    // Selectable rather than clickable, so the announcement says
                    // WHICH document is open. See AddCameraScreen's picker.
                    .plainSelectable(selected = isSelected, role = Role.Tab) { onSelect(doc.id) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (doc.id in reviewed) "${doc.title}  ✓" else doc.title,
                    // UISegmentedControl draws the selected segment's label
                    // semibold and the rest regular, both at 13.
                    style = if (isSelected) NemaraType.bodySmallStrong else NemaraType.bodySmall,
                    color = if (isSelected) Ink else Ink3,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The document itself
// ---------------------------------------------------------------------------

/** iOS: `PDFDocumentScroll.LoadState`. */
private enum class DocumentLoadState { Loading, Ready, Failed }

/**
 * Key of the zero-height sentinel below the last page. Its arrival in the
 * viewport is the scroll-to-bottom signal, exactly as the iOS sentinel's
 * `.onAppear` is.
 */
private const val EndSentinelKey = "end-sentinel"

/**
 * A read-only, scrollable view of a legal PDF that reports when the reader has
 * scrolled past the last page. Spec: `Features/Legal/PDFDocumentScroll.swift`.
 *
 * Pages are images, rendered by [pageSource] and shown in a plain `LazyColumn`
 * with a 1dp sentinel below the last one. Detection watches for the sentinel
 * entering the viewport rather than for the list simply being composed, because
 * a lazy list composes a prefetch buffer past the viewport and that would unlock
 * "I agree" a screen early.
 *
 * [onReachedEnd] can fire more than once over the life of the composition only
 * if the sentinel leaves and re-enters; it does not, because `first { it }` ends
 * the collection. The caller is idempotent regardless.
 */
@Composable
private fun PdfDocumentScroll(
    resource: String,
    pageSource: suspend (String) -> List<ImageBitmap>,
    onReachedEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pages by remember(resource) { mutableStateOf(emptyList<ImageBitmap>()) }
    var loadState by remember(resource) { mutableStateOf(DocumentLoadState.Loading) }
    val listState = rememberLazyListState()

    LaunchedEffect(resource) {
        val rendered = pageSource(resource)
        pages = rendered
        loadState = if (rendered.isEmpty()) DocumentLoadState.Failed else DocumentLoadState.Ready
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == EndSentinelKey } }
            .first { it }
        onReachedEnd()
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        // iOS puts .padding(12) on the LazyVStack, i.e. inside the scroll
        // content, and only in the ready case.
        contentPadding = if (loadState == DocumentLoadState.Ready) {
            PaddingValues(Spacing.sm)
        } else {
            PaddingValues(0.dp)
        },
        // iOS: LazyVStack(spacing: 10).
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (loadState) {
            DocumentLoadState.Loading -> item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            DocumentLoadState.Failed -> item {
                Text(
                    text = "Couldn't load this document. Pull down to retry.",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                    style = NemaraType.bodySmall,
                    color = Terra,
                    textAlign = TextAlign.Center,
                )
            }

            DocumentLoadState.Ready -> {
                items(pages) { page ->
                    Image(
                        bitmap = page,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().border(1.dp, Line),
                        contentScale = ContentScale.FillWidth,
                    )
                }
                item(key = EndSentinelKey) {
                    Box(Modifier.fillMaxWidth().height(1.dp))
                }
            }
        }
    }
}
