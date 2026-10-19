@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.studyflow.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studyflow.app.p2p.Period
import com.studyflow.app.p2p.ST_BANNED
import com.studyflow.app.p2p.ST_CLOSED
import com.studyflow.app.p2p.ST_FULL
import com.studyflow.app.p2p.ST_KICKED
import com.studyflow.app.p2p.ST_MEMBER
import com.studyflow.app.p2p.ST_PENDING
import com.studyflow.app.p2p.StatsIndex
import com.studyflow.app.p2p.secFor

@Composable
fun GroupTile(name: String, key: String, size: androidx.compose.ui.unit.Dp, locked: Boolean = false) {
    Box(
        Modifier.size(size).clip(MaterialTheme.shapes.small).background(Color(PALETTE[fallbackColor(key)])),
        contentAlignment = Alignment.Center,
    ) {
        if (locked) Icon(Ico.Lock, null, Modifier.size(size * 0.45f), tint = Color.White)
        else Text(name.trim().take(1).uppercase().ifEmpty { "#" }, color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun GroupsScreen(vm: MainVM, onOpen: (String) -> Unit, onFind: () -> Unit) {
    val social = vm.social
    val snap = social.snap
    val joined = social.joined
    val rule = MaterialTheme.colorScheme.outlineVariant
    val today = todayOf()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 96.dp),
        ) {
            item {
                ScreenHeader(
                    "Groups",
                    if (joined.isEmpty()) "Study together, compare time, chat." else "${joined.size} joined",
                    Modifier.padding(bottom = 16.dp),
                )
            }
            if (joined.isEmpty()) {
                item {
                    Note("You are not in any group yet. Tap the magnifier to find a group or create your own.", Modifier.padding(top = 8.dp))
                }
            }
            items(joined, key = { it.gref }) { jg ->
                val view = snap.groups[jg.gref]
                val def = view?.def
                val st = view?.statusOf(social.me) ?: -1
                val meta = when {
                    view == null -> "Looking for this group..."
                    st == ST_MEMBER -> {
                        val live = view.members.count { isLive(snap.live[it]) }
                        "${view.members.size}/${def!!.max} members" + if (live > 0) " · $live studying now" else ""
                    }
                    st == ST_PENDING -> "Waiting for approval"
                    st == ST_BANNED -> "You were banned"
                    st == ST_KICKED -> "You were removed"
                    st == ST_FULL -> "Group is full"
                    st == ST_CLOSED -> "Closed to new members"
                    else -> "Joining..."
                }
                val total = if (view != null && st == ST_MEMBER) {
                    view.members.sumOf { secFor(snap.stats[it] ?: StatsIndex.EMPTY, Period.DAY, today) }
                } else 0L
                Row(
                    Modifier
                        .fillMaxWidth()
                        .animateItemPlacement()
                        .topRule(rule)
                        .clickable { onOpen(jg.gref) }
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GroupTile(def?.name ?: "?", jg.gref, 44.dp, locked = def?.isPrivate == true)
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(def?.name ?: "Group", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    if (total > 0) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(fmtDur(total), style = MaterialTheme.typography.titleMedium)
                            Text("today", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Icon(Ico.ChevronRight, null, Modifier.padding(start = 8.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        IconFab(Ico.Search, "Find or create a group", Modifier.align(Alignment.BottomEnd), onFind)
    }
}
