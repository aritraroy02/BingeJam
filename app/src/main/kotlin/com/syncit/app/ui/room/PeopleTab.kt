package com.syncit.app.ui.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.theme.Glass
import com.syncit.app.ui.theme.Inter
import com.syncit.core.model.Member
import com.syncit.core.model.Room
import com.syncit.sync.PositionMath
import kotlin.math.abs

/** Reports older than this aren't shown in the drift overlay. */
private const val REPORT_MAX_AGE_MS = 15_000L

@Composable
fun PeopleTab(
    room: Room,
    myUid: String?,
    serverNow: Long,
    myEngineDriftMs: Long?,
    modifier: Modifier = Modifier,
) {
    val p = Glass.palette
    var showDrift by rememberSaveable { mutableStateOf(false) }
    val roomExpected = room.state?.let { PositionMath.roomExpectedPositionMs(it, serverNow) }
    val members = room.members.sortedWith(compareBy({ !it.online }, { it.uid != room.hostUid }, { it.name.lowercase() }))

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            GlassPanel(spacing = 16.dp) {
                for (member in members) {
                    MemberRow(
                        member = member,
                        isHost = member.uid == room.hostUid,
                        isMe = member.uid == myUid,
                        driftMs = if (showDrift) memberDrift(member, room, roomExpected, serverNow) else null,
                        showDrift = showDrift,
                    )
                }
            }
        }
        item {
            GlassPanel(Modifier.navigationBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show drift", style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
                        Text(
                            "How far each person is from the room's position. Corrections kick in above ±1.5 s.",
                            style = MaterialTheme.typography.bodySmall,
                            color = p.textSecondary,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = showDrift,
                        onCheckedChange = { showDrift = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = p.onAccent),
                    )
                }
                if (showDrift) {
                    Text(
                        "Room position ${roomExpected?.let(::formatClock) ?: "-"}  ·  mine ${myEngineDriftMs?.let(::formatDrift) ?: "-"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = Inter,
                        color = p.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun MemberRow(member: Member, isHost: Boolean, isMe: Boolean, driftMs: Long?, showDrift: Boolean) {
    val p = Glass.palette
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(member.name, size = 42.dp, online = member.online)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(member.name + if (isMe) " (you)" else "", style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
            val tags = listOfNotNull("Host".takeIf { isHost }, if (member.online) "Watching" else "Offline")
            Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
        }
        if (showDrift) {
            Text(
                driftMs?.let(::formatDrift) ?: "no report",
                fontFamily = Inter,
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    driftMs == null -> p.textTertiary
                    abs(driftMs) > 1_500 -> p.warning
                    else -> p.success
                },
            )
        }
    }
}

private fun memberDrift(member: Member, room: Room, roomExpected: Long?, serverNow: Long): Long? {
    val expected = roomExpected ?: return null
    val memberPosition = PositionMath.memberExpectedPositionMs(member, room.state?.status, serverNow, REPORT_MAX_AGE_MS)
        ?: return null
    return memberPosition - expected
}

internal fun formatDrift(ms: Long): String = "%+.1fs".format(ms / 1000.0)

internal fun formatClock(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
}
