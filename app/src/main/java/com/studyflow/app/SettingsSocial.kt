@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.studyflow.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studyflow.app.p2p.DEFAULT_RELAYS
import com.studyflow.app.p2p.Relays
import com.studyflow.app.p2p.Social

/** The "Profile & groups" section of Settings. */
@Composable
fun ProfileSection(social: Social, onOpenProfile: () -> Unit) {
    val c = social.cfg
    val clip = LocalClipboardManager.current
    var showKey by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf(false) }
    var restartNeeded by remember { mutableStateOf(false) }
    var newRelay by remember { mutableStateOf("") }
    var relayErr by remember { mutableStateOf(false) }
    val net = social.net
    val status = social.relayStatus()

    Section("Profile and groups", startOpen = c.name.isBlank()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(c.name.ifBlank { "?" }, c.color, c.glyph, 56.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(c.name.ifBlank { "No name yet" }, style = MaterialTheme.typography.titleMedium)
                Text("This is how other people see you.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onOpenProfile) { Text("View") }
        }
        OutlinedTextField(
            value = c.name, onValueChange = { v -> social.updateCfg { it.copy(name = v.take(24)) } },
            label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = c.bio, onValueChange = { v -> social.updateCfg { it.copy(bio = v.take(160)) } },
            label = { Text("About you (optional)") }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
        )
        Text("Avatar colour")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PALETTE.forEachIndexed { i, col ->
                Box(
                    Modifier.size(32.dp).clip(MaterialTheme.shapes.small).background(Color(col))
                        .border(if (i == c.color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, MaterialTheme.shapes.small)
                        .clickable { social.updateCfg { it.copy(color = i) } },
                )
            }
        }
        Text("Avatar symbol")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GLYPHS.forEachIndexed { i, g ->
                Box(
                    Modifier.size(40.dp).clip(MaterialTheme.shapes.small)
                        .border(if (i == c.glyph) 2.dp else 1.dp, if (i == c.glyph) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                        .clickable { social.updateCfg { it.copy(glyph = i) } },
                    contentAlignment = Alignment.Center,
                ) {
                    if (g != null) Icon(g, null, Modifier.size(20.dp)) else Text(c.name.trim().take(1).uppercase().ifEmpty { "A" })
                }
            }
        }

        SwitchRow("Share my study time", "Group members can see your totals and, optionally, your sessions.", c.share) { v -> social.updateCfg { it.copy(share = v) } }
        Text("Detail level")
        Segmented(listOf("Daily totals", "Full sessions"), c.detail, enabled = c.share) { i -> social.updateCfg { it.copy(detail = i) } }
        SwitchRow("Count manually added sessions", "Off keeps the board to time you actually timed.", c.manual) { v -> social.updateCfg { it.copy(manual = v) } }
        SwitchRow("Show me on the global leaderboard", "Anyone using StudyFlow can see your totals.", c.board) { v -> social.updateCfg { it.copy(board = v, share = if (v) true else it.share) } }
        SwitchRow("Stay live while timing", "Keeps a small notification so groups see you studying even with the app closed.", c.keepLive) { v -> social.updateCfg { it.copy(keepLive = v) } }

        Text("Your public ID", style = MaterialTheme.typography.titleSmall)
        Text(social.me, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { clip.setText(AnnotatedString(social.me)) }) { Text("Copy ID") }
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { showKey = true }) { Text("Back up key") }
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { restore = true }) { Text("Restore") }
        }

        Text("Relays", style = MaterialTheme.typography.titleSmall)
        Text(
            if (net.open > 0) "${net.open} of ${net.total} connected" else "Not connected right now",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        c.relays.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (status[r] == true) Color(0xFF22C55E) else MaterialTheme.colorScheme.outline))
                Text(r.removePrefix("wss://"), Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { social.updateCfg { it.copy(relays = it.relays - r) } }) { Icon(Ico.Close, "Remove relay", Modifier.size(16.dp)) }
            }
        }
        OutlinedTextField(
            value = newRelay, onValueChange = { newRelay = it; relayErr = false },
            label = { Text("Add relay (wss://...)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = relayErr,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = {
                val u = newRelay.trim()
                if (Relays.valid(u)) { social.updateCfg { it.copy(relays = (it.relays + u).distinct()) }; newRelay = "" } else relayErr = true
            }) { Text("Add") }
            TextButton(onClick = { social.updateCfg { it.copy(relays = DEFAULT_RELAYS) } }) { Text("Reset list") }
        }
        Text(
            "Relays only pass signed messages along. Several are used at once so no single one is needed, and your phone re-sends data a relay is missing.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showKey) {
        AlertDialog(
            onDismissRequest = { showKey = false },
            title = { Text("Your secret key") },
            text = { Text("Anyone with this key can act as you. Keep it private. Use Restore on a new phone to continue with the same identity and groups.") },
            confirmButton = { TextButton(onClick = { clip.setText(AnnotatedString(social.secretHex())); showKey = false }) { Text("Copy key") } },
            dismissButton = { TextButton(onClick = { showKey = false }) { Text("Close") } },
        )
    }
    if (restore) {
        var text by remember { mutableStateOf("") }
        var bad by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { restore = false },
            title = { Text("Restore identity") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This replaces the identity on this phone. Public groups you were in come back; private groups need their invite links again.")
                    OutlinedTextField(value = text, onValueChange = { text = it; bad = false }, label = { Text("Secret key (64 hex characters)") }, isError = bad, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (social.importIdentity(text)) { restore = false; restartNeeded = true } else bad = true
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { restore = false }) { Text("Cancel") } },
        )
    }
    if (restartNeeded) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Restart needed") },
            text = { Text("The app will close now. Open it again to continue with the restored identity.") },
            confirmButton = { TextButton(onClick = { android.os.Process.killProcess(android.os.Process.myPid()) }) { Text("Close app") } },
        )
    }
}
