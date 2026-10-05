package com.syncit.app.ui.room

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.syncit.core.model.RoomTitle
import com.syncit.core.model.StreamingService

/**
 * Host: pick the service and paste the title's share link. Everyone: "Open movie".
 * We don't try to verify everyone is on the same title (MediaSession metadata isn't reliable
 * enough); we ask people to check instead.
 */
@Composable
fun TitleCard(title: RoomTitle?, isHost: Boolean, onSave: (StreamingService, String) -> Unit) {
    val context = LocalContext.current
    var editing by rememberSaveable { mutableStateOf(false) }
    var confirmOpen by rememberSaveable { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What we're watching", style = MaterialTheme.typography.titleMedium)

            if (title == null) {
                Text(if (isHost) "Pick the service and paste the movie's share link." else "Waiting for the host to pick a title.")
            } else {
                Text("${title.service.displayName}", fontWeight = FontWeight.Bold)
                Text(title.deepLink, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "Make sure you're all on the same title (and episode) before pressing play.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { confirmOpen = true }) { Text("Open movie") }
            }

            if (isHost) {
                if (editing || title == null) {
                    TitleEditor(
                        initial = title,
                        onSave = { service, link ->
                            onSave(service, link)
                            editing = false
                        },
                        onCancel = if (title != null) ({ editing = false }) else null,
                    )
                } else {
                    TextButton(onClick = { editing = true }) { Text("Change title") }
                }
            }
        }
    }

    if (confirmOpen && title != null) {
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            title = { Text("Make sure you're all on the same title") },
            text = {
                Text(
                    "This opens ${title.service.displayName} on the room's title. Before anyone presses play, " +
                        "check with the others that you all see the same movie or episode. Sync it can't verify that for you.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmOpen = false
                    if (!openTitle(context, title)) {
                        android.widget.Toast.makeText(context, "No app can open this link", android.widget.Toast.LENGTH_LONG).show()
                    }
                }) { Text("Open ${title.service.displayName}") }
            },
            dismissButton = { TextButton(onClick = { confirmOpen = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TitleEditor(
    initial: RoomTitle?,
    onSave: (StreamingService, String) -> Unit,
    onCancel: (() -> Unit)?,
) {
    var service by rememberSaveable { mutableStateOf(initial?.service ?: StreamingService.NETFLIX) }
    var link by rememberSaveable { mutableStateOf(initial?.deepLink.orEmpty()) }
    val check = TitleLinks.check(service, link)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (option in StreamingService.entries) {
            FilterChip(
                selected = service == option,
                onClick = { service = option },
                label = { Text(option.displayName) },
            )
        }
    }
    if (!service.isConfigured) {
        Text(
            "${service.displayName} sync isn't configured yet (package name missing). The link still opens, " +
                "and you can use the 3, 2, 1 countdown.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    OutlinedTextField(
        value = link,
        onValueChange = { link = it.trim() },
        label = { Text("Share link, e.g. https://www.netflix.com/watch/…") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    check.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onSave(service, link) }, enabled = check.valid) { Text("Save for everyone") }
        onCancel?.let { OutlinedButton(onClick = it) { Text("Cancel") } }
    }
}

/** Light validation of pasted share links. */
internal object TitleLinks {
    data class Check(val valid: Boolean, val warning: String?)

    fun check(service: StreamingService, link: String): Check {
        if (link.isBlank()) return Check(false, null)
        val uri = runCatching { link.toUri() }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        if (uri == null || (scheme != "https" && scheme != "http") || uri.host.isNullOrBlank()) {
            return Check(false, "Paste the full link starting with https://")
        }
        if (link.length > 500) return Check(false, "That link is too long")
        val host = uri.host!!.lowercase()
        val matches = service.linkHosts.any { host == it || host.endsWith(".$it") }
        return Check(true, if (matches) null else "This doesn't look like a ${service.displayName} link, but it'll be saved anyway.")
    }
}

/**
 * ACTION_VIEW on the share link. Try the official app directly first (when its package is
 * known); if that fails, let Android pick, which usually still routes to the app via its
 * App Links, else the browser.
 */
internal fun openTitle(context: Context, title: RoomTitle): Boolean {
    val view = Intent(Intent.ACTION_VIEW, title.deepLink.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (title.service.isConfigured) {
        try {
            context.startActivity(Intent(view).setPackage(title.service.packageName))
            return true
        } catch (_: ActivityNotFoundException) {
            // Not installed, or it doesn't handle this link: fall through.
        }
    }
    return try {
        context.startActivity(view)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
