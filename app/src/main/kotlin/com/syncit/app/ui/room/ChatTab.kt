package com.syncit.app.ui.room

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syncit.app.ui.chat.ChatTimes
import com.syncit.app.ui.chat.DaySeparator
import com.syncit.app.ui.chat.copyToClipboard
import com.syncit.app.ui.chat.systemShowsCopies
import com.syncit.app.ui.chat.messageGap
import com.syncit.app.ui.chat.rememberChatRows
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.GlassTextField
import com.syncit.app.ui.glass.NoticeBanner
import com.syncit.app.ui.glass.PillShape
import com.syncit.app.ui.glass.glass
import com.syncit.app.ui.glass.popOn
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
    serverNowMs: () -> Long,
    modifier: Modifier = Modifier,
) {
    val p = Glass.palette
    val rows = rememberChatRows(chat, { it.id }, { it.uid }, { it.sentAt }, serverNowMs)
    val context = LocalContext.current
    val longPressHaptics = LocalHapticFeedback.current
    // Long-press copies a message (room chat has no forward/delete).
    var copied by remember { mutableStateOf<String?>(null) }
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val listState = rememberLazyListState()
            LaunchedEffect(chat.size) { if (rows.isNotEmpty()) listState.animateScrollToItem(rows.lastIndex) }
            if (chat.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("No messages yet", style = MaterialTheme.typography.titleMedium, color = p.textPrimary)
                    Text(
                        "Say hi, or tap a reaction below. Everyone in the room sees it, even over the movie with the chat bubble.",
                        color = p.textSecondary,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is ChatTimes.Row.Day -> DaySeparator(row.label)
                        is ChatTimes.Row.Message -> MessageBubble(
                            row.item.text,
                            senderName = row.item.name,
                            isMine = row.item.uid == myUid,
                            time = row.time,
                            grouped = row.grouped,
                            // New messages slide into place.
                            modifier = Modifier.padding(top = messageGap(row.grouped)).animateItem(),
                            onLongPress = {
                                longPressHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (copyToClipboard(context, row.item.text) && !systemShowsCopies()) copied = "Message copied"
                            },
                        )
                    }
                }
            }
            FloatingReactions(reactions, Modifier.align(Alignment.BottomEnd).padding(bottom = 8.dp))
            NoticeBanner(copied, { copied = null }, Modifier.align(Alignment.BottomCenter))
        }

        val haptics = LocalHapticFeedback.current
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            for (emoji in REACTION_EMOJIS) {
                // Each tap bumps the counter, which replays the pop.
                var taps by remember { mutableIntStateOf(0) }
                Box(
                    Modifier
                        .size(40.dp)
                        .popOn(taps.takeIf { it > 0 })
                        .glass(CircleShape)
                        .clickable(onClickLabel = "React with $emoji", role = Role.Button) {
                            taps++
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onReact(emoji)
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(emoji, fontSize = 20.sp) }
            }
        }
        ChatInputBar(onSend, "Message the room", Modifier.padding(bottom = 8.dp))
    }
}

/**
 * Speech bubble shared by room chat and direct messages. [senderName] null hides avatar and name
 * (DMs). [time] sits bottom-right after the text, WhatsApp-style. [grouped] continues the previous
 * message from the same sender: no repeated name or avatar. [forwarded] adds a small "Forwarded"
 * label above the text; [deleted] shows [text] (the placeholder) in italics. [onLongPress] opens
 * the message's actions (Copy, Forward, Delete); the caller gives the haptic feedback.
 */
@Composable
internal fun MessageBubble(
    text: String,
    senderName: String?,
    isMine: Boolean,
    time: String,
    modifier: Modifier = Modifier,
    grouped: Boolean = false,
    forwarded: Boolean = false,
    deleted: Boolean = false,
    onLongPress: (() -> Unit)? = null,
) {
    val p = Glass.palette
    // The corner nearest the sender is tighter, like a speech bubble's tail.
    val shape = if (isMine) RoundedCornerShape(16.dp, 16.dp, 8.dp, 16.dp) else RoundedCornerShape(16.dp, 16.dp, 16.dp, 8.dp)
    val timeStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp)
    val textColor = if (isMine) p.onAccent else p.textPrimary
    val timeColor = if (isMine) p.onAccent.copy(alpha = 0.72f) else p.textTertiary
    val showName = !isMine && senderName != null && !grouped
    // An invisible copy of the time at the end of the text reserves room for the real one, so it
    // shares the last line when there's space and wraps to its own line when there isn't.
    val body = remember(text, time, timeStyle) {
        buildAnnotatedString {
            append(text)
            withStyle(timeStyle.toSpanStyle().copy(color = Color.Transparent)) {
                append(" \u00A0\u00A0" + time.replace(' ', '\u00A0'))
            }
        }
    }
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!isMine && senderName != null) {
            if (grouped) {
                Spacer(Modifier.width(36.dp))
            } else {
                Avatar(senderName, size = 28.dp)
                Spacer(Modifier.width(8.dp))
            }
        }
        val bubble = if (isMine) {
            Modifier.clip(shape).background(p.accent)
        } else {
            Modifier.glass(shape)
        }
        val press = if (onLongPress != null) {
            Modifier.combinedClickable(
                interactionSource = null,
                indication = null,
                onLongClickLabel = "Message options",
                onLongClick = onLongPress,
                onClick = {},
            )
        } else {
            Modifier
        }
        Box(
            bubble
                .then(press)
                .widthIn(max = 280.dp)
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                // Read as one item ("Asha, see you at 9, 10:42 pm"), without the invisible copy.
                .clearAndSetSemantics {
                    contentDescription = listOfNotNull(
                        senderName.takeIf { showName },
                        "Forwarded".takeIf { forwarded },
                        text,
                        time,
                    ).joinToString(", ")
                },
        ) {
            Column {
                if (showName) Text(senderName.orEmpty(), style = MaterialTheme.typography.labelMedium, color = p.accentText)
                if (forwarded) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = timeColor, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Forwarded", style = timeStyle, color = timeColor)
                    }
                }
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (deleted) timeColor else textColor,
                )
            }
            Text(time, style = timeStyle, color = timeColor, maxLines = 1, modifier = Modifier.align(Alignment.BottomEnd))
        }
    }
}

@Composable
internal fun ChatInputBar(
    onSend: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onTextChange: (String) -> Unit = {},
) {
    val p = Glass.palette
    val haptics = LocalHapticFeedback.current
    var text by rememberSaveable { mutableStateOf("") }
    var sent by remember { mutableIntStateOf(0) }
    // Reported only when the text really changes (typing indicator), never on recomposition.
    val update = { value: String ->
        if (value != text) {
            text = value
            onTextChange(value)
        }
    }
    val send = {
        if (text.isNotBlank()) {
            onSend(text)
            update("")
            sent++
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }
    GlassTextField(
        value = text,
        onValueChange = { update(it.take(500)) },
        placeholder = placeholder,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send() }),
        modifier = modifier.fillMaxWidth(),
        trailing = {
            Box(
                Modifier
                    .size(44.dp)
                    .popOn(sent.takeIf { it > 0 })
                    .clip(CircleShape)
                    .background(
                        if (text.isBlank()) p.raised else p.accent,
                    )
                    .clickable(enabled = text.isNotBlank(), role = Role.Button) { send() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (text.isBlank()) p.textTertiary else p.onAccent, modifier = Modifier.size(20.dp)) }
        },
    )
}

/** Reactions from the last few seconds, floating up the right edge. */
@Composable
private fun FloatingReactions(reactions: List<Reaction>, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (reaction in reactions) {
            // Starts hidden so each new reaction animates in.
            val appear = remember(reaction.id) { MutableTransitionState(false).apply { targetState = true } }
            AnimatedVisibility(visibleState = appear, enter = fadeIn() + slideInVertically { it }, exit = fadeOut()) {
                Row(
                    Modifier.glass(PillShape).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(reaction.emoji, fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(reaction.name, style = MaterialTheme.typography.labelMedium, color = Glass.palette.textPrimary)
                }
            }
        }
    }
}
