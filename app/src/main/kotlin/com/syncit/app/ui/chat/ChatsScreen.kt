package com.syncit.app.ui.chat

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.syncit.app.di.AppContainer
import com.syncit.app.social.WatchTogether
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassTextField
import com.syncit.app.ui.glass.GlassTopBar
import com.syncit.app.ui.nav.LocalBottomBarInset
import com.syncit.app.ui.theme.Glass
import com.syncit.core.model.DirectMessage
import com.syncit.core.model.Friend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId

/** One conversation in the Chats list. */
data class ChatRow(val friend: Friend, val last: DirectMessage, val unread: Int) {
    val uid: String get() = friend.profile.uid
}

/** Chats tab: every conversation with a friend, newest first (WhatsApp-style). */
class ChatsViewModel(container: AppContainer) : ViewModel() {
    private val social = container.social
    val myUid: String? get() = social.auth.currentUid

    private val friends = social.friends.observeFriends()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun onQueryChange(value: String) {
        _query.value = value.take(MAX_QUERY)
    }

    /** Friends whose name or username contains the search (all friends, not only past chats). */
    val matches: StateFlow<List<Friend>> = combine(friends, _query) { list, q -> matchFriends(list.orEmpty(), q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Null while loading. */
    val rows: StateFlow<List<ChatRow>?> = combine(
        friends.filterNotNull(),
        social.dms.observeLatest().catch { emit(emptyMap()) },
        social.dms.observeUnreadCounts().catch { emit(emptyMap()) },
    ) { friends, latest, unread ->
        friends.mapNotNull { f -> latest[f.profile.uid]?.let { ChatRow(f, it, unread[f.profile.uid] ?: 0) } }
            .sortedByDescending { it.last.sentAt.takeIf { t -> t > 0 } ?: Long.MAX_VALUE }
    }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private companion object {
        const val MAX_QUERY = 40
    }
}

/** Case-insensitive match on display name or username ("@" ignored); names starting with it first. */
internal fun matchFriends(friends: List<Friend>, query: String): List<Friend> {
    val q = query.trim().removePrefix("@").lowercase()
    if (q.isEmpty()) return emptyList()
    return friends
        .filter { f -> f.profile.displayName.lowercase().contains(q) || f.profile.username.lowercase().contains(q) }
        .sortedWith(
            compareBy<Friend>(
                { f -> !(f.profile.displayName.lowercase().startsWith(q) || f.profile.username.lowercase().startsWith(q)) },
                { f -> f.profile.displayName.lowercase() },
            ),
        )
}

@Composable
fun ChatsScreen(viewModel: ChatsViewModel, onOpenChat: (String) -> Unit) {
    val p = Glass.palette
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val matches by viewModel.matches.collectAsStateWithLifecycle()
    val bottom = LocalBottomBarInset.current

    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GlassTopBar(title = "Chats")
            GlassTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                placeholder = "Search friends",
                leadingIcon = Icons.Filled.Search,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
                trailing = if (query.isNotEmpty()) {
                    { GlassIconButton(Icons.Filled.Close, "Clear search", { viewModel.onQueryChange("") }, plain = true) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp),
            )
            val list = rows
            when {
                query.isNotBlank() -> SearchResults(matches, bottom, onOpenChat)

                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = p.accent)
                }

                list.isEmpty() -> Box(
                    Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = bottom).padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No chats yet. Message a friend from Home and it shows up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.textSecondary,
                    )
                }

                else -> LazyColumn(
                    Modifier.fillMaxSize().navigationBarsPadding(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp + bottom),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(list, key = { it.uid }) { row ->
                        ChatRowView(row, viewModel.myUid, onClick = { onOpenChat(row.uid) })
                    }
                }
            }
        }
    }
}

/** Friends matching the search; tap to open (or start) the chat. */
@Composable
private fun SearchResults(matches: List<Friend>, bottom: Dp, onOpenChat: (String) -> Unit) {
    val p = Glass.palette
    if (matches.isEmpty()) {
        Text(
            "No friends match that",
            style = MaterialTheme.typography.bodyMedium,
            color = p.textSecondary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().imePadding().navigationBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp + bottom),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(matches, key = { it.profile.uid }) { friend ->
            GlassPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp), onClick = { onOpenChat(friend.profile.uid) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(
                        friend.profile.displayName,
                        size = 44.dp,
                        online = friend.presence?.online == true,
                        photoUrl = friend.profile.photoUrl,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            friend.profile.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = p.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("@${friend.profile.username}", style = MaterialTheme.typography.bodySmall, color = p.textSecondary, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatRowView(row: ChatRow, myUid: String?, onClick: () -> Unit) {
    val p = Glass.palette
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val sentAt = row.last.sentAt
    val day = ChatTimes.dayLabel(sentAt, now, zone)
    val time = if (day == "Today") ChatTimes.timeLabel(sentAt, now, zone, DateFormat.is24HourFormat(context)) else day
    val unread = row.unread > 0

    GlassPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(
                row.friend.profile.displayName,
                size = 48.dp,
                online = row.friend.presence?.online == true,
                photoUrl = row.friend.profile.photoUrl,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.friend.profile.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = p.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(time, style = MaterialTheme.typography.labelSmall, color = if (unread) p.accentText else p.textSecondary)
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val last = row.last
                    val watched = last.watched
                    Text(
                        when {
                            watched != null -> WatchTogether.headline(watched)
                            last.deleted -> if (last.fromUid == myUid) "You deleted this message" else "This message was deleted"
                            last.fromUid == myUid -> "You: ${last.text}"
                            else -> last.text
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontStyle = if (last.deleted) FontStyle.Italic else FontStyle.Normal,
                            fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (unread) p.textPrimary else p.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (unread) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.clip(CircleShape).background(p.accent).padding(horizontal = 7.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (row.unread > 99) "99+" else row.unread.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = p.onAccent,
                            )
                        }
                    }
                }
            }
        }
    }
}
