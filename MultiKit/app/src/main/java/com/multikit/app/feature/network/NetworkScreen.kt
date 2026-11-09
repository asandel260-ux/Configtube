package com.multikit.app.feature.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.multikit.app.ui.components.BusyOverlay
import com.multikit.app.ui.components.ErrorBanner
import com.multikit.app.ui.components.LabeledField
import com.multikit.app.ui.components.OutputBlock
import com.multikit.app.ui.components.SectionCard
import com.multikit.app.ui.components.SuccessBanner
import com.multikit.app.ui.components.ToolScaffold
import com.multikit.app.ui.components.rememberTaskState
import com.multikit.app.util.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val TABS = listOf("Overview", "Ping", "Traceroute", "Ports", "DNS", "WHOIS", "HTTP/TLS")
private val DNS_TYPES = listOf("ALL", "A", "AAAA", "MX", "NS", "TXT", "CNAME", "SOA", "CAA", "PTR", "SRV")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val task = rememberTaskState()
    var job by remember { mutableStateOf<Job?>(null) }

    var target by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val outputs = remember { List(TABS.size) { mutableStateOf("") } }

    // Per-tab options (kept at screen level so they survive tab switches).
    var pingCount by rememberSaveable { mutableStateOf("4") }
    var maxHops by rememberSaveable { mutableStateOf("20") }
    var preset by rememberSaveable { mutableIntStateOf(0) } // PRESETS index; == size means Custom
    var rangeStart by rememberSaveable { mutableStateOf("1") }
    var rangeEnd by rememberSaveable { mutableStateOf("1024") }
    var portTimeout by rememberSaveable { mutableStateOf("700") }
    var dnsType by rememberSaveable { mutableStateOf("ALL") }
    var url by rememberSaveable { mutableStateOf("") }

    fun validTarget(): String? {
        val t = target.trim()
        if (t.isEmpty()) {
            task.error = "Enter a domain or IP address first."
            return null
        }
        return t
    }

    /** Runs [block] on the screen scope; engine code hops to IO and emits on Main. */
    fun runTask(idx: Int, block: suspend (emit: (String) -> Unit) -> Unit) {
        job?.cancel()
        outputs[idx].value = ""
        task.busy = true
        task.error = null
        task.message = null
        task.progress = null
        job = scope.launch {
            try {
                block { text -> outputs[idx].value += text }
            } catch (ce: CancellationException) {
                task.message = "Cancelled."
            } catch (t: Throwable) {
                task.error = t.message ?: t.javaClass.simpleName
            } finally {
                task.busy = false
                task.progress = null
            }
        }
    }

    ToolScaffold(title = "Network Analyzer", onBack = onBack) {
        LabeledField(
            label = "Target (domain or IP)",
            value = target,
            onValueChange = { target = it },
            trailing = { Icon(Icons.Filled.Search, contentDescription = null) },
        )

        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            TABS.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title, maxLines = 1) })
            }
        }

        when (tab) {
            0 -> SectionCard("Overview") {
                Text(
                    "DNS resolution, reverse DNS, public IP, active network and device interfaces.",
                    style = MaterialTheme.typography.bodySmall,
                )
                RunButton(enabled = !task.busy) {
                    val t = validTarget() ?: return@RunButton
                    runTask(0) { emit -> NetworkEngine.overview(context, t, emit) }
                }
            }

            1 -> SectionCard("Ping") {
                LabeledField(
                    label = "Count",
                    value = pingCount,
                    onValueChange = { pingCount = it.filter(Char::isDigit).take(3) },
                    keyboardType = KeyboardType.Number,
                )
                RunButton(enabled = !task.busy) {
                    val t = validTarget() ?: return@RunButton
                    val count = pingCount.toIntOrNull() ?: 4
                    runTask(1) { emit -> NetworkEngine.ping(t, count, emit) }
                }
            }

            2 -> SectionCard("Traceroute") {
                LabeledField(
                    label = "Max hops",
                    value = maxHops,
                    onValueChange = { maxHops = it.filter(Char::isDigit).take(2) },
                    keyboardType = KeyboardType.Number,
                )
                Text(
                    "Uses the system traceroute when available, otherwise a ping TTL sweep.",
                    style = MaterialTheme.typography.bodySmall,
                )
                RunButton(enabled = !task.busy) {
                    val t = validTarget() ?: return@RunButton
                    val hops = maxHops.toIntOrNull() ?: 20
                    runTask(2) { emit -> NetworkEngine.traceroute(t, hops, emit) }
                }
            }

            3 -> SectionCard("Port scan") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NetworkEngine.PRESETS.forEachIndexed { i, p ->
                        FilterChip(selected = preset == i, onClick = { preset = i }, label = { Text(p.name) })
                    }
                    FilterChip(
                        selected = preset == NetworkEngine.PRESETS.size,
                        onClick = { preset = NetworkEngine.PRESETS.size },
                        label = { Text("Custom") },
                    )
                }
                if (preset == NetworkEngine.PRESETS.size) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledField(
                            label = "Start port",
                            value = rangeStart,
                            onValueChange = { rangeStart = it.filter(Char::isDigit).take(5) },
                            modifier = Modifier.weight(1f),
                            keyboardType = KeyboardType.Number,
                        )
                        LabeledField(
                            label = "End port",
                            value = rangeEnd,
                            onValueChange = { rangeEnd = it.filter(Char::isDigit).take(5) },
                            modifier = Modifier.weight(1f),
                            keyboardType = KeyboardType.Number,
                        )
                    }
                    Text("Custom ranges are capped at 1024 ports.", style = MaterialTheme.typography.bodySmall)
                }
                LabeledField(
                    label = "Connect timeout (ms)",
                    value = portTimeout,
                    onValueChange = { portTimeout = it.filter(Char::isDigit).take(5) },
                    keyboardType = KeyboardType.Number,
                )
                RunButton(enabled = !task.busy) {
                    val t = validTarget() ?: return@RunButton
                    val ports: List<Int> = if (preset < NetworkEngine.PRESETS.size) {
                        NetworkEngine.PRESETS[preset].ports
                    } else {
                        val s = rangeStart.toIntOrNull() ?: 0
                        val e = rangeEnd.toIntOrNull() ?: 0
                        if (s !in 1..65535 || e !in 1..65535 || e < s) {
                            task.error = "Invalid port range (1-65535, start <= end)."
                            return@RunButton
                        }
                        (s..e).toList()
                    }
                    val timeout = portTimeout.toIntOrNull() ?: 700
                    runTask(3) { emit ->
                        val capped = ports.take(1024)
                        if (capped.size < ports.size) {
                            emit("Note: range capped at 1024 ports (${ports.size} requested).\n\n")
                        }
                        NetworkEngine.scanPorts(t, capped, timeout, emit) { p -> task.progress = p }
                    }
                }
            }

            4 -> SectionCard("DNS lookup") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DNS_TYPES.forEach { type ->
                        FilterChip(selected = dnsType == type, onClick = { dnsType = type }, label = { Text(type) })
                    }
                }
                Text(
                    "PTR expects an IP target. SRV queries _sip._tcp.<target> unless the target already starts with '_'.",
                    style = MaterialTheme.typography.bodySmall,
                )
                RunButton(enabled = !task.busy) {
                    val t = validTarget() ?: return@RunButton
                    runTask(4) { emit -> NetworkEngine.dns(t, dnsType, emit) }
                }
            }

            5 -> SectionCard("WHOIS") {
                Text(
                    "Queries whois.iana.org and follows up to 2 referrals (works for domains and IPs).",
                    style = MaterialTheme.typography.bodySmall,
                )
                RunButton(enabled = !task.busy) {
                    val t = validTarget() ?: return@RunButton
                    runTask(5) { emit -> NetworkEngine.whois(t, emit) }
                }
            }

            6 -> SectionCard("HTTP / TLS") {
                LabeledField(
                    label = "URL (blank = https://target)",
                    value = url,
                    onValueChange = { url = it },
                )
                RunButton(enabled = !task.busy) {
                    val t = validTarget() ?: return@RunButton
                    var effective = url.trim().ifBlank { "https://$t" }
                    if (!effective.startsWith("http://") && !effective.startsWith("https://")) {
                        effective = "https://$effective"
                    }
                    val finalUrl = effective
                    runTask(6) { emit -> NetworkEngine.httpTls(finalUrl, emit) }
                }
            }
        }

        ErrorBanner(task.error)
        SuccessBanner(task.message)

        if (task.busy) {
            OutlinedButton(onClick = { job?.cancel() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Stop", modifier = Modifier.padding(start = 4.dp))
            }
        }

        val output = outputs[tab].value
        if (output.isNotBlank()) {
            OutputBlock(output)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        Files.copyToClipboard(context, "Network result", output)
                        task.message = "Result copied to clipboard."
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Copy", modifier = Modifier.padding(start = 4.dp))
                }
                OutlinedButton(onClick = { outputs[tab].value = "" }, modifier = Modifier.weight(1f)) {
                    Text("Clear")
                }
            }
        }

        BusyOverlay(busy = task.busy, label = "Analyzing…", progress = task.progress)
    }
}

@Composable
private fun RunButton(enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
        Text("  Run", modifier = Modifier.padding(start = 4.dp))
    }
}