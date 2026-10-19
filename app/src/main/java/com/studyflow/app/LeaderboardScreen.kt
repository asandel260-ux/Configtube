@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.studyflow.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.studyflow.app.p2p.ST_MEMBER
import com.studyflow.app.p2p.StatsIndex
import com.studyflow.app.p2p.secFor

@Composable
fun LeaderboardScreen(vm: MainVM, onBack: () -> Unit, onProfile: (String) -> Unit, onFind: () -> Unit) {
    val social = vm.social
    val snap = social.snap
    val me = social.me
    val today = todayOf()
    var scope by rememberSaveable { mutableStateOf(0) }
    var period by rememberSaveable { mutableStateOf(1) }
    var groupSel by rememberSaveable { mutableStateOf("") }
    var refreshing by androidx.compose.runtime.remember { mutableStateOf(false) }
    val per = periodOf(period)

    fun refresh() {
        if (scope == 1) {
            refreshing = true
            social.refreshGlobal { refreshing = false }
        }
    }
    LaunchedEffect(scope) { refresh() }

    val myGroups = social.joined.mapNotNull { jg -> snap.groups[jg.gref]?.takeIf { it.statusOf(me) == ST_MEMBER } }

    val entriesRaw: List<RankEntry> = if (scope == 0) {
        val pubs = LinkedHashSet<String>()
        pubs.add(me)
        myGroups.filter { groupSel.isEmpty() || it.def.gref == groupSel }.forEach { pubs.addAll(it.members) }
        pubs.map { RankEntry(it, secFor(snap.stats[it] ?: StatsIndex.EMPTY, per, today)) }
    } else {
        val m = HashMap<String, Long>()
        for (b in snap.board) m[b.pub] = secFor(b, per, today)
        if (social.cfg.board && social.cfg.share) m[me] = secFor(snap.mine, per, today)
        m.map { RankEntry(it.key, it.value) }
    }
    val entries = entriesRaw.sortedWith(compareByDescending<RankEntry> { it.sec }.thenBy { it.pub })

    val myRank = entries.indexOfFirst { it.pub == me }
    val mySec = entries.firstOrNull { it.pub == me }?.sec ?: 0L

    Column(Modifier.fillMaxSize()) {
        TopBar("Leaderboard", onBack) {
            IconButton(onClick = { refresh() }, enabled = scope == 1 && !refreshing) {
                Icon(Ico.Refresh, "Refresh", Modifier.size(20.dp))
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Segmented(listOf("My groups", "Global"), scope) { scope = it }
                Segmented(PERIOD_LABELS, period) { period = it }
            }
            if (scope == 0 && myGroups.size > 1) {
                FlowRow(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = groupSel.isEmpty(), onClick = { groupSel = "" }, label = { Text("All") })
                    myGroups.forEach { g -> FilterChip(selected = groupSel == g.def.gref, onClick = { groupSel = g.def.gref }, label = { Text(g.def.name) }) }
                }
            }
            Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Caps("Your rank")
                    Text(if (myRank >= 0 && mySec > 0) "#${myRank + 1}" else "-", style = MaterialTheme.typography.displaySmall)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (mySec > 0) fmtDur(mySec) else "No time yet", style = MaterialTheme.typography.titleMedium)
                    Text("of ${entries.size} people", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (scope == 0 && myGroups.isEmpty()) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Note("Join or create a group to compete with friends. Groups are private to their members.")
                    TextButton(onClick = onFind) { Text("Find groups") }
                }
            }
            if (scope == 1 && !(social.cfg.board && social.cfg.share)) {
                ProfileGate(social) { need ->
                    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Note("Only people who opted in appear here. Joining shares your name, avatar and today/week/month totals with everyone using StudyFlow.")
                        Button(shape = MaterialTheme.shapes.small, onClick = { need { social.updateCfg { it.copy(board = true, share = true) } } }) {
                            Text("Join the global leaderboard")
                        }
                    }
                }
            }
            if (scope == 1 && entries.isEmpty() && !refreshing) {
                Note(
                    if (social.net.open == 0) "Offline. Connect to see the global board."
                    else "Nobody has joined the global board yet.",
                    Modifier.padding(horizontal = 24.dp),
                )
            }
            RankList(entries.take(100), snap, me, onProfile)
            Spacer(Modifier.height(12.dp))
            val n = social.net
            Note(
                if (n.open > 0) "Live through ${n.open} of ${n.total} relays." else "Offline. Showing what is stored on this phone.",
                Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}
