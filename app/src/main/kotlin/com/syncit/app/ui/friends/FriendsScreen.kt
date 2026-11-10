package com.syncit.app.ui.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.CountBadge
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.staggerIn
import com.syncit.core.config.Brand
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassSmallButton
import com.syncit.app.ui.glass.GlassTextField
import com.syncit.app.ui.glass.GlassTopBar
import com.syncit.app.ui.nav.LocalBottomBarInset
import com.syncit.app.ui.glass.NoticeBanner
import com.syncit.app.ui.glass.SectionLabel
import com.syncit.app.ui.theme.Glass

/** [onBack] is null when Friends is a tab (no back arrow). */
@Composable
fun FriendsScreen(viewModel: FriendsViewModel, onBack: (() -> Unit)?, onInvite: () -> Unit = {}) {
    val p = Glass.palette
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val incoming by viewModel.incoming.collectAsStateWithLifecycle()
    val outgoing by viewModel.outgoing.collectAsStateWithLifecycle()
    val blocked by viewModel.blocked.collectAsStateWithLifecycle()
    val blockedGroups by viewModel.blockedGroups.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val myUsername by viewModel.myUsername.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GlassTopBar(
                title = "Friends",
                navigation = onBack?.let { back -> { GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", back) } },
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp + LocalBottomBarInset.current),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GlassTextField(
                    value = query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = "Search by username",
                    leadingIcon = Icons.Filled.Search,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Search,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    trailing = {
                        if (searching) CircularProgressIndicator(Modifier.padding(end = 16.dp).size(18.dp), color = p.accent, strokeWidth = 2.dp)
                    },
                )
                if (query.trim().removePrefix("@").length >= 3) {
                    GlassPanel(Modifier.fillMaxWidth(), spacing = 4.dp, contentPadding = PaddingValues(12.dp)) {
                        if (results.isEmpty()) {
                            Text(
                                if (searching) "Searching…" else "No one has exactly that username",
                                style = MaterialTheme.typography.bodyMedium,
                                color = p.textSecondary,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                        for (r in results) {
                            PersonRow(r.profile.displayName, "@${r.profile.username}", r.profile.photoUrl) {
                                when (r.relation) {
                                    Relation.NONE -> GlassSmallButton("Add", onClick = { viewModel.add(r.profile) }, enabled = r.profile.uid !in busy)
                                    Relation.INCOMING -> GlassSmallButton("Accept", onClick = { viewModel.acceptFrom(r.profile) }, enabled = r.profile.uid !in busy)
                                    Relation.REQUESTED -> GlassSmallButton("Requested", onClick = {}, enabled = false, primary = false)
                                    Relation.FRIEND -> GlassSmallButton("Friends", onClick = {}, enabled = false, primary = false)
                                    Relation.BLOCKED -> GlassSmallButton("Unblock", onClick = { viewModel.unblock(r.profile) }, primary = false)
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        "Type their full username. Usernames are private, so only an exact match finds someone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textTertiary,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    // The other half of making friends: let them find you.
                    if (myUsername != null) {
                        GlassPanel(Modifier.fillMaxWidth().padding(top = 8.dp).staggerIn(1)) {
                            Text("Invite friends to ${Brand.NAME}", style = MaterialTheme.typography.titleMedium, color = p.textPrimary)
                            Text(
                                "Send your link or show your QR code. When a friend opens it, you're friends straight away.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = p.textSecondary,
                            )
                            GlassButton(
                                "Invite with link or QR",
                                icon = Icons.Filled.Share,
                                onClick = onInvite,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                if (incoming.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                        SectionLabel("Requests")
                        Spacer(Modifier.width(8.dp))
                        CountBadge(incoming.size)
                    }
                    GlassPanel(Modifier.fillMaxWidth(), spacing = 4.dp, contentPadding = PaddingValues(12.dp)) {
                        for (req in incoming) {
                            PersonRow("@${req.fromUsername}", "${req.fromDisplayName} · wants to be friends") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    GlassSmallButton("Decline", onClick = { viewModel.decline(req) }, primary = false, enabled = req.fromUid !in busy)
                                    GlassSmallButton(
                                        "Accept",
                                        onClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                            viewModel.accept(req)
                                        },
                                        enabled = req.fromUid !in busy,
                                    )
                                }
                            }
                        }
                    }
                }

                if (outgoing.isNotEmpty()) {
                    SectionLabel("Sent", Modifier.padding(top = 12.dp))
                    GlassPanel(Modifier.fillMaxWidth(), spacing = 4.dp, contentPadding = PaddingValues(12.dp)) {
                        for (req in outgoing) {
                            PersonRow("@${req.toUsername}", "${req.toDisplayName} · waiting") {
                                GlassSmallButton("Cancel", onClick = { viewModel.cancel(req) }, primary = false, enabled = req.toUid !in busy)
                            }
                        }
                    }
                }

                if (blocked.isNotEmpty()) {
                    SectionLabel("Blocked", Modifier.padding(top = 12.dp))
                    GlassPanel(Modifier.fillMaxWidth(), spacing = 4.dp, contentPadding = PaddingValues(12.dp)) {
                        for (profile in blocked) {
                            PersonRow(profile.displayName, "@${profile.username}", profile.photoUrl) {
                                GlassSmallButton("Unblock", onClick = { viewModel.unblock(profile) }, primary = false, enabled = profile.uid !in busy)
                            }
                        }
                    }
                }

                if (blockedGroups.isNotEmpty()) {
                    SectionLabel("Blocked groups", Modifier.padding(top = 12.dp))
                    GlassPanel(Modifier.fillMaxWidth(), spacing = 4.dp, contentPadding = PaddingValues(12.dp)) {
                        for (g in blockedGroups) {
                            PersonRow(g.name.ifEmpty { "Group" }, "Nobody can add you to it") {
                                GlassSmallButton("Unblock", onClick = { viewModel.unblockGroup(g) }, primary = false, enabled = g.id !in busy)
                            }
                        }
                    }
                }
            }
        }
        NoticeBanner(message, viewModel::dismissMessage, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = LocalBottomBarInset.current))
    }
}

@Composable
private fun PersonRow(name: String, subtitle: String, photoUrl: String? = null, trailing: @Composable () -> Unit) {
    // Request rows lead with "@username"; the avatar letter skips the @.
    val p = Glass.palette
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(name.removePrefix("@"), size = 42.dp, photoUrl = photoUrl)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Box { trailing() }
    }
}
