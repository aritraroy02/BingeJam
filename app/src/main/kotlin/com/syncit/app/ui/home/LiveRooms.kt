package com.syncit.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncit.app.di.AppContainer
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.SectionLabel
import com.syncit.app.ui.glass.SyncDots
import com.syncit.app.ui.glass.glass
import com.syncit.app.ui.theme.Glass
import com.syncit.core.model.ChatRoomLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** A chat (group or 1:1) whose room has people in it right now. Tapping opens that chat. */
data class LiveRoom(
    val groupId: String?,
    val friendUid: String?,
    val title: String,
    val photoUrl: String?,
    val people: Int,
    val startedAt: Long,
) {
    val key: String get() = groupId?.let { "g:$it" } ?: "d:$friendUid"
}

/** Live rooms on the Watch tab: your groups' rooms and your recent 1:1 chats' rooms with someone in them. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveRoomsViewModel(container: AppContainer) : ViewModel() {
    private val social = container.social

    private val groupRooms: Flow<List<LiveRoom>> = social.groups.observeMyGroups()
        .catch { emit(emptyList()) }
        .map { list -> list.map { it.id to it.name } }
        .distinctUntilChanged()
        .flatMapLatest { groups ->
            if (groups.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(groups.map { (id, name) ->
                    social.groups.observeRoom(id).catch { emit(null) }.map { link -> link.live()?.let { LiveRoom(id, null, name, null, it.inRoom.size, it.startedAt) } }
                }) { it.filterNotNull() }
            }
        }

    private val dmRooms: Flow<List<LiveRoom>> = combine(
        social.friends.observeFriends().catch { emit(emptyList()) },
        social.dms.observeLatest().catch { emit(emptyMap()) },
    ) { friends, latest ->
        friends.filter { latest.containsKey(it.profile.uid) }
            .sortedByDescending { latest[it.profile.uid]?.sentAt ?: 0L }
            .take(MAX_DM_ROOMS)
            .map { it.profile }
    }
        .distinctUntilChanged()
        .flatMapLatest { profiles ->
            if (profiles.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(profiles.map { f ->
                    social.dms.observeRoom(f.uid).catch { emit(null) }
                        .map { link -> link.live()?.let { LiveRoom(null, f.uid, f.displayName, f.photoUrl, it.inRoom.size, it.startedAt) } }
                }) { it.filterNotNull() }
            }
        }

    val rooms: StateFlow<List<LiveRoom>> = combine(groupRooms, dmRooms) { g, d -> (g + d).sortedByDescending { it.startedAt } }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun ChatRoomLink?.live(): ChatRoomLink? = this?.takeIf { it.inRoom.isNotEmpty() }

    private companion object {
        const val MAX_DM_ROOMS = 12
    }
}

@Composable
fun LiveRoomsRow(rooms: List<LiveRoom>, onOpen: (LiveRoom) -> Unit, modifier: Modifier = Modifier) {
    val p = Glass.palette
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Live rooms")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 4.dp)) {
            items(rooms, key = { it.key }) { room ->
                val shape = RoundedCornerShape(16.dp)
                Row(
                    Modifier
                        .widthIn(min = 150.dp, max = 220.dp)
                        .clip(shape)
                        .glass(shape)
                        .clickable(role = Role.Button) { onOpen(room) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(room.title, size = 36.dp, photoUrl = room.photoUrl)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(room.title, style = MaterialTheme.typography.titleSmall, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SyncDots(size = 8.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (room.people == 1) "1 watching" else "${room.people} watching",
                                style = MaterialTheme.typography.bodySmall,
                                color = p.textSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
