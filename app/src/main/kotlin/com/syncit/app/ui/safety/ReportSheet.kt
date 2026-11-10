package com.syncit.app.ui.safety

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.syncit.app.ui.glass.GlassBottomSheet
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassButtonStyle
import com.syncit.app.ui.glass.GlassSwitchRow
import com.syncit.app.ui.glass.GlassTextField
import com.syncit.app.ui.theme.Glass
import com.syncit.core.social.ReportReason

/**
 * Report a person or a group (2026-11-10): a reason, optional details, and "also block", like
 * WhatsApp. [evidenceNote] says what's sent with the report, so nothing leaves the phone unannounced.
 */
@Composable
fun ReportSheet(
    visible: Boolean,
    title: String,
    evidenceNote: String,
    blockLabel: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (reason: ReportReason, details: String?, block: Boolean) -> Unit,
) {
    val p = Glass.palette
    var reason by remember(visible) { mutableStateOf<ReportReason?>(null) }
    var details by remember(visible) { mutableStateOf("") }
    var block by remember(visible) { mutableStateOf(true) }
    GlassBottomSheet(visible = visible, onDismiss = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = p.textPrimary)
        Text("Why are you reporting this?", style = MaterialTheme.typography.bodyMedium, color = p.textSecondary)
        Column(Modifier.selectableGroup()) {
            for (r in ReportReason.entries) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .selectable(selected = reason == r, role = Role.RadioButton, onClick = { reason = r })
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = reason == r,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = p.accentText, unselectedColor = p.textTertiary),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(r.label, style = MaterialTheme.typography.bodyLarge, color = p.textPrimary)
                }
            }
        }
        GlassTextField(
            value = details,
            onValueChange = { details = it.take(MAX_DETAILS) },
            placeholder = "Add details (optional)",
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            maxLines = 4,
        )
        GlassSwitchRow(title = blockLabel, checked = block, onCheckedChange = { block = it })
        Text(evidenceNote, style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Cancel", onClick = onDismiss, style = GlassButtonStyle.Secondary, modifier = Modifier.weight(1f))
            GlassButton(
                "Report",
                onClick = { reason?.let { onSubmit(it, details.trim().ifEmpty { null }, block) } },
                style = GlassButtonStyle.Danger,
                enabled = reason != null && !busy,
                loading = busy,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** "Block X?" with what blocking does; [message] differs for a person and a group. */
@Composable
fun BlockDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val p = Glass.palette
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = p.danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MAX_DETAILS = 500
