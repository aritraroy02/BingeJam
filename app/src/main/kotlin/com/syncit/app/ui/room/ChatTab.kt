package com.syncit.app.ui.room

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.GlassTextField
import com.syncit.app.ui.glass.PillShape
import com.syncit.app.ui.glass.glass
import com.syncit.app.ui.theme.Glass
import com.syncit.core.model.ChatMessage
import com.syncit.core.model.Reaction

val REACTION_EMOJIS = listOf("👍", "😂", "😮", "😢", "❤️", "🔥")

@Composable
fun ChatTab(
    chat: List<ChatMessage>,
    myUid: String?,
    reactions: List<Reaction>,
    onSend: (String) -> Unit,
    onReact: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Glass.palette
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val listState = rememberLazyListState()
            LaunchedEffect(chat.size) { if (chat.isNotEmpty()) listState.animateScrollToItem(chat.lastIndex) }
            if (chat.isEmpty()) {
                Text(
                    "Say hi 👋\nMessages show up for everyone in the room.",
                    color = p.textSecondary,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(chat, key = { it.id }) { msg -> MessageBubble(msg.text, senderName = msg.name, isMine = msg.uid == myUid) }
            }
            FloatingReactions(reactions, Modifier.align(Alignment.BottomEnd).padding(bottom = 8.dp))
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            for (emoji in REACTION_EMOJIS) {
                Box(
                    Modifier.size(46.dp).glass(CircleShape).clickable { onReact(emoji) },
                    contentAlignment = Alignment.Center,
                ) { Text(emoji, fontSize = 22.sp) }
            }
        }
        ChatInputBar(onSend, "Message the room", Modifier.padding(bottom = 8.dp))
    }
}

/** Speech bubble shared by room chat and direct messages. [senderName] null hides avatar and name (DMs). */
@Composable
internal fun MessageBubble(text: String, senderName: String?, isMine: Boolean) {
    val p = Glass.palette
    // The corner nearest the sender is tighter, like a speech bubble's tail.
    val shape = if (isMine) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!isMine && senderName != null) {
            Avatar(senderName, size = 28.dp)
            Spacer(Modifier.width(8.dp))
        }
        val bubble = if (isMine) {
            Modifier.clip(shape).background(Brush.linearGradient(listOf(p.accent, p.accent2)))
        } else {
            Modifier.glass(shape)
        }
        Column(bubble.widthIn(max = 280.dp).padding(horizontal = 14.dp, vertical = 9.dp)) {
            if (!isMine && senderName != null) Text(senderName, style = MaterialTheme.typography.labelMedium, color = p.accent2)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (isMine) p.onAccent else p.textPrimary)
        }
    }
}

@Composable
internal fun ChatInputBar(onSend: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val p = Glass.palette
    var text by rememberSaveable { mutableStateOf("") }
    val send = {
        if (text.isNotBlank()) {
            onSend(text)
            text = ""
        }
    }
    GlassTextField(
        value = text,
        onValueChange = { text = it.take(500) },
        placeholder = placeholder,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send() }),
        modifier = modifier.fillMaxWidth(),
        trailing = {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        if (text.isBlank()) Brush.linearGradient(listOf(p.textTertiary, p.textTertiary))
                        else Brush.linearGradient(listOf(p.accent, p.accent2)),
                    )
                    .clickable(enabled = text.isNotBlank()) { send() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = p.onAccent, modifier = Modifier.size(20.dp)) }
        },
    )
}

/** Reactions from the last few seconds, floating up the right edge. */
@Composable
private fun FloatingReactions(reactions: List<Reaction>, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (reaction in reactions) {
            // Starts hidden so each new reaction animates in.
            val appear = remember(reaction.id) { MutableTransitionState(false).apply { targetState = true } }
            AnimatedVisibility(visibleState = appear, enter = fadeIn() + slideInVertically { it }, exit = fadeOut()) {
                Row(
                    Modifier.glass(PillShape).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(reaction.emoji, fontSize = 20.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(reaction.name, style = MaterialTheme.typography.labelMedium, color = Glass.palette.textPrimary)
                }
            }
        }
    }
}
