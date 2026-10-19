@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.studyflow.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studyflow.app.p2p.CATEGORIES
import com.studyflow.app.p2p.GroupDef
import com.studyflow.app.p2p.InviteCodec
import com.studyflow.app.p2p.JOIN_APPROVAL
import com.studyflow.app.p2p.JOIN_OPEN
import com.studyflow.app.p2p.MAX_MEMBERS
import com.studyflow.app.p2p.VIS_PRIVATE
import com.studyflow.app.p2p.VIS_PUBLIC
import com.studyflow.app.p2p.Invite
import kotlinx.coroutines.delay

@Composable
fun GroupFinderScreen(vm: MainVM, onBack: () -> Unit, onOpen: (String) -> Unit, onCreated: (String) -> Unit) {
    val social = vm.social
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Find or create a group", onBack)
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Segmented(listOf("Browse", "Invite code", "Create"), tab) { tab = it }
        }
        when (tab) {
            0 -> BrowseTab(vm, onOpen)
            1 -> CodeTab(vm, onOpen)
            else -> CreateTab(vm, onCreated)
        }
    }
}

@Composable
private fun BrowseTab(vm: MainVM, onOpen: (String) -> Unit) {
    val social = vm.social
    var query by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf("all") }
    LaunchedEffect(query, cat) {
        delay(450)
        social.searchDirectory(query, cat)
    }
    val dir = social.directory
    val rule = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it.take(40) },
            label = { Text("Search by name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf("all") + CATEGORIES).forEach { c ->
                FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text(c.replaceFirstChar { it.uppercase() }) })
            }
        }
        if (dir.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp).height(2.dp))
        else Column(Modifier.height(14.dp)) {}
        if (!dir.loading && dir.items.isEmpty()) {
            Note(
                if (social.net.open == 0) "You are offline, so the public directory can't be reached."
                else "No public groups match. Be the first: open the Create tab.",
                Modifier.padding(top = 8.dp),
            )
        }
        dir.items.forEach { d ->
            DirRow(d, rule) {
                social.peek(Invite(d.gref, null, d.relays))
                onOpen(d.gref)
            }
        }
    }
}

@Composable
private fun DirRow(d: GroupDef, rule: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().topRule(rule).clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupTile(d.name, d.gref, 40.dp)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (d.desc.isNotBlank()) {
                Text(d.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                d.cat + " · " + when (d.join) { JOIN_OPEN -> "open"; JOIN_APPROVAL -> "approval needed"; else -> "closed" } + " · max ${d.max}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Ico.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CodeTab(vm: MainVM, onOpen: (String) -> Unit) {
    val social = vm.social
    var text by rememberSaveable { mutableStateOf("") }
    var err by rememberSaveable { mutableStateOf(false) }
    val clip = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Note("Paste an invite link or code that a member sent you. Private groups can only be joined this way.")
        OutlinedTextField(
            value = text, onValueChange = { text = it; err = false },
            label = { Text("Invite link or code") }, modifier = Modifier.fillMaxWidth(), maxLines = 3, isError = err,
            supportingText = { if (err) Text("That doesn't look like a StudyFlow invite.") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clip.getText()?.let { text = it.text; err = false } }, shape = MaterialTheme.shapes.small) { Text("Paste") }
            Button(
                shape = MaterialTheme.shapes.small,
                onClick = {
                    val inv = InviteCodec.parse(text)
                    if (inv == null) err = true else {
                        social.peek(inv)
                        onOpen(inv.gref)
                    }
                },
            ) { Text("Find group") }
        }
    }
}

@Composable
private fun CreateTab(vm: MainVM, onCreated: (String) -> Unit) {
    val social = vm.social
    var name by rememberSaveable { mutableStateOf("") }
    var desc by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf("general") }
    var vis by rememberSaveable { mutableStateOf(VIS_PUBLIC) }
    var join by rememberSaveable { mutableStateOf(JOIN_OPEN) }
    var max by rememberSaveable { mutableStateOf(MAX_MEMBERS) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Group name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = desc, onValueChange = { desc = it.take(240) }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
        Caps("Category")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CATEGORIES.forEach { c -> FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text(c.replaceFirstChar { it.uppercase() }) }) }
        }
        Caps("Visibility")
        Segmented(listOf("Public", "Private"), vis) { vis = it }
        Note(
            if (vis == VIS_PUBLIC) "Listed in the directory. Anyone can find it and read its chat."
            else "Not listed. Only people with the invite link can join, and chat is encrypted for members."
        )
        Caps("Who can join")
        Segmented(listOf("Open", "Approval"), join) { join = it }
        Note(if (join == JOIN_OPEN) "Anyone who finds the group can join, up to the member limit." else "You or a moderator must approve each person.")
        SliderRow("Member limit", max, 2, MAX_MEMBERS, 1, "") { max = it }
        ProfileGate(social) { need ->
            Button(
                enabled = name.trim().length >= 2,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = {
                    need {
                        val inv = social.createGroup(name, desc, cat, vis, join, max)
                        onCreated(inv.gref)
                    }
                },
            ) { Text("Create group") }
        }
        Note("You become the owner and can moderate: remove or ban people, mute, add moderators, approve requests, edit or delete the group.", Modifier.padding(bottom = 24.dp))
    }
}
