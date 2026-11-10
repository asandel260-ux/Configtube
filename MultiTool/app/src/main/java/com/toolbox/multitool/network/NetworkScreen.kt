package com.toolbox.multitool.network

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.toolbox.multitool.ui.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

private val portPresets = listOf(
    "Common" to "", "Web" to "80,443,8080,8443", "1–1024" to "1-1024",
    "Databases" to "1433,3306,5432,6379,27017", "Remote" to "22,23,3389,5900",
)

@Composable
fun NetworkScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by rememberSaveable { mutableStateOf("") }
    var portSpec by rememberSaveable { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf("") }

    fun start(label: String, flow: (String) -> Flow<String>) {
        val h = host.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (h.isEmpty()) { output = "Enter a domain or IP address first."; return }
        job?.cancel(); output = ""; running = label
        job = scope.launch {
            flow(h).catch { output += "\n✗ ${it.message ?: it}" }.collect { line ->
                output = (output + line + "\n").takeLast(40_000) // bounded so long scans stay smooth
            }
            job = null; running = ""
        }
    }

    ToolScaffold("Network toolkit", onBack) {
        SectionCard("Target") {
            OutlinedTextField(
                host, { host = it }, label = { Text("Domain or IP address") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Filled.Language, null) },
                trailingIcon = {
                    Row {
                        IconButton({ host = readClipboard(ctx).trim() }) { Icon(Icons.Filled.ContentPaste, "Paste") }
                        if (host.isNotEmpty()) IconButton({ host = "" }) { Icon(Icons.Filled.Close, "Clear") }
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolTile(Icons.Filled.NetworkPing, "Ping", { start("Ping") { NetworkEngine.ping(it) } }, Modifier.weight(1f), job == null)
                ToolTile(Icons.Filled.Route, "Route", { start("Route") { NetworkEngine.trace(it) } }, Modifier.weight(1f), job == null)
                ToolTile(Icons.Filled.Dns, "DNS", { start("DNS") { NetworkEngine.dns(it) } }, Modifier.weight(1f), job == null)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolTile(Icons.Filled.Info, "WHOIS", { start("WHOIS") { NetworkEngine.whois(it) } }, Modifier.weight(1f), job == null)
                ToolTile(Icons.Filled.Https, "HTTP/TLS", { start("HTTP/TLS") { NetworkEngine.http(it) } }, Modifier.weight(1f), job == null)
                ToolTile(Icons.Filled.Public, "IP owner", { start("IP owner") { NetworkEngine.ipInfo(it) } }, Modifier.weight(1f), job == null)
            }
            PrimaryButton("Run full analysis", { start("Full analysis") { NetworkEngine.full(it) } }, icon = Icons.Filled.Radar, enabled = job == null)
        }

        SectionCard("Port scan") {
            ChipRow { portPresets.forEach { (label, spec) -> FilterChip(portSpec == spec, { portSpec = spec }, { Text(label) }) } }
            OutlinedTextField(
                portSpec, { portSpec = it }, label = { Text("Ports (blank = common, e.g. 1-1024,8080)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            TonalButton("Scan ports", { start("Port scan") { NetworkEngine.ports(it, NetworkEngine.parsePorts(portSpec)) } }, icon = Icons.Filled.Search, enabled = job == null)
        }

        if (job != null) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text("Running $running…", Modifier.weight(1f))
                    FilledTonalButton(onClick = { job?.cancel(); job = null; running = "" }) { Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop") }
                }
            }
        }
        LogBox(output)
        Text("Only scan hosts you own or have permission to test.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
