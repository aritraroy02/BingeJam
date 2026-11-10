package com.syncit.app.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.syncit.app.social.UsernameAvailability.Status
import com.syncit.app.ui.glass.GlassChip
import com.syncit.app.ui.glass.GlassTextField
import com.syncit.app.ui.theme.Glass

/** "@username" field with a live availability line and optional suggestion chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UsernameField(
    value: String,
    status: Status,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    suggestions: List<String> = emptyList(),
) {
    val p = Glass.palette
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = "username",
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
            trailing = {
                when (status) {
                    Status.Checking -> CircularProgressIndicator(Modifier.padding(end = 16.dp).size(18.dp), color = p.accent, strokeWidth = 2.dp)
                    is Status.Available, Status.Unchanged ->
                        Icon(Icons.Filled.Check, "Available", tint = p.success, modifier = Modifier.padding(end = 16.dp))
                    else -> Unit
                }
            },
        )
        val (text, color) = when (status) {
            Status.Empty -> "Letters, numbers, _ and . (3 to 20)" to p.textTertiary
            Status.Checking -> "Checking…" to p.textSecondary
            is Status.Available -> "@${status.username} is available" to p.success
            Status.Unchanged -> "That's your current username" to p.textSecondary
            Status.Taken -> "Taken. Try another." to p.danger
            is Status.Invalid -> status.reason to p.warning
            Status.Failed -> "Couldn't check right now. Check your connection." to p.warning
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(start = 8.dp))
        if (suggestions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in suggestions) GlassChip("@$s", onClick = { onValueChange(s) }, selected = s == value)
            }
        }
    }
}
