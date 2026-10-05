package com.syncit.app.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.syncit.app.ui.theme.StatusColors
import com.syncit.core.model.Member
import com.syncit.core.model.Room
import com.syncit.sync.PositionMath
import kotlin.math.abs

/** Reports older than this aren't shown in the drift overlay. */
private const val REPORT_MAX_AGE_MS = 15_000L

@Composable
fun MembersCard(
    room: Room,
    myUid: String?,
    serverNow: Long,
    showDrift: Boolean,
    onToggleDrift: (Boolean) -> Unit,
    myEngineDriftMs: Long?,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "People (${room.members.count { it.online }} online)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text("Drift (debug)", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(4.dp))
                Switch(checked = showDrift, onCheckedChange = onToggleDrift)
            }

            val roomExpected = room.state?.let { PositionMath.roomExpectedPositionMs(it, serverNow) }

            for (member in room.members.sortedWith(compareBy({ !it.online }, { it.name.lowercase() }))) {
                MemberRow(
                    member = member,
                    isHost = member.uid == room.hostUid,
                    isMe = member.uid == myUid,
                    driftMs = if (showDrift) memberDrift(member, room, roomExpected, serverNow) else null,
                    showDrift = showDrift,
                )
            }

            if (showDrift) {
                Text(
                    "Room expected position: ${roomExpected?.let(::formatClock) ?: "-"}" +
                        "  ·  my engine drift: ${myEngineDriftMs?.let(::formatDrift) ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "Drift = member's last reported position (extrapolated) minus the room's expected " +
                        "position. Corrections kick in above ±1.5 s.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun MemberRow(member: Member, isHost: Boolean, isMe: Boolean, driftMs: Long?, showDrift: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (member.online) StatusColors.Online else StatusColors.Offline),
        )
        Spacer(Modifier.size(8.dp))
        val tags = listOfNotNull("host".takeIf { isHost }, "you".takeIf { isMe }, "offline".takeIf { !member.online })
        Text(
            member.name + if (tags.isEmpty()) "" else "  (${tags.joinToString(", ")})",
            modifier = Modifier.weight(1f),
        )
        if (showDrift) {
            Text(
                driftMs?.let(::formatDrift) ?: "no report",
                fontFamily = FontFamily.Monospace,
                color = when {
                    driftMs == null -> StatusColors.Offline
                    abs(driftMs) > 1_500 -> StatusColors.Warning
                    else -> StatusColors.Online
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
