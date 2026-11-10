package com.syncit.app.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syncit.app.R
import com.syncit.app.ui.glass.glass
import com.syncit.app.ui.theme.Glass

/** The three main screens, switched from the bottom bar (2026-11-10: Add friends moved into Chats' "+"). */
enum class MainTab { WATCH, CHATS, YOU }

/**
 * How much the floating tab bar covers at the bottom of a tab screen, so its scrolling content can
 * end above it (added to the screen's own navigation-bar padding). 0 on every other screen.
 */
val LocalBottomBarInset = compositionLocalOf { 0.dp }

/** Bar height plus the gap under it. */
val TabBarInset: Dp = 84.dp

/**
 * Floating glass bar with icons only: Watch, Chats, You. The Chats badge counts unread messages
 * plus friend requests (those are answered behind Chats' "+").
 */
@Composable
fun MainTabBar(
    current: MainTab,
    unreadChats: Int,
    friendRequests: Int,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .height(60.dp)
            .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
            .glass(shape, floating = true),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabIcon(ImageVector.vectorResource(R.drawable.ic_tab_watch), "Watch", current == MainTab.WATCH, 0) { onSelect(MainTab.WATCH) }
        TabIcon(ImageVector.vectorResource(R.drawable.ic_tab_chat), "Chats", current == MainTab.CHATS, unreadChats + friendRequests) {
            onSelect(MainTab.CHATS)
        }
        TabIcon(Icons.Filled.Person, "You", current == MainTab.YOU, 0) { onSelect(MainTab.YOU) }
    }
}

@Composable
private fun TabIcon(icon: ImageVector, label: String, selected: Boolean, badge: Int, onClick: () -> Unit) {
    val p = Glass.palette
    val haptics = LocalHapticFeedback.current
    val tint by animateColorAsState(if (selected) p.base else p.textSecondary, label = "tabTint")
    val fill by animateColorAsState(if (selected) p.textPrimary else Color.Transparent, label = "tabFill")
    // Only the round tap target is clipped; the badge sits on top of it, uncut.
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Tab) {
                    if (!selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onClick()
                }
                .semantics {
                    contentDescription = if (badge > 0) "$label, $badge new" else label
                    this.selected = selected
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
            }
        }
        if (badge > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = 2.dp)
                    .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(p.accent)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (badge > 99) "99+" else badge.toString(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
                )
            }
        }
    }
}
