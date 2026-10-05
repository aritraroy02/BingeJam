package com.syncit.app.ui.room

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.overlay.ChatBubbleService
import com.syncit.core.config.RoomLinks
import com.syncit.core.model.SyncMode

@Composable
fun RoomScreen(viewModel: RoomViewModel, onLeft: () -> Unit) {
    val context = LocalContext.current
    val room by viewModel.room.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()
    val myUid by viewModel.myUid.collectAsStateWithLifecycle()
    val serverNow by viewModel.serverNow.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val reactions by viewModel.reactions.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var showDrift by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.enter() }

    message?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
            text = { Text(it) },
        )
    }

    val currentRoom = room
    val isHost = currentRoom != null && currentRoom.hostUid == myUid

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.padding(top = 8.dp)) {
                    Text("Room", style = MaterialTheme.typography.labelLarge)
                    Text(
                        viewModel.code,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, RoomLinks.shareText(viewModel.code))
                            context.startActivity(Intent.createChooser(send, "Invite to Sync it"))
                        }) { Text("Share invite") }
                        TextButton(onClick = {
                            ChatBubbleService.stop(context)
                            viewModel.leave(onLeft)
                        }) { Text("Leave room") }
                    }
                }
            }

            if (currentRoom == null) {
                item { Text("Connecting to room…") }
                return@LazyColumn
            }

            item { SyncStatusCard(sync, onResume = viewModel::enter) }

            item {
                CountdownCard(
                    countdown = currentRoom.countdown,
                    serverNow = serverNow,
                    prominent = sync.mode == SyncMode.MANUAL_COUNTDOWN,
                    onStart = viewModel::startCountdown,
                )
            }

            item {
                TitleCard(
                    title = currentRoom.title,
                    isHost = isHost,
                    onSave = viewModel::setTitle,
                )
            }

            item {
                MembersCard(
                    room = currentRoom,
                    myUid = myUid,
                    serverNow = serverNow,
                    showDrift = showDrift,
                    onToggleDrift = { showDrift = it },
                    myEngineDriftMs = sync.driftMs,
                )
            }

            item { ChatBubbleToggle(roomCode = viewModel.code) }

            item { ReactionsBar(reactions = reactions, onReact = viewModel::sendReaction) }

            item { Text("Chat", style = MaterialTheme.typography.titleMedium) }
            items(chat, key = { it.id }) { msg -> ChatMessageRow(msg, isMine = msg.uid == myUid) }
            item { ChatInput(onSend = viewModel::sendChat) }
            item { Row(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {} }
        }
    }
}
