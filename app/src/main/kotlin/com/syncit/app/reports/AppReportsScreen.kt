package com.syncit.app.reports

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.ui.chat.copyToClipboard
import com.syncit.app.ui.chat.systemShowsCopies
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassButtonStyle
import com.syncit.app.ui.glass.GlassChip
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassSegmented
import com.syncit.app.ui.glass.GlassSmallButton
import com.syncit.app.ui.glass.GlassTextField
import com.syncit.app.ui.glass.GlassTopBar
import com.syncit.app.ui.glass.NoticeBanner
import com.syncit.app.ui.glass.SectionLabel
import com.syncit.app.ui.glass.raised
import com.syncit.app.ui.profile.BirthDates
import com.syncit.app.ui.profile.PhotoViewer
import com.syncit.app.ui.theme.Glass
import com.syncit.core.social.AppUser
import com.syncit.core.social.Staff
import com.syncit.core.social.StaffRole
import java.time.LocalDate

private enum class Tab(val label: String) { CRASHES("Crashes"), USERS("Users"), NOTIFY("Notify"), TEAM("Team") }

/**
 * App reports (2026-11-10): only for the owner and the developers the owner adds, and only after the
 * phone's own fingerprint/face/screen lock. Crashes are decrypted on this phone.
 */
@Composable
fun AppReportsScreen(viewModel: AppReportsViewModel, onBack: () -> Unit) {
    val p = Glass.palette
    val context = LocalContext.current
    val role by viewModel.role.collectAsStateWithLifecycle()
    val lock by viewModel.lock.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val unlock = rememberStaffUnlock { ok -> if (ok) viewModel.onUnlocked(context) else viewModel.onUnlockFailed(context) }
    var openGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var openUser by rememberSaveable { mutableStateOf<String?>(null) }

    // Straight to the prompt; the button below is for a second try.
    LaunchedEffect(role, lock) { if (role != null && lock == AppReportsViewModel.Lock.LOCKED) unlock() }
    BackHandler(enabled = openGroup != null || openUser != null) {
        openGroup = null
        openUser = null
    }

    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GlassTopBar(
                title = "App reports",
                navigation = {
                    GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", {
                        when {
                            openGroup != null -> openGroup = null
                            openUser != null -> openUser = null
                            else -> onBack()
                        }
                    })
                },
            )
            val r = role
            when {
                r == null -> Centered("App reports are only for the app's owner and developers.")
                lock == AppReportsViewModel.Lock.NO_SCREEN_LOCK -> Centered("Set a screen lock (PIN, pattern or fingerprint) on this phone, then come back. Reports only open after it.")
                lock == AppReportsViewModel.Lock.LOCKED -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                ) {
                    Text("Locked", style = MaterialTheme.typography.titleMedium, color = p.textPrimary)
                    Text(
                        "Reports are encrypted for this phone. Unlock with your fingerprint, face or screen lock.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                    GlassButton("Unlock", onClick = unlock, icon = Icons.Filled.Lock)
                }
                else -> Unlocked(viewModel, r, openGroup, onOpenGroup = { openGroup = it }, openUser, onOpenUser = { openUser = it })
            }
        }
        NoticeBanner(notice, viewModel::dismissNotice, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Glass.palette.textSecondary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Unlocked(
    viewModel: AppReportsViewModel,
    role: StaffRole,
    openGroup: String?,
    onOpenGroup: (String?) -> Unit,
    openUser: String?,
    onOpenUser: (String?) -> Unit,
) {
    val tabs = if (role == StaffRole.OWNER) Tab.entries else listOf(Tab.CRASHES, Tab.NOTIFY)
    var tab by rememberSaveable { mutableStateOf(Tab.CRASHES) }
    val crashes by viewModel.crashes.collectAsStateWithLifecycle()
    val group = openGroup?.let { sig -> crashes?.firstOrNull { it.sig == sig } }
    if (group != null) {
        CrashGroupView(group, viewModel)
        return
    }
    val users by viewModel.users.collectAsStateWithLifecycle()
    val user = openUser?.let { uid -> users?.firstOrNull { it.uid == uid } }
    if (role == StaffRole.OWNER && user != null) {
        UserDetail(user, viewModel)
        return
    }
    Column(Modifier.fillMaxSize()) {
        GlassSegmented(tabs.map { it.label }, tabs.indexOf(tab).coerceAtLeast(0), { tab = tabs[it] }, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (tab) {
                Tab.CRASHES -> CrashesTab(viewModel, crashes, onOpenGroup)
                Tab.USERS -> UsersTab(viewModel, onOpenUser)
                Tab.NOTIFY -> NotifyTab(viewModel, role)
                Tab.TEAM -> TeamTab(viewModel)
            }
        }
    }
}

// ---- Crashes ----

@Composable
private fun CrashesTab(viewModel: AppReportsViewModel, crashes: List<CrashGroup>?, onOpenGroup: (String?) -> Unit) {
    val p = Glass.palette
    val unreadable by viewModel.unreadable.collectAsStateWithLifecycle()
    when {
        crashes == null -> CircularProgressIndicator(color = p.accent)
        crashes.isEmpty() -> Text("No crashes in the last 7 days.", style = MaterialTheme.typography.bodyMedium, color = p.textSecondary)
        else -> {
            val total = crashes.sumOf { it.entries.size }
            SectionLabel("$total ${if (total == 1) "report" else "reports"} · ${crashes.size} ${if (crashes.size == 1) "problem" else "problems"} · last 7 days")
            GlassPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp), spacing = 0.dp) {
                for (g in crashes) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenGroup(g.sig) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(kindLabel(g.latest.kind), style = MaterialTheme.typography.labelSmall, color = p.textSecondary, modifier = Modifier.raised().padding(horizontal = 8.dp, vertical = 2.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(g.latest.title, style = MaterialTheme.typography.titleSmall, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                        Text(g.latest.where, style = MaterialTheme.typography.bodySmall, color = p.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${g.entries.size}× · ${g.people} ${if (g.people == 1) "person" else "people"} · ${ago(g.latest.at)} · v${g.versions.joinToString(", v")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = p.textTertiary,
                        )
                    }
                }
            }
        }
    }
    if (unreadable > 0) {
        Text(
            "$unreadable older ${if (unreadable == 1) "report was" else "reports were"} sent before this phone was set up, so ${if (unreadable == 1) "it's" else "they're"} encrypted for another phone only.",
            style = MaterialTheme.typography.bodySmall,
            color = p.textTertiary,
        )
    }
}

@Composable
private fun CrashGroupView(group: CrashGroup, viewModel: AppReportsViewModel) {
    val p = Glass.palette
    val context = LocalContext.current
    var selected by remember(group.sig) { mutableStateOf(0) }
    val entry = group.entries.getOrElse(selected) { group.latest }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(entry.title, style = MaterialTheme.typography.titleMedium, color = p.textPrimary)
        if (entry.message.isNotBlank()) Text(entry.message, style = MaterialTheme.typography.bodyMedium, color = p.textPrimary)
        Text(entry.where, style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
        Text(
            "${group.entries.size}× · ${group.people} ${if (group.people == 1) "person" else "people"} · v${group.versions.joinToString(", v")}",
            style = MaterialTheme.typography.bodySmall,
            color = p.textTertiary,
        )
        if (group.entries.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                group.entries.take(30).forEachIndexed { i, e -> GlassChip(ago(e.at), onClick = { selected = i }, selected = i == selected) }
            }
        }
        GlassPanel(Modifier.fillMaxWidth(), spacing = 4.dp) {
            Detail("User", entry.username?.let { "@$it" } ?: entry.uid)
            Detail("Phone", "${entry.model} · Android ${entry.android}")
            Detail("App", "v${entry.appVersion} · ${entry.screen} screen")
            Detail("When", DateUtils.formatDateTime(context, entry.at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME))
        }
        GlassButton(
            "Copy report",
            onClick = { if (copyToClipboard(context, entry.asText()) && !systemShowsCopies()) viewModel.showNotice("Report copied") },
            style = GlassButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
        SectionLabel("Stack")
        CodeBlock(entry.stack)
        if (entry.logs.isNotBlank()) {
            SectionLabel("Logs before it happened")
            CodeBlock(entry.logs)
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    val p = Glass.palette
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, color = p.textSecondary, modifier = Modifier.width(56.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = p.textPrimary)
    }
}

/** Long lines scroll sideways instead of wrapping, so stack frames stay one per line. */
@Composable
private fun CodeBlock(text: String) {
    val p = Glass.palette
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .raised(RoundedCornerShape(12.dp))
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(text, style = TextStyle(fontSize = 11.sp, lineHeight = 16.sp), color = p.textPrimary, softWrap = false)
    }
}

private fun kindLabel(kind: String) = when (kind) {
    "freeze" -> "Freeze"
    "native" -> "Native"
    else -> "Crash"
}

private fun ago(at: Long): String =
    DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

// ---- Users (owner) ----

@Composable
private fun UsersTab(viewModel: AppReportsViewModel, onOpenUser: (String?) -> Unit) {
    val p = Glass.palette
    val users by viewModel.users.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val list = users
    if (list == null) {
        CircularProgressIndicator(color = p.accent)
        return
    }
    val online = list.count { it.online }
    SectionLabel("${list.size} ${if (list.size == 1) "person" else "people"} joined · $online online now")
    // Birthdays in the next 30 days, soonest first: for planning surprises.
    val today = LocalDate.now()
    val soon = list.mapNotNull { u -> BirthDates.parse(u.birthDate)?.let { d -> Triple(u, d, BirthDates.daysUntil(d, today)) } }
        .filter { it.third <= BIRTHDAY_WINDOW_DAYS }
        .sortedBy { it.third }
    if (soon.isNotEmpty()) {
        SectionLabel("Birthdays soon")
        GlassPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp), spacing = 0.dp) {
            for ((u, d, days) in soon) {
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenUser(u.uid) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(u.displayName.ifEmpty { "?" }, size = 32.dp, photoUrl = u.photoUrl)
                    Spacer(Modifier.width(12.dp))
                    Text(u.displayName, style = MaterialTheme.typography.bodyMedium, color = p.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "turns ${BirthDates.age(d, BirthDates.nextBirthday(d, today))} ${BirthDates.whenText(days)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (days == 0L) p.accentText else p.textSecondary,
                    )
                }
            }
        }
    }
    val missing = list.count { it.birthDate == null }
    if (missing > 0) {
        Text(
            "$missing ${if (missing == 1) "person hasn't" else "people haven't"} answered the birthday question yet (they're asked next time they open the app).",
            style = MaterialTheme.typography.bodySmall,
            color = p.textTertiary,
        )
    }
    GlassTextField(query, { query = it }, "Search name, username or email", leadingIcon = Icons.Filled.Search, modifier = Modifier.fillMaxWidth())
    val shown = list.filter { it.matches(query) }
    GlassPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp), spacing = 0.dp) {
        for (u in shown) UserRow(u, onClick = { onOpenUser(u.uid) })
        if (shown.isEmpty()) Text("Nobody matches.", style = MaterialTheme.typography.bodySmall, color = p.textSecondary, modifier = Modifier.padding(16.dp))
    }
}

private fun AppUser.matches(q: String): Boolean {
    val t = q.trim().removePrefix("@").lowercase()
    return t.isEmpty() || displayName.lowercase().contains(t) || username.contains(t) || email?.lowercase()?.contains(t) == true
}

@Composable
private fun UserRow(u: AppUser, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val p = Glass.palette
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(u.displayName.ifEmpty { "?" }, size = 40.dp, online = u.online, photoUrl = u.photoUrl)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(u.displayName, style = MaterialTheme.typography.titleSmall, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull("@${u.username}", u.email).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = p.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    "Joined ${ago(u.joinedAt)}",
                    if (u.online) "online" else u.lastSeen?.let { "seen ${ago(it)}" },
                    u.appVersion?.let { "v$it" },
                    u.device,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = p.textTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}

/** Everything the owner can see about one person (2026-11-10). */
@Composable
private fun UserDetail(u: AppUser, viewModel: AppReportsViewModel) {
    val p = Glass.palette
    val context = LocalContext.current
    var viewing by remember { mutableStateOf(false) }
    val birth = BirthDates.parse(u.birthDate)
    val today = LocalDate.now()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(
            u.displayName.ifEmpty { "?" },
            size = 112.dp,
            photoUrl = u.photoUrl,
            modifier = Modifier.clip(CircleShape).clickable(enabled = u.photoUrl != null) { viewing = true },
        )
        Text(u.displayName, style = MaterialTheme.typography.headlineSmall, color = p.textPrimary, textAlign = TextAlign.Center)
        Text("@${u.username}", style = MaterialTheme.typography.bodyMedium, color = p.textSecondary)
        u.bio?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = p.textPrimary, textAlign = TextAlign.Center) }
        GlassPanel(Modifier.fillMaxWidth(), spacing = 8.dp) {
            Field("Email", u.email ?: "Not recorded")
            Field(
                "Birthday",
                birth?.let { d ->
                    val days = BirthDates.daysUntil(d, today)
                    "${BirthDates.display(d)} · ${BirthDates.age(d, today)} years old · next one ${BirthDates.whenText(days)}"
                } ?: "Not given yet (asked next time they open the app)",
            )
            Field("Signed in with", u.provider ?: "Unknown")
            Field("Joined", if (u.joinedAt > 0) DateUtils.formatDateTime(context, u.joinedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR) else "Unknown")
            Field("Last online", if (u.online) "Online now" else u.lastSeen?.let { ago(it) } ?: "Unknown")
            Field("Phone", listOfNotNull(u.device, u.android?.let { "Android $it" }).joinToString(" · ").ifEmpty { "Not recorded yet" })
            Field("App", u.appVersion?.let { "v$it" } ?: "Not recorded yet")
            Field("User ID", u.uid)
        }
        GlassButton(
            "Copy details",
            onClick = {
                val text = buildString {
                    appendLine("${u.displayName} (@${u.username})")
                    u.email?.let { appendLine("Email: $it") }
                    birth?.let { appendLine("Birthday: ${BirthDates.display(it)}") }
                    appendLine("User ID: ${u.uid}")
                }.trimEnd()
                if (copyToClipboard(context, text) && !systemShowsCopies()) viewModel.showNotice("Copied")
            },
            style = GlassButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val photo = u.photoUrl
    if (viewing && photo != null) PhotoViewer(photo, u.displayName) { viewing = false }
}

@Composable
private fun Field(label: String, value: String) {
    val p = Glass.palette
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = p.textPrimary)
    }
}

// ---- Notify ----

@Composable
private fun NotifyTab(viewModel: AppReportsViewModel, role: StaffRole) {
    val p = Glass.palette
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val users by viewModel.users.collectAsStateWithLifecycle()
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var pick by rememberSaveable { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val owner = role == StaffRole.OWNER

    Text(
        if (owner) "Send a notification to everyone, or to people you pick." else "Send a notification to everyone who has BingeJam.",
        style = MaterialTheme.typography.bodyMedium,
        color = p.textSecondary,
    )
    GlassTextField(title, { title = it.take(MAX_TITLE) }, "Title", modifier = Modifier.fillMaxWidth())
    GlassTextField(body, { body = it.take(MAX_BODY) }, "Message", modifier = Modifier.fillMaxWidth(), singleLine = false, maxLines = 5)
    Text("${body.length}/$MAX_BODY", style = MaterialTheme.typography.bodySmall, color = p.textTertiary)
    if (owner) {
        GlassSegmented(listOf("Everyone", "Pick people"), if (pick) 1 else 0, { pick = it == 1 }, Modifier.fillMaxWidth())
    }
    if (owner && pick) {
        SectionLabel("${picked.size} picked · up to $MAX_PICK")
        GlassTextField(query, { query = it }, "Search", leadingIcon = Icons.Filled.Search, modifier = Modifier.fillMaxWidth())
        GlassPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp), spacing = 0.dp) {
            for (u in users.orEmpty().filter { it.matches(query) }.take(100)) {
                val on = u.uid in picked
                UserRow(u) {
                    Checkbox(
                        checked = on,
                        onCheckedChange = { checked -> picked = if (checked && picked.size < MAX_PICK) picked + u.uid else picked - u.uid },
                        colors = CheckboxDefaults.colors(checkedColor = p.accent, uncheckedColor = p.textTertiary),
                    )
                }
            }
        }
    }
    val ready = title.isNotBlank() && body.isNotBlank() && (!pick || picked.isNotEmpty())
    GlassButton(
        if (pick && owner) "Send to ${picked.size} ${if (picked.size == 1) "person" else "people"}" else "Send to everyone",
        onClick = { confirm = true },
        enabled = ready && !sending,
        loading = sending,
        modifier = Modifier.fillMaxWidth(),
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(if (pick && owner) "Send to ${picked.size} ${if (picked.size == 1) "person" else "people"}?" else "Send to everyone?") },
            text = { Text("\"${title.trim()}\" will show as a notification on their phones right away.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    viewModel.send(title, body, if (pick && owner) picked.toList() else null) {
                        title = ""
                        body = ""
                        picked = emptySet()
                    }
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

// ---- Team (owner) ----

@Composable
private fun TeamTab(viewModel: AppReportsViewModel) {
    val p = Glass.palette
    val developers by viewModel.developers.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf("") }
    var removing by remember { mutableStateOf<String?>(null) }
    SectionLabel("Owner")
    GlassPanel(Modifier.fillMaxWidth()) {
        Text(Staff.OWNER_EMAIL, style = MaterialTheme.typography.bodyMedium, color = p.textPrimary)
        Text("Everything: crashes, users and their emails, notifications to anyone, the team.", style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
    }
    SectionLabel("Developers")
    GlassPanel(Modifier.fillMaxWidth(), spacing = 8.dp) {
        if (developers.isEmpty()) Text("No developers yet.", style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
        for (d in developers) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d, style = MaterialTheme.typography.bodyMedium, color = p.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                GlassSmallButton("Remove", onClick = { removing = d }, primary = false)
            }
        }
        Text("Crash reports and notifications to everyone. They need a BingeJam account with this email.", style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlassTextField(email, { email = it.take(254) }, "Developer's email", modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        GlassSmallButton("Add", onClick = {
            viewModel.addDeveloper(email)
            email = ""
        }, enabled = email.contains('@'))
    }
    Text(
        "A new developer can read crash reports sent after they first open App reports on their phone.",
        style = MaterialTheme.typography.bodySmall,
        color = p.textTertiary,
    )
    removing?.let { d ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove $d?") },
            text = { Text("They lose access to App reports, and new reports aren't encrypted for them any more.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    viewModel.removeDeveloper(d)
                }) { Text("Remove", color = p.danger) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

private const val MAX_TITLE = 65
private const val MAX_BODY = 240
private const val MAX_PICK = 20
private const val BIRTHDAY_WINDOW_DAYS = 30L
