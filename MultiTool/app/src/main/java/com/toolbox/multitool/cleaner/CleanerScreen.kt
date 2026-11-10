package com.toolbox.multitool.cleaner

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.graphics.drawable.toBitmap
import com.toolbox.multitool.ui.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.toolbox.multitool.core.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class AppSize(val label: String, val pkg: String, val cache: Long, val data: Long, val code: Long)
enum class JunkType(val label: String) {
    Temp("Temp & log files"), Thumbs("Thumbnail caches"), Apk("Leftover APK files"),
    Empty("Empty folders"), Large("Large files (>100 MB)"), Dupes("Likely duplicates"),
}
data class Junk(val file: File, val type: JunkType, val size: Long)

object CleanerEngine {
    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }

    fun hasAllFiles() = Environment.isExternalStorageManager()

    /** Per-app cache / data / code sizes via StorageStatsManager (needs Usage access). */
    fun appSizes(ctx: Context): List<AppSize> {
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)
        val pm = ctx.packageManager
        return pm.getInstalledApplications(0).mapNotNull { ai ->
            runCatching {
                val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, Process.myUserHandle())
                AppSize(ai.loadLabel(pm).toString(), ai.packageName, s.cacheBytes, s.dataBytes - s.cacheBytes, s.appBytes)
            }.getOrNull()
        }.sortedByDescending { it.cache }
    }

    /** Cache of THIS app is the only one Android lets a normal app clear. */
    fun clearOwnCache(ctx: Context): Long {
        var freed = 0L
        listOfNotNull(ctx.cacheDir, ctx.externalCacheDir).forEach { d ->
            freed += d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            d.listFiles()?.forEach { it.deleteRecursively() }
        }
        return freed
    }

    private val tempExt = setOf("tmp", "temp", "log", "bak", "old", "cache", "crdownload", "part", "dmp", "chk")

    fun scan(onProgress: (String) -> Unit): List<Junk> {
        val root = Environment.getExternalStorageDirectory()
        val out = ArrayList<Junk>()
        val bySize = HashMap<Long, MutableList<File>>()
        fun walk(dir: File, depth: Int) {
            val children = dir.listFiles() ?: return
            if (children.isEmpty() && depth > 0 && !dir.name.startsWith(".")) { out += Junk(dir, JunkType.Empty, 0); return }
            for (f in children) {
                if (f.isDirectory) {
                    if (depth == 0 && f.name == "Android") continue // app-private, not accessible on Android 11+
                    if (f.name == ".thumbnails" || f.name == "thumbnails" && f.parentFile?.name == "DCIM") {
                        out += Junk(f, JunkType.Thumbs, f.walkTopDown().filter { it.isFile }.sumOf { it.length() }); continue
                    }
                    onProgress(f.absolutePath)
                    walk(f, depth + 1)
                } else {
                    val ext = f.extension.lowercase(); val len = f.length()
                    when {
                        ext in tempExt || f.name.startsWith(".thumbdata") -> out += Junk(f, JunkType.Temp, len)
                        ext == "apk" -> out += Junk(f, JunkType.Apk, len)
                        len > 100L * 1024 * 1024 -> out += Junk(f, JunkType.Large, len)
                    }
                    if (len > 1024 * 1024) bySize.getOrPut(len) { mutableListOf() }.add(f)
                }
            }
        }
        walk(root, 0)
        // Duplicates: same size, then same hash of the first 256 KB, then same hash of the last 256 KB.
        bySize.values.filter { it.size > 1 }.forEach { group ->
            group.groupBy { sample(it, head = true) + sample(it, head = false) }.values.filter { it.size > 1 }.forEach { same ->
                same.drop(1).forEach { out += Junk(it, JunkType.Dupes, it.length()) }
            }
        }
        return out.distinctBy { it.file.absolutePath to it.type }
    }

    private fun sample(f: File, head: Boolean): String = runCatching {
        java.io.RandomAccessFile(f, "r").use { r ->
            val n = minOf(256 * 1024L, r.length()).toInt()
            r.seek(if (head) 0 else r.length() - n)
            val b = ByteArray(n); r.readFully(b)
            MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }
        }
    }.getOrDefault("")
}

private enum class SortBy(val label: String) { Cache("Cache"), Data("Data"), App("App size"), Name("Name") }

@Composable
fun CleanerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    ToolScaffold("Cleaner", onBack, scroll = false) {
        StorageCard()
        ChipRow {
            FilterChip(tab == 0, { tab = 0 }, { Text("Apps") }, leadingIcon = { Icon(Icons.Filled.Apps, null, Modifier.size(18.dp)) })
            FilterChip(tab == 1, { tab = 1 }, { Text("Junk files") }, leadingIcon = { Icon(Icons.Filled.CleaningServices, null, Modifier.size(18.dp)) })
        }
        if (tab == 0) AppsTab(ctx) else JunkTab(ctx)
    }
}

@Composable
private fun StorageCard() {
    val root = remember { Environment.getExternalStorageDirectory() }
    val total = root.totalSpace; val free = root.usableSpace; val used = (total - free).coerceAtLeast(0)
    SectionCard("Device storage") {
        LinearProgressIndicator(
            progress = { if (total > 0) used.toFloat() / total else 0f },
            modifier = Modifier.fillMaxWidth().height(10.dp), strokeCap = StrokeCap.Round,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${Files.human(used)} used", style = MaterialTheme.typography.labelLarge)
            Text("${Files.human(free)} free of ${Files.human(total)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ColumnScope.AppsTab(ctx: Context) {
    var apps by remember { mutableStateOf<List<AppSize>?>(null) }
    var access by remember { mutableStateOf(CleanerEngine.hasUsageAccess(ctx)) }
    var msg by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortBy.Cache) }

    LaunchedEffect(access) { if (access) apps = withContext(Dispatchers.IO) { CleanerEngine.appSizes(ctx) } }
    if (!access) {
        EmptyState(Icons.Filled.Security, "Usage access needed", "Android only shows per-app storage to apps you grant “Usage access”. Nothing leaves your phone.")
        PrimaryButton("Open settings", { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, icon = Icons.Filled.Settings)
        SecondaryButton("I've granted it", { access = CleanerEngine.hasUsageAccess(ctx) }, icon = Icons.Filled.Check)
        return
    }
    val raw = apps
    if (raw == null) { CircularProgressIndicator(); return }
    val list = when (sort) {
        SortBy.Cache -> raw.sortedByDescending { it.cache }
        SortBy.Data -> raw.sortedByDescending { it.data }
        SortBy.App -> raw.sortedByDescending { it.code }
        SortBy.Name -> raw.sortedBy { it.label.lowercase() }
    }
    val maxCache = (raw.maxOfOrNull { it.cache } ?: 1L).coerceAtLeast(1L)

    ChipRow { Pill("Cache ${Files.human(raw.sumOf { it.cache })}", icon = Icons.Filled.Memory); Pill("${raw.size} apps", icon = Icons.Filled.Apps) }
    ChipRow { SortBy.entries.forEach { s -> FilterChip(sort == s, { sort = s }, { Text(s.label) }) } }
    TonalButton("Clear MultiTool's own cache", { msg = "Freed ${Files.human(CleanerEngine.clearOwnCache(ctx))}" }, icon = Icons.Filled.DeleteSweep)
    if (msg.isNotEmpty()) Pill(msg, icon = Icons.Filled.CheckCircle)
    Text(
        "Android does not let regular apps clear other apps' caches. Tap an app to open its storage page, where “Clear cache” is one tap.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LazyColumn(Modifier.weight(1f)) {
        items(list, key = { it.pkg }) { a ->
            val icon = remember(a.pkg) { runCatching { ctx.packageManager.getApplicationIcon(a.pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
            ListItem(
                leadingContent = { if (icon != null) Image(icon, null, Modifier.size(40.dp).clip(CircleShape)) else Icon(Icons.Filled.Android, null, Modifier.size(40.dp)) },
                headlineContent = { Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Data ${Files.human(a.data)} · App ${Files.human(a.code)}", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { a.cache.toFloat() / maxCache }, modifier = Modifier.fillMaxWidth().height(4.dp), strokeCap = StrokeCap.Round)
                    }
                },
                trailingContent = { Text(Files.human(a.cache), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
            )
        }
    }
}

@Composable
private fun ColumnScope.JunkTab(ctx: Context) {
    val scope = rememberCoroutineScope()
    var perm by remember { mutableStateOf(CleanerEngine.hasAllFiles()) }
    var scanning by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("") }
    var junk by remember { mutableStateOf<List<Junk>?>(null) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var result by remember { mutableStateOf("") }

    if (!perm) {
        EmptyState(Icons.Filled.FolderSpecial, "All files access needed", "To find junk in shared storage, Android requires the “All files access” permission.")
        PrimaryButton("Grant access", {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, icon = Icons.Filled.Security)
        SecondaryButton("I've granted it", { perm = CleanerEngine.hasAllFiles() }, icon = Icons.Filled.Check)
        return
    }

    PrimaryButton(if (scanning) "Scanning…" else "Scan storage", icon = Icons.Filled.Search, loading = scanning, onClick = {
        scanning = true; result = ""; selected.clear()
        scope.launch {
            val r = withContext(Dispatchers.IO) { CleanerEngine.scan { current = it } }
            r.forEach { selected[it.file.absolutePath] = it.type != JunkType.Large && it.type != JunkType.Dupes } // risky kinds start unticked
            junk = r; scanning = false
        }
    })
    if (scanning) Text(current.takeLast(60), style = MaterialTheme.typography.bodySmall, maxLines = 1)

    val list = junk
    if (list != null) {
        val chosen = list.filter { selected[it.file.absolutePath] == true }
        ChipRow { Pill("Found ${Files.human(list.sumOf { it.size })}", icon = Icons.Filled.Search); Pill("Selected ${Files.human(chosen.sumOf { it.size })}", icon = Icons.Filled.CheckCircle) }
        PrimaryButton("Delete selected", icon = Icons.Filled.Delete, enabled = chosen.isNotEmpty(), onClick = {
            scope.launch {
                val freed = withContext(Dispatchers.IO) {
                    var f = 0L
                    chosen.forEach { j -> if (j.file.deleteRecursively()) f += j.size }
                    f
                }
                result = "Deleted ${Files.human(freed)}"
                junk = list.filterNot { j -> chosen.any { it.file == j.file } }
            }
        })
        if (result.isNotEmpty()) Pill(result, icon = Icons.Filled.CheckCircle)
        LazyColumn(Modifier.weight(1f)) {
            JunkType.entries.forEach { t ->
                val items = list.filter { it.type == t }.sortedByDescending { it.size }
                if (items.isNotEmpty()) {
                    item(key = "h_$t") {
                        val allOn = items.all { selected[it.file.absolutePath] == true }
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                Text("${items.size} items · ${Files.human(items.sumOf { it.size })}", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { items.forEach { selected[it.file.absolutePath] = !allOn } }) { Text(if (allOn) "Select none" else "Select all") }
                        }
                    }
                    items(items, key = { it.file.absolutePath + it.type }) { j ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(selected[j.file.absolutePath] == true, { selected[j.file.absolutePath] = it })
                            Column(Modifier.weight(1f)) {
                                Text(j.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(j.file.parent?.removePrefix(Environment.getExternalStorageDirectory().path).orEmpty() + " · " + Files.human(j.size), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
