package com.toolbox.multitool.network

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.toolbox.multitool.ui.LogBox
import com.toolbox.multitool.ui.ToolScaffold
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@Composable
fun NetworkScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var host by rememberSaveable { mutableStateOf("") }
    var ports by rememberSaveable { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }

    fun start(flow: (String) -> Flow<String>) {
        val h = host.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (h.isEmpty()) { output = "Enter a domain or IP address"; return }
        job?.cancel(); output = ""
        job = scope.launch {
            flow(h).catch { output += "\n✗ ${it.message ?: it}" }.collect { line ->
                // keep the log bounded so very long scans stay smooth
                output = (output + line + "\n").takeLast(40_000)
            }
            job = null
        }
    }

    ToolScaffold("Network toolkit", onBack) {
        OutlinedTextField(host, { host = it }, label = { Text("Domain or IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip({ start { NetworkEngine.ping(it) } }, { Text("Ping") })
            AssistChip({ start { NetworkEngine.trace(it) } }, { Text("Route") })
            AssistChip({ start { NetworkEngine.dns(it) } }, { Text("DNS") })
            AssistChip({ start { NetworkEngine.whois(it) } }, { Text("WHOIS") })
            AssistChip({ start { NetworkEngine.http(it) } }, { Text("HTTP/TLS") })
            AssistChip({ start { NetworkEngine.ipInfo(it) } }, { Text("IP owner") })
            AssistChip({ start { NetworkEngine.full(it) } }, { Text("Everything") })
        }
        OutlinedTextField(
            ports, { ports = it }, label = { Text("Ports (blank = common, e.g. 1-1024,8080)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { start { NetworkEngine.ports(it, NetworkEngine.parsePorts(ports)) } }) { Text("Scan ports") }
            if (job != null) OutlinedButton(onClick = { job?.cancel(); job = null }) { Text("Stop") }
        }
        if (job != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        LogBox(output)
        Text("Only scan hosts you own or have permission to test.", style = MaterialTheme.typography.bodySmall)
    }
}
