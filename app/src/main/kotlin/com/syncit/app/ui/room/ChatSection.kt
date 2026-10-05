package com.syncit.app.ui.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syncit.app.overlay.ChatBubbleService
import com.syncit.app.permissions.Permissions
import com.syncit.core.model.ChatMessage
import com.syncit.core.model.Reaction

val REACTION_EMOJIS = listOf("👍", "😂", "😮", "😢", "❤️", "🔥")

@Composable
fun ChatMessageRow(message: ChatMessage, isMine: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!isMine) Text(message.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(message.text)
            }
        }
    }
}

@Composable
fun ChatInput(onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(500) },
            placeholder = { Text("Say something") },
            modifier = Modifier.weight(1f),
            maxLines = 3,
        )
        Button(
            onClick = {
                onSend(text)
                text = ""
            },
            enabled = text.isNotBlank(),
        ) { Text("Send") }
    }
}

@Composable
fun ReactionsBar(reactions: List<Reaction>, onReact: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (emoji in REACTION_EMOJIS) {
                TextButton(onClick = { onReact(emoji) }) { Text(emoji, fontSize = 22.sp) }
            }
        }
        if (reactions.isNotEmpty()) {
            Text(
                reactions.joinToString("   ") { "${it.name} ${it.emoji}" },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/** Starts/stops the floating chat bubble shown over Netflix/JioHotstar. */
@Composable
fun ChatBubbleToggle(roomCode: String) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            if (Permissions.canDrawOverlays(context)) {
                ChatBubbleService.start(context, roomCode)
            } else {
                // SYSTEM_ALERT_WINDOW is a special-access setting; the user must flip it.
                Permissions.openOverlaySettings(context)
            }
        }) { Text("Chat bubble over the movie") }
        TextButton(onClick = { ChatBubbleService.stop(context) }) { Text("Hide bubble") }
    }
    if (!Permissions.canDrawOverlays(context)) {
        Text(
            "Optional: allow \"Display over other apps\" for Sync it to chat without leaving the movie.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
