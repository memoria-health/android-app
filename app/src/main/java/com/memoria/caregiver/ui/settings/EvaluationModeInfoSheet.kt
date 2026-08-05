package com.memoria.caregiver.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.ui.theme.Ink
import com.memoria.caregiver.ui.theme.Ink2
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.NemaraType
import com.memoria.caregiver.ui.theme.Paper
import com.memoria.caregiver.ui.theme.PaperDim

/**
 * The explainer behind the ⓘ on [EvaluationModeCard]. Spec:
 * `Features/Patient/Settings/EvaluationModeInfoSheet.swift`.
 *
 * Not decoration. Evaluation mode is ON by default, so for most households
 * this sheet is the first and only place they learn that a person at Memoria
 * sees masked footage of their home. Treat the copy as a disclosure: it says
 * plainly who sees what, that sound is included, that a real alert is never
 * delayed, and how to switch it off.
 *
 * The wording is kept identical to the iOS sheet and the web modal. If you
 * edit one, edit all three — a household that reads a different promise on
 * the web than on the phone has been told two different things.
 *
 * The mask image is a real frame from the real pipeline, staged deliberately
 * rather than taken from a customer's home. It is resolved by name at
 * runtime so a build without the asset yet shows an explicit placeholder
 * instead of failing to compile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvaluationModeInfoSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val maskResId = remember(context) {
        context.resources.getIdentifier(
            "evaluation_mode_mask", "drawable", context.packageName,
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Paper,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Image FIRST. It is the whole argument: a caregiver decides
            // whether this is acceptable by looking, not by reading.
            if (maskResId != 0) {
                Image(
                    painter = painterResource(id = maskResId),
                    contentDescription =
                        "A camera view of a room with the person covered by " +
                            "a solid green shape",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(PaperDim),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Example image not bundled",
                        style = NemaraType.caption, color = Ink3)
                }
            }

            Text("Evaluation mode", style = NemaraType.title2, color = Ink)

            Body("Video data is not always perfect, and sometimes our AI " +
                "gets things wrong.")
            Body("Evaluation mode adds a second pair of eyes. Before an " +
                "alert reaches you, a trained member of our team takes a " +
                "quick look and stops the false alarms, so you only get " +
                "notified about the things that matter.")
            Body("The picture above is all they see. Faces, bodies and " +
                "clothing are covered by a solid shape, so they can tell " +
                "what happened without seeing who it is. They can hear the " +
                "clip as well, because a call for help is often the " +
                "clearest sign that something is wrong.")
            Body("You can turn this off at any time.")
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = NemaraType.body, color = Ink2)
}
