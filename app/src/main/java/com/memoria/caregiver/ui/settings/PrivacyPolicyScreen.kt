package com.memoria.caregiver.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.Spacing
import com.memoria.caregiver.ui.theme.Terra
import androidx.compose.material3.Text

/**
 * The bundled privacy notice. Port of
 * `Features/AppSettings/PrivacyPolicyView.swift`.
 *
 * Reached from Settings → "Privacy & data", which until now had nothing behind
 * it: `onPrivacy` was wired all the way from `MainShell` to a `NotBuiltScreen`
 * (FINDINGS.md F-14).
 *
 * The text lives in [PrivacyPolicyContent] and is carried verbatim from the
 * Swift — see that file for why it must not be edited here, including the
 * clauses that are wrong on Android.
 *
 * Presented as a full-screen overlay rather than iOS's `NavigationStack` sheet,
 * which is how every other modal in this app is presented (DIFFERENCES.md D9).
 */
@Composable
fun PrivacyPolicyScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(Paper)) {
        NavigationBar(onDone = onDone)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Header()

            PrivacyPolicyContent.sections.forEach { section -> Section(section) }

            Footer()
        }
    }
}

@Composable
private fun NavigationBar(onDone: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Privacy",
            // No sans token is 17, so the inline nav title takes the nearest:
            // Inter 16 semibold. Same choice as TermsGate and the door picker.
            style = NemaraType.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = Ink,
            maxLines = 1,
        )
        Text(
            text = "Done",
            style = NemaraType.bodyLarge,
            color = Ink2,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .clickable(onClick = onDone)
                .padding(vertical = Spacing.xs),
        )
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = PrivacyPolicyContent.TITLE,
            style = NemaraType.largeTitle,
            color = Ink,
            // iOS: .kerning(-1) on the large title.
            letterSpacing = (-1).sp,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = PrivacyPolicyContent.EFFECTIVE,
            style = NemaraType.captionStrong,
            color = Ink3,
            // iOS: .tracking(0.6).
            letterSpacing = 0.6.sp,
        )
    }
}

@Composable
private fun Section(section: PrivacySection) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = section.title,
            style = NemaraType.title2,
            color = Ink,
            letterSpacing = (-0.3).sp,
            // Marked as a heading so TalkBack's heading navigation can jump
            // between the eighteen sections. A 4,000-word notice read strictly
            // top to bottom is not reachable by anyone using a screen reader,
            // and this screen exists to be read.
            modifier = Modifier.semantics { heading() },
        )
        section.body.forEach { block -> Content(block) }
    }
}

@Composable
private fun Content(block: PrivacyContent) {
    when (block) {
        is PrivacyContent.Paragraph -> Text(
            text = annotated(block.text),
            style = NemaraType.bodyLarge,
            color = Ink2,
            // iOS: .lineSpacing(4) on top of the 16pt body.
            lineHeight = 24.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start,
        )

        is PrivacyContent.Subheading -> Text(
            text = block.title,
            style = NemaraType.bodySemibold,
            color = Ink,
            modifier = Modifier.padding(top = 4.dp).semantics { heading() },
        )

        is PrivacyContent.Bullets -> Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            block.items.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "•",
                        style = NemaraType.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = Terra,
                    )
                    Text(
                        text = annotated(item),
                        style = NemaraType.bodyLarge,
                        color = Ink2,
                        lineHeight = 24.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun Footer() {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text(PrivacyPolicyContent.FOOTER_LEAD, style = NemaraType.bodySmall, color = Ink3)
        Text(PrivacyPolicyContent.FOOTER_NAME, style = NemaraType.bodySemibold, color = Ink)
        Text(PrivacyPolicyContent.FOOTER_CONTACT, style = NemaraType.bodyMedium, color = Ink2)
    }
}

/**
 * `**bold**` to an [AnnotatedString]. iOS gets this from
 * `AttributedString(markdown:)`; the parse itself is [boldRuns], kept separate
 * so it is testable without a Compose runtime.
 */
@Composable
private fun annotated(source: String): AnnotatedString {
    val runs = remember(source) { boldRuns(source) }
    return remember(runs) {
        buildAnnotatedString {
            append(runs.text)
            runs.bold.forEach { range ->
                addStyle(
                    SpanStyle(fontWeight = FontWeight.SemiBold),
                    range.first,
                    range.last + 1,
                )
            }
        }
    }
}
