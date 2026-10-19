@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.studyflow.app

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studyflow.app.p2p.Acts
import com.studyflow.app.p2p.CATEGORIES
import com.studyflow.app.p2p.GroupView
import com.studyflow.app.p2p.InviteCodec
import com.studyflow.app.p2p.JOIN_APPROVAL
import com.studyflow.app.p2p.JOIN_CLOSED
import com.studyflow.app.p2p.JOIN_OPEN
import com.studyflow.app.p2p.MAX_MEMBERS
import com.studyflow.app.p2p.Msg
import com.studyflow.app.p2p.Period
import com.studyflow.app.p2p.ST_BANNED
import com.studyflow.app.p2p.ST_CLOSED
import com.studyflow.app.p2p.ST_FULL
import com.studyflow.app.p2p.ST_KICKED
import com.studyflow.app.p2p.ST_MEMBER
import com.studyflow.app.p2p.ST_NONE
import com.studyflow.app.p2p.ST_PENDING
import com.studyflow.app.p2p.Snapshot
import com.studyflow.app.p2p.Social
import com.studyflow.app.p2p.StatsIndex
import com.studyflow.app.p2p.secFor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun GroupScreen(vm: MainVM, gref: String, onBack: () -> Unit, onProfile: (String) -> Unit) {
    val social = vm.social
    val snap = social.snap
    val view = snap.groups[gref]
    val me = social.me

    DisposableEffect(gref) {
        onDispose { if (social.joined.none { it.gref == gref }) social.unpeek(gref) }
    }

    if (view == null || view.def.closed) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Group", onBack)
            Note(
                if (view?.def?.closed == true) "This group was closed by its owner."
                else if (social.net.open == 0) "Offline. The group will load when a relay connects."
                else "Looking for this group on the relays...",
                Modifier.padding(24.dp),
            )
        }
        return
    }

    val def = view.def
    val st = view.statusOf(me)
    val member = st == ST_MEMBER
    val isMod = def.isMod(me)
    val tabs = buildList {
        add("Members")
        if (member) { add("Chat"); add("Board") }
        if (isMod) add("Manage")
    }
    var tab by rememberSaveable(gref) { mutableStateOf(0) }
    val cur = tabs.getOrElse(tab) { "Members" }
    var leaving by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopBar(def.name, onBack)
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            Text(
                "${view.members.size}/${def.max} members · " + (if (def.isPrivate) "Private" else "Public") + " · " +
                    when (def.join) { JOIN_OPEN -> "Open"; JOIN_APPROVAL -> "Approval needed"; else -> "Closed" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (def.desc.isNotBlank()) Text(def.desc, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            if (def.ann.isNotBlank()) {
                Row(
                    Modifier.padding(top = 10.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(def.ann, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!member) JoinBanner(social, gref, view, st, Modifier.padding(top = 12.dp))
        }
        UnderlineTabs(tabs, tab.coerceIn(0, tabs.size - 1)) { tab = it }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (cur) {
                "Members" -> MembersTab(snap, view, me, onProfile, member && me != def.founder) { leaving = true }
                "Chat" -> ChatTab(social, snap, gref, view, me, isMod, onProfile)
                "Board" -> BoardTab(snap, view, me, onProfile)
                else -> ManageTab(social, snap, gref, view, me)
            }
        }
    }

    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Leave this group?") },
            text = { Text("Your stats will no longer be visible to its members. You can join again later if there is room.") },
            confirmButton = { TextButton(onClick = { leaving = false; social.leaveGroup(gref); onBack() }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun JoinBanner(social: Social, gref: String, view: GroupView, st: Int, modifier: Modifier) {
    val joinedLocally = social.joined.any { it.gref == gref }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val msg = when (st) {
            ST_PENDING -> "Your request is waiting for a moderator."
            ST_BANNED -> "You are banned from this group."
            ST_KICKED -> "You were removed from this group."
            ST_FULL -> "This group is full."
            ST_CLOSED -> "This group is closed to new members."
            else -> null
        }
        if (msg != null) Note(msg)
        val canJoin = st == ST_NONE || (st == ST_KICKED && view.def.join == JOIN_OPEN)
        if (canJoin) {
            ProfileGate(social) { need ->
                Button(
                    shape = MaterialTheme.shapes.small,
                    enabled = !view.full || view.def.join != JOIN_OPEN,
                    onClick = {
                        need {
                            social.inviteFor(gref)?.let { social.joinGroup(it) }
                        }
                    },
                ) { Text(if (view.def.join == JOIN_APPROVAL) "Request to join" else "Join group") }
            }
            if (view.full) Note("The group is currently full.")
        }
        if (joinedLocally && st != ST_NONE) {
            TextButton(onClick = { social.forgetGroup(gref) }) { Text("Remove from my list") }
        }
    }
}

// ---------------------------------------------------------------------------
// Members: four columns, rows separated by hairlines
// ---------------------------------------------------------------------------

@Composable
private fun MembersTab(snap: Snapshot, view: GroupView, me: String, onProfile: (String) -> Unit, canLeave: Boolean, onLeave: () -> Unit) {
    val rule = MaterialTheme.colorScheme.outlineVariant
    val today = todayOf()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        view.members.chunked(4).forEachIndexed { r, row ->
            if (r > 0) HorizontalDivider(color = rule)
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 4) {
                    val pub = row.getOrNull(c)
                    Box(
                        Modifier
                            .weight(1f)
                            .drawBehind {
                                if (c < 3) drawLine(rule, Offset(size.width, 0f), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
                            },
                    ) {
                        if (pub != null) MemberCell(snap, pub, view, me, today) { onProfile(pub) }
                        else Spacer(Modifier.height(112.dp))
                    }
                }
            }
        }
        HorizontalDivider(color = rule)
        if (canLeave) TextButton(onClick = onLeave, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { Text("Leave group") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MemberCell(snap: Snapshot, pub: String, view: GroupView, me: String, today: java.time.LocalDate, onClick: () -> Unit) {
    val sec = secFor(snap.stats[pub] ?: StatsIndex.EMPTY, Period.DAY, today)
    val live = isLive(snap.live[pub])
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            UserAvatar(snap, pub, 48.dp)
            if (pub == view.def.founder) {
                Icon(
                    Ico.Crown, "Owner",
                    Modifier.align(Alignment.TopEnd).size(16.dp).background(MaterialTheme.colorScheme.background).padding(1.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else if (pub in view.def.mods) {
                Icon(
                    Ico.Shield, "Moderator",
                    Modifier.align(Alignment.TopEnd).size(16.dp).background(MaterialTheme.colorScheme.background).padding(1.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            nameOf(snap, pub) + if (pub == me) " (you)" else "",
            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            if (live) "Studying" else if (sec > 0) fmtDur(sec) else "-",
            style = MaterialTheme.typography.bodySmall,
            color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Chat
// ---------------------------------------------------------------------------

@Composable
private fun ChatTab(social: Social, snap: Snapshot, gref: String, view: GroupView, me: String, isMod: Boolean, onProfile: (String) -> Unit) {
    var text by rememberSaveable(gref) { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Msg?>(null) }
    val muted = me in view.muted
    val zone = ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    Column(Modifier.fillMaxSize()) {
        if (view.msgs.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Note("No messages yet. Say hello.") }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true) {
                items(view.msgs.asReversed(), key = { it.id }) { m ->
                    val dt = Instant.ofEpochSecond(m.at).atZone(zone)
                    val t = dt.format(DateTimeFormatter.ofPattern(if (dt.toLocalDate() == today) "HH:mm" else "d MMM HH:mm"))
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = {}, onLongClick = { if (isMod) deleting = m })
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                    ) {
                        Box(Modifier.clickable { onProfile(m.author) }) { UserAvatar(snap, m.author, 32.dp) }
                        Column(Modifier.padding(start = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (m.author == me) "You" else nameOf(snap, m.author),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Text("  $t", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(m.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (muted) {
            Note("A moderator has muted you in this group.", Modifier.padding(16.dp))
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(500) },
                    placeholder = { Text("Message") }, modifier = Modifier.weight(1f), maxLines = 4,
                )
                IconButton(
                    enabled = text.isNotBlank(),
                    onClick = { social.sendMessage(gref, text); text = "" },
                ) { Icon(Ico.Send, "Send", Modifier.size(22.dp)) }
            }
        }
    }
    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete message?") },
            text = { Text("It disappears for everyone in the group.") },
            confirmButton = { TextButton(onClick = { social.deleteMessage(gref, m.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------------
// Group board
// ---------------------------------------------------------------------------

@Composable
private fun BoardTab(snap: Snapshot, view: GroupView, me: String, onProfile: (String) -> Unit) {
    var p by rememberSaveable { mutableStateOf(1) }
    val today = todayOf()
    val entries = view.members.map { RankEntry(it, secFor(snap.stats[it] ?: StatsIndex.EMPTY, periodOf(p), today)) }
        .sortedByDescending { it.sec }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(24.dp)) { Segmented(PERIOD_LABELS, p) { p = it } }
        RankList(entries, snap, me, onProfile)
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------------
// Manage (owner and moderators)
// ---------------------------------------------------------------------------

@Composable
private fun ManageTab(social: Social, snap: Snapshot, gref: String, view: GroupView, me: String) {
    val def = view.def
    val isFounder = me == def.founder
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val inv = social.inviteFor(gref)
    val link = inv?.let { InviteCodec.link(it) } ?: ""
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmBan by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Caps("Invite")
        Note(if (def.isPrivate) "This link contains the group's key. Share it only with people you want inside." else "Share this link so people can open the group directly.")
        Text(link, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { clip.setText(AnnotatedString(link)) }) {
                IconLabel(Ico.Copy, "Copy")
            }
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = {
                val body = "Join my StudyFlow group \"${def.name}\":\n$link\n\nOr paste this code in Groups > Find: ${inv?.let { InviteCodec.code(it) } ?: ""}"
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body), null))
            }) { IconLabel(Ico.Share, "Share") }
        }

        if (isFounder) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Caps("Group settings")
            var name by remember(def.at) { mutableStateOf(def.name) }
            var desc by remember(def.at) { mutableStateOf(def.desc) }
            var ann by remember(def.at) { mutableStateOf(def.ann) }
            var cat by remember(def.at) { mutableStateOf(def.cat) }
            var join by remember(def.at) { mutableStateOf(def.join) }
            var max by remember(def.at) { mutableStateOf(def.max) }
            OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = desc, onValueChange = { desc = it.take(240) }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            OutlinedTextField(value = ann, onValueChange = { ann = it.take(200) }, label = { Text("Pinned announcement") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            if (!def.isPrivate) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CATEGORIES.forEach { c -> FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text(c.replaceFirstChar { it.uppercase() }) }) }
                }
            }
            Segmented(listOf("Open", "Approval", "Closed"), join) { join = it }
            SliderRow("Member limit", max, maxOf(2, view.members.size), MAX_MEMBERS, 1, "") { max = it }
            Button(shape = MaterialTheme.shapes.small, onClick = {
                social.editGroup(gref) { it.copy(name = name, desc = desc, ann = ann, cat = cat, join = join, max = max) }
            }) { Text("Save changes") }
        }

        if (view.pending.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Caps("Requests (${view.pending.size})")
            view.pending.forEach { pub ->
                PersonRow(snap, pub) {
                    IconButton(onClick = { social.moderate(gref, pub, Acts.OK) }) { Icon(Ico.Check, "Approve", Modifier.size(20.dp)) }
                    IconButton(onClick = { social.moderate(gref, pub, Acts.KICK) }) { Icon(Ico.Close, "Decline", Modifier.size(20.dp)) }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Caps("People")
        view.members.filter { it != me }.forEach { pub ->
            var menu by remember { mutableStateOf(false) }
            val isMember = pub in def.mods
            val canAct = pub != def.founder && (isFounder || !isMember)
            PersonRow(snap, pub, sub = when { pub == def.founder -> "Owner"; isMember -> "Moderator"; pub in view.muted -> "Muted"; else -> null }) {
                if (canAct) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Ico.More, "Actions", Modifier.size(20.dp)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (pub in view.muted) "Unmute" else "Mute in chat") },
                                onClick = { menu = false; social.moderate(gref, pub, if (pub in view.muted) Acts.UNMUTE else Acts.MUTE) },
                            )
                            DropdownMenuItem(text = { Text("Remove from group") }, onClick = { menu = false; social.moderate(gref, pub, Acts.KICK) })
                            DropdownMenuItem(text = { Text("Ban") }, onClick = { menu = false; confirmBan = pub })
                            if (isFounder) {
                                DropdownMenuItem(
                                    text = { Text(if (isMember) "Remove moderator role" else "Make moderator") },
                                    onClick = {
                                        menu = false
                                        social.editGroup(gref) { d -> d.copy(mods = if (isMember) d.mods - pub else d.mods + pub) }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (view.members.size <= 1) Note("Nobody else is in the group yet.")

        if (view.banned.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Caps("Banned")
            view.banned.forEach { pub ->
                PersonRow(snap, pub) { TextButton(onClick = { social.moderate(gref, pub, Acts.UNBAN) }) { Text("Unban") } }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (isFounder) {
            TextButton(onClick = { confirmDelete = true }) { Text("Delete group") }
        }
    }

    confirmBan?.let { pub ->
        AlertDialog(
            onDismissRequest = { confirmBan = null },
            title = { Text("Ban ${nameOf(snap, pub)}?") },
            text = { Text("They are removed and cannot join again until you unban them.") },
            confirmButton = { TextButton(onClick = { social.moderate(gref, pub, Acts.BAN); confirmBan = null }) { Text("Ban") } },
            dismissButton = { TextButton(onClick = { confirmBan = null }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this group?") },
            text = { Text("It closes for every member. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; social.deleteGroup(gref) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PersonRow(snap: Snapshot, pub: String, sub: String? = null, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        UserAvatar(snap, pub, 36.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(nameOf(snap, pub), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}
