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
import com.toolbox.multitool.ui.ToolScaffold
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

@Composable
fun CleanerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    ToolScaffold("Cleaner", onBack, scroll = false) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(tab == 0, { tab = 0 }, { Text("Apps") })
            FilterChip(tab == 1, { tab = 1 }, { Text("Junk files") })
        }
        if (tab == 0) AppsTab(ctx) else JunkTab(ctx)
    }
}

@Composable
private fun ColumnScope.AppsTab(ctx: Context) {
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<AppSize>?>(null) }
    var access by remember { mutableStateOf(CleanerEngine.hasUsageAccess(ctx)) }
    var msg by remember { mutableStateOf("") }

    LaunchedEffect(access) {
        if (access) apps = withContext(Dispatchers.IO) { CleanerEngine.appSizes(ctx) }
    }
    if (!access) {
        Text("Per-app sizes need “Usage access”. Grant it for MultiTool, then come back.")
        Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open Usage access settings") }
        OutlinedButton(onClick = { access = CleanerEngine.hasUsageAccess(ctx) }) { Text("I've granted it") }
        return
    }
    val list = apps
    if (list == null) { CircularProgressIndicator(); return }
    Text("Total cache: ${Files.human(list.sumOf { it.cache })} across ${list.size} apps")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { msg = "Freed ${Files.human(CleanerEngine.clearOwnCache(ctx))} from MultiTool's own cache" }) { Text("Clear my cache") }
    }
    Text(
        "Android does not let regular apps clear other apps' caches. Tap an app to open its system storage page, where “Clear cache” is one tap.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (msg.isNotEmpty()) Text(msg)
    LazyColumn(Modifier.weight(1f)) {
        items(list, key = { it.pkg }) { a ->
            ListItem(
                headlineContent = { Text(a.label, maxLines = 1) },
                supportingContent = { Text("Cache ${Files.human(a.cache)} · Data ${Files.human(a.data)} · App ${Files.human(a.code)}") },
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
        Text("Scanning shared storage needs “All files access”.")
        Button(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Grant access") }
        OutlinedButton(onClick = { perm = CleanerEngine.hasAllFiles() }) { Text("I've granted it") }
        return
    }

    Button(enabled = !scanning, onClick = {
        scanning = true; result = ""; selected.clear()
        scope.launch {
            val r = withContext(Dispatchers.IO) { CleanerEngine.scan { current = it } }
            r.forEach { selected[it.file.absolutePath] = it.type != JunkType.Large && it.type != JunkType.Dupes } // risky kinds start unticked
            junk = r; scanning = false
        }
    }) { Text(if (scanning) "Scanning…" else "Scan storage") }
    if (scanning) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(current.takeLast(60), style = MaterialTheme.typography.bodySmall, maxLines = 1) }

    val list = junk
    if (list != null) {
        val chosen = list.filter { selected[it.file.absolutePath] == true }
        Text("Found ${Files.human(list.sumOf { it.size })} · selected ${Files.human(chosen.sumOf { it.size })}")
        Button(enabled = chosen.isNotEmpty(), onClick = {
            scope.launch {
                val freed = withContext(Dispatchers.IO) {
                    var f = 0L
                    chosen.forEach { j -> if (j.file.deleteRecursively()) f += j.size }
                    f
                }
                result = "Deleted ${Files.human(freed)}"
                junk = list.filterNot { j -> chosen.any { it.file == j.file } }
            }
        }) { Text("Delete selected") }
        if (result.isNotEmpty()) Text(result)
        LazyColumn(Modifier.weight(1f)) {
            JunkType.entries.forEach { t ->
                val items = list.filter { it.type == t }.sortedByDescending { it.size }
                if (items.isNotEmpty()) {
                    item(key = "h_$t") {
                        Text("${t.label} · ${Files.human(items.sumOf { it.size })}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                    }
                    items(items, key = { it.file.absolutePath + it.type }) { j ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(selected[j.file.absolutePath] == true, { selected[j.file.absolutePath] = it })
                            Column(Modifier.weight(1f)) {
                                Text(j.file.name, maxLines = 1)
                                Text(j.file.parent?.removePrefix(Environment.getExternalStorageDirectory().path).orEmpty() + " · " + Files.human(j.size), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
