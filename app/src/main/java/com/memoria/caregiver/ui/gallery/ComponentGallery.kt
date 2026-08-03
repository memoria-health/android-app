package com.memoria.caregiver.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoria.caregiver.ui.components.NemaraAvatar
import com.memoria.caregiver.ui.components.NemaraButton
import com.memoria.caregiver.ui.components.NemaraButtonSize
import com.memoria.caregiver.ui.components.NemaraButtonVariant
import com.memoria.caregiver.ui.components.NemaraCamFeedPlaceholder
import com.memoria.caregiver.ui.components.NemaraCard
import com.memoria.caregiver.ui.components.NemaraCardTone
import com.memoria.caregiver.ui.components.NemaraChip
import com.memoria.caregiver.ui.components.NemaraChipTone
import com.memoria.caregiver.ui.components.NemaraEyebrow
import com.memoria.caregiver.ui.components.NemaraField
import com.memoria.caregiver.ui.components.NemaraMark
import com.memoria.caregiver.ui.components.NemaraPulse
import com.memoria.caregiver.ui.components.NemaraSolidPill
import com.memoria.caregiver.ui.components.NemaraToggle
import com.memoria.caregiver.ui.components.NemaraWaveform
import com.memoria.caregiver.ui.theme.Action
import com.memoria.caregiver.ui.theme.ActionInk
import com.memoria.caregiver.ui.theme.ActionSoft
import com.memoria.caregiver.ui.theme.Card
import com.memoria.caregiver.ui.theme.CardAlt
import com.memoria.caregiver.ui.theme.Danger
import com.memoria.caregiver.ui.theme.Info
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Ink4
import com.memoria.caregiver.ui.theme.Line
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.PaperDim
import com.memoria.caregiver.ui.theme.Radius
import com.memoria.caregiver.ui.theme.Sky
import com.memoria.caregiver.ui.theme.SkySoft
import com.memoria.caregiver.ui.theme.Spacing
import com.memoria.caregiver.ui.theme.Success
import com.memoria.caregiver.ui.theme.Terra
import com.memoria.caregiver.ui.theme.TerraDeep
import com.memoria.caregiver.ui.theme.TerraInk
import com.memoria.caregiver.ui.theme.TerraSoft
import com.memoria.caregiver.ui.theme.Warning

/**
 * Every design-system token and component on one scrollable screen.
 *
 * This is the Phase 1 verification surface: `HARNESS.md §4` makes a screenshot a
 * first-class gate, because a component with green unit tests and a wrong
 * screenshot is not done. Tests pin the numbers; only looking at it catches font
 * rendering, accumulated spacing and baseline alignment.
 *
 * Debug-only in practice — `MainActivity` shows it until Phase 2 replaces the
 * body with the navigation graph.
 */
@Composable
fun ComponentGallery(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().background(Paper),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        item { GallerySection("Colors") { ColorSwatches() } }
        item { GallerySection("Typography") { TypeSpecimens() } }
        item { GallerySection("Geometry") { GeometryRulers() } }
        item { GallerySection("Buttons") { Buttons() } }
        item { GallerySection("Cards") { Cards() } }
        item { GallerySection("Chips") { Chips() } }
        item { GallerySection("Form") { FormBits() } }
        item { GallerySection("Display") { DisplayBits() } }
    }
}

@Composable
private fun GallerySection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        NemaraEyebrow(title, color = Terra)
        content()
    }
}

// ---------------------------------------------------------------------------

private val PALETTE = listOf(
    "Paper" to Paper, "PaperDim" to PaperDim, "Card" to Card, "CardAlt" to CardAlt,
    "Ink" to Ink, "Ink2" to Ink2, "Ink3" to Ink3, "Ink4" to Ink4, "Line" to Line,
    "Terra" to Terra, "TerraSoft" to TerraSoft, "TerraDeep" to TerraDeep, "TerraInk" to TerraInk,
    "Action" to Action, "ActionSoft" to ActionSoft, "ActionInk" to ActionInk,
    "Sky" to Sky, "SkySoft" to SkySoft,
    "Success" to Success, "Warning" to Warning, "Danger" to Danger, "Info" to Info,
)

@Composable
private fun ColorSwatches() {
    PALETTE.chunked(4).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            row.forEach { (name, color) ->
                Column(
                    Modifier.width(78.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(color),
                    )
                    Text(name, style = NemaraType.monoSmall, color = Ink3, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun TypeSpecimens() {
    val styles = listOf(
        "display 44" to NemaraType.display,
        "largeTitle 32" to NemaraType.largeTitle,
        "title1 28" to NemaraType.title1,
        "title2 22" to NemaraType.title2,
        "title3 18" to NemaraType.title3,
        "eventTitle 17/600" to NemaraType.eventTitle,
        "bodyLarge 16" to NemaraType.bodyLarge,
        "body 14" to NemaraType.body,
        "bodyMedium 14/500" to NemaraType.bodyMedium,
        "bodySemibold 14/600" to NemaraType.bodySemibold,
        "bodySmall 13" to NemaraType.bodySmall,
        "bodySmallStrong 13/600" to NemaraType.bodySmallStrong,
        "labelStrong 12/600" to NemaraType.labelStrong,
        "label 12/500" to NemaraType.label,
        "caption 11" to NemaraType.caption,
        "captionStrong 11/600" to NemaraType.captionStrong,
        "eyebrow mono 11/600" to NemaraType.eyebrow,
        "monoSmall 10/600" to NemaraType.monoSmall,
        "monoMicro 9/700" to NemaraType.monoMicro,
        "monoValue 12/600" to NemaraType.monoValue,
    )
    styles.forEach { (label, style) ->
        Column {
            Text("Margaret is home", style = style, color = Ink)
            Text(label, style = NemaraType.monoSmall, color = Ink4)
        }
    }
}

@Composable
private fun GeometryRulers() {
    val spacing = listOf(
        "xxs 4" to Spacing.xxs, "xs 8" to Spacing.xs, "sm 12" to Spacing.sm,
        "md 16" to Spacing.md, "lg 20" to Spacing.lg, "xl 24" to Spacing.xl,
        "xxl 32" to Spacing.xxl, "xxxl 40" to Spacing.xxxl, "huge 56" to Spacing.huge,
    )
    spacing.forEach { (label, dp) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(dp).height(10.dp).background(Terra))
            Text("  $label", style = NemaraType.monoSmall, color = Ink3)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        listOf("sm 6" to Radius.sm, "md 10" to Radius.md, "lg 14" to Radius.lg, "xl 20" to Radius.xl)
            .forEach { (label, r) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(r)).background(ActionSoft),
                    )
                    Text(label, style = NemaraType.monoSmall, color = Ink3)
                }
            }
    }
}

@Composable
private fun Buttons() {
    NemaraButtonVariant.entries.forEach { variant ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NemaraButtonSize.entries.forEach { size ->
                NemaraButton(
                    title = variant.name,
                    variant = variant,
                    size = size,
                    // The title here is a variant name rather than copy, so it
                    // gets an explicit label: `logButton` falls back to the
                    // title, and every analytics label in this app is a fixed
                    // string written by hand. AnalyticsLabelsAreFixedStringsTest
                    // enforces that.
                    track = "gallery.variant_button",
                    onClick = {},
                )
            }
        }
    }
    NemaraButton(title = "Create an account", size = NemaraButtonSize.Lg, block = true, onClick = {})
    NemaraButton(title = "Disabled", enabled = false, onClick = {})
}

@Composable
private fun Cards() {
    NemaraCardTone.entries.forEach { tone ->
        NemaraCard(modifier = Modifier.fillMaxWidth(), tone = tone) {
            Text(tone.name, style = NemaraType.bodyMedium, color = Ink)
        }
    }
}

@Composable
private fun Chips() {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        NemaraChip("all online", tone = NemaraChipTone.Action, dotColor = Action)
        NemaraChip("partial", tone = NemaraChipTone.Warning)
        NemaraChip("offline", tone = NemaraChipTone.Terra, dotColor = Terra)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        NemaraChip("last 7d")
        NemaraChip("routine", tone = NemaraChipTone.Sky)
        NemaraChip("dark", tone = NemaraChipTone.Dark)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        NemaraSolidPill("safety", Terra)
        NemaraSolidPill("routine", Sky)
        NemaraSolidPill("custom", TerraDeep)
    }
}

@Composable
private fun FormBits() {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("Mar9aret!") }
    var on by remember { mutableStateOf(true) }
    var off by remember { mutableStateOf(false) }

    NemaraField(
        label = "Email",
        value = email,
        onValueChange = { email = it },
        placeholder = "you@home.com",
        hint = "Empty: the border is Line. Typed: it darkens to Ink.",
    )
    NemaraField(label = "Password", value = password, onValueChange = { password = it }, isSecure = true)
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        NemaraToggle(isOn = on, onToggle = { on = it }, track = "gallery.on")
        NemaraToggle(isOn = off, onToggle = { off = it }, track = "gallery.off")
    }
}

@Composable
private fun DisplayBits() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NemaraMark(size = 44.dp)
        NemaraAvatar(letter = "M", size = 48.dp)
        NemaraAvatar(letter = "s", size = 48.dp, ring = Terra)
        NemaraAvatar(letter = "Á", size = 48.dp)
        NemaraPulse()
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(Radius.lg)),
    ) { NemaraCamFeedPlaceholder(Modifier.fillMaxWidth().height(120.dp)) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(Radius.lg)),
    ) { NemaraCamFeedPlaceholder(Modifier.fillMaxWidth().height(120.dp), lit = false, loading = true) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(Radius.md))
            .background(Action)
            .padding(Spacing.xs),
    ) {
        NemaraWaveform(
            levels = List(40) { i -> (kotlin.math.sin(i * 0.5) * 0.4 + 0.5).toFloat() },
            modifier = Modifier.fillMaxWidth().height(32.dp),
            tint = ActionInk,
        )
    }
}
