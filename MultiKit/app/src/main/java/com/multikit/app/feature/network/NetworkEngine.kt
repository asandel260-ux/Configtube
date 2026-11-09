package com.multikit.app.feature.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.multikit.app.util.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.xbill.DNS.Lookup
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.Type
import org.xbill.DNS.config.ResolverConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.net.UnknownHostException
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

/**
 * Blocking network logic for the Network Analyzer. Every public entry point runs on
 * Dispatchers.IO and publishes progress/output through [publish], which always hops
 * to the Main dispatcher so Compose state is only ever touched from the UI thread.
 */
internal object NetworkEngine {

    internal data class PortPreset(val name: String, val ports: List<Int>)

    internal val PRESETS: List<PortPreset> = listOf(
        PortPreset("Web", listOf(80, 443, 8080, 8443)),
        PortPreset("Mail", listOf(25, 465, 587, 110, 143, 993, 995)),
        PortPreset("Remote", listOf(22, 23, 3389, 5900)),
        PortPreset("DB", listOf(1433, 1521, 3306, 5432, 6379, 27017)),
        PortPreset(
            "Common",
            listOf(
                20, 21, 22, 23, 25, 53, 80, 110, 111, 135, 139, 143, 443, 445, 465,
                587, 993, 995, 1433, 1521, 1723, 2049, 3306, 3389, 5432, 5900, 6379,
                8080, 8443, 27017,
            ),
        ),
    )

    private val SERVICE_NAMES: Map<Int, String> = mapOf(
        20 to "ftp-data", 21 to "ftp", 22 to "ssh", 23 to "telnet", 25 to "smtp",
        53 to "dns", 67 to "dhcp", 68 to "dhcp", 80 to "http", 110 to "pop3",
        111 to "rpcbind", 123 to "ntp", 135 to "msrpc", 139 to "netbios-ssn",
        143 to "imap", 161 to "snmp", 389 to "ldap", 443 to "https", 445 to "microsoft-ds",
        465 to "smtps", 514 to "syslog", 587 to "submission", 636 to "ldaps",
        873 to "rsync", 993 to "imaps", 995 to "pop3s", 1080 to "socks",
        1433 to "mssql", 1521 to "oracle", 1723 to "pptp", 2049 to "nfs",
        3306 to "mysql", 3389 to "rdp", 5432 to "postgresql", 5900 to "vnc",
        6379 to "redis", 8080 to "http-alt", 8443 to "https-alt", 27017 to "mongodb",
    )

    private data class ProcResult(val exitCode: Int, val output: String)

    // ---------------------------------------------------------------- helpers

    private suspend fun publish(emit: (String) -> Unit, text: String) {
        withContext(Dispatchers.Main) { emit(text) }
    }

    /** Runs a process, streaming stdout+stderr live (when [emit] != null). Destroys it on exit/cancel. */
    private suspend fun runProcess(cmd: List<String>, timeoutSec: Long, emit: ((String) -> Unit)?): ProcResult =
        withContext(Dispatchers.IO) {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val sb = StringBuilder()
            try {
                val reader = proc.inputStream.bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    currentCoroutineContext().ensureActive()
                    sb.appendLine(line)
                    if (emit != null) publish(emit, line + "\n")
                }
                val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    ProcResult(-1, sb.toString())
                } else {
                    ProcResult(proc.exitValue(), sb.toString())
                }
            } finally {
                proc.destroyForcibly()
            }
        }

    private fun isIpLiteral(s: String): Boolean =
        s.matches(Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")) ||
            (s.contains(':') && s.matches(Regex("^[0-9a-fA-F:]+$")))

    private fun extractIp(text: String): String? {
        Regex("\\(([^)]+)\\)").find(text)?.let { return it.groupValues[1] }
        return Regex("(\\d{1,3}\\.){3}\\d{1,3}").find(text)?.value
            ?: Regex("[0-9a-fA-F]{0,4}(:[0-9a-fA-F]{0,4}){2,}").find(text)?.value
    }

    private fun reverseNameOf(ip: String): String? =
        runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull()?.takeIf { it != ip }

    // --------------------------------------------------------------- overview

    suspend fun overview(context: Context, target: String, emit: (String) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            publish(emit, "── Resolution ──\n")
            try {
                val t0 = System.nanoTime()
                val addrs = InetAddress.getAllByName(target)
                val ms = (System.nanoTime() - t0) / 1_000_000
                publish(emit, "$target resolved in $ms ms (${addrs.size} address(es))\n")
                for (a in addrs) {
                    val kind = if (a is Inet4Address) "A   " else "AAAA"
                    publish(emit, "  $kind  ${a.hostAddress}\n")
                }
                currentCoroutineContext().ensureActive()
                publish(emit, "\n── Reverse DNS ──\n")
                for (a in addrs.take(8)) {
                    val canonical = runCatching { a.canonicalHostName }.getOrDefault(a.hostAddress)
                    publish(emit, "  ${a.hostAddress} → $canonical\n")
                }
            } catch (e: UnknownHostException) {
                publish(emit, "Resolution failed: ${e.message}\n")
            }

            currentCoroutineContext().ensureActive()
            publish(emit, "\n── Public IP ──\n")
            val publicIp = runCatching {
                val c = URL("https://api.ipify.org").openConnection() as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 5000
                try {
                    c.inputStream.bufferedReader().use { it.readText().trim() }
                } finally {
                    c.disconnect()
                }
            }.getOrElse { "unavailable (${it.message ?: it.javaClass.simpleName})" }
            publish(emit, "  $publicIp\n")

            currentCoroutineContext().ensureActive()
            publish(emit, "\n── Active network ──\n")
            runCatching {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val net = cm.activeNetwork
                val caps = net?.let { cm.getNetworkCapabilities(it) }
                if (caps == null) {
                    publish(emit, "  No active network\n")
                } else {
                    val transport = when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                        else -> "Other"
                    }
                    val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    publish(emit, "  Transport: $transport\n")
                    publish(emit, "  Metered: ${if (metered) "yes" else "no"}\n")
                    publish(emit, "  Internet validated: ${if (validated) "yes" else "no"}\n")
                    publish(emit, "  Downstream: ~${caps.linkDownstreamBandwidthKbps / 1000} Mbps\n")
                    publish(emit, "  Upstream: ~${caps.linkUpstreamBandwidthKbps / 1000} Mbps\n")
                }
            }.onFailure { publish(emit, "  Connectivity info unavailable: ${it.message}\n") }

            currentCoroutineContext().ensureActive()
            publish(emit, "\n── Device interfaces ──\n")
            runCatching {
                val nifs = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                if (nifs.isEmpty()) publish(emit, "  (none visible)\n")
                for (nif in nifs) {
                    val addrs = nif.inetAddresses.toList().joinToString(", ") { it.hostAddress }
                    val flags = buildList {
                        if (nif.isUp) add("up")
                        if (nif.isLoopback) add("loopback")
                        if (nif.isPointToPoint) add("ptp")
                    }.joinToString(",")
                    val mtu = runCatching { nif.mtu }.getOrDefault(-1)
                    publish(emit, "  ${nif.name} [$flags] MTU $mtu\n")
                    publish(emit, "    ${addrs.ifBlank { "(no addresses)" }}\n")
                }
            }.onFailure { publish(emit, "  Interface enumeration failed: ${it.message}\n") }
        }

    // ------------------------------------------------------------------- ping

    suspend fun ping(target: String, count: Int, emit: (String) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            val n = count.coerceIn(1, 100)
            val resolved = runCatching { InetAddress.getByName(target) }.getOrNull()
            publish(
                emit,
                "PING $target${resolved?.let { " (${it.hostAddress})" } ?: ""}: $n packets, 2 s timeout\n\n",
            )
            val result = runCatching {
                runProcess(listOf("/system/bin/ping", "-c", n.toString(), "-W", "2", target), n * 3L + 15, emit)
            }.getOrNull()

            if (result != null && (result.exitCode == 0 || result.output.contains("packets transmitted"))) {
                val stats = Regex("min/avg/max[^=]*=\\s*([\\d.]+)/([\\d.]+)/([\\d.]+)").find(result.output)
                val loss = Regex("([\\d.]+)% packet loss").find(result.output)?.groupValues?.get(1)
                publish(emit, "\n── Statistics ──\n")
                if (loss != null) publish(emit, "Packet loss: $loss%\n")
                if (stats != null) {
                    publish(
                        emit,
                        "RTT min/avg/max: ${stats.groupValues[1]} / ${stats.groupValues[2]} / ${stats.groupValues[3]} ms\n",
                    )
                } else {
                    publish(emit, "No RTT summary available.\n")
                }
                return@withContext
            }

            publish(emit, "\nping binary unavailable or failed — falling back to InetAddress.isReachable\n")
            publish(emit, "(uses TCP echo port 7 when ICMP is not permitted; may report false timeouts)\n\n")
            val addr = resolved ?: throw UnknownHostException("Cannot resolve $target")
            val times = mutableListOf<Long>()
            var received = 0
            for (i in 1..n) {
                currentCoroutineContext().ensureActive()
                val t0 = System.nanoTime()
                val ok = runCatching { addr.isReachable(2000) }.getOrDefault(false)
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (ok) {
                    received++
                    times.add(ms)
                    publish(emit, "Reply from ${addr.hostAddress}: seq=$i time=$ms ms\n")
                } else {
                    publish(emit, "Request timeout for seq=$i\n")
                }
            }
            publish(emit, "\n── Statistics ──\n")
            publish(emit, String.format(Locale.US, "Sent: %d, received: %d, loss: %.1f%%\n", n, received, (n - received) * 100.0 / n))
            if (times.isNotEmpty()) {
                publish(emit, "RTT min/avg/max: ${times.min()} / ${times.average().toLong()} / ${times.max()} ms\n")
            }
        }

    // ------------------------------------------------------------- traceroute

    suspend fun traceroute(target: String, maxHops: Int, emit: (String) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            val hops = maxHops.coerceIn(1, 64)
            val dest = runCatching { InetAddress.getByName(target).hostAddress }.getOrNull()
            publish(emit, "Traceroute to $target${dest?.let { " ($it)" } ?: ""}, max $hops hops\n\n")

            val direct = runCatching {
                runProcess(listOf("/system/bin/traceroute", "-m", hops.toString(), target), hops * 3L + 15, null)
            }.getOrNull()
            if (direct != null && direct.exitCode == 0 && direct.output.isNotBlank()) {
                publish(emit, direct.output)
                return@withContext
            }

            publish(emit, "traceroute binary unavailable — using ping TTL sweep\n\n")
            for (ttl in 1..hops) {
                currentCoroutineContext().ensureActive()
                val res = runCatching {
                    runProcess(listOf("/system/bin/ping", "-t", ttl.toString(), "-c", "1", "-W", "1", target), 6, null)
                }
                if (res.isFailure && res.exceptionOrNull() is IOException) {
                    publish(emit, "ping binary unavailable — traceroute is not possible on this device.\n")
                    return@withContext
                }
                val out = res.getOrNull()?.output.orEmpty()
                val replyLine = out.lineSequence().firstOrNull { it.contains("bytes from") }
                val fromLine = out.lineSequence().firstOrNull { it.contains("From ") }
                val rtt = Regex("time[=<]([\\d.]+)\\s*ms").find(out)?.groupValues?.get(1)
                val ip = when {
                    replyLine != null -> extractIp(replyLine.substringAfter("bytes from"))
                    fromLine != null -> extractIp(fromLine.substringAfter("From "))
                    else -> null
                }
                if (ip == null) {
                    publish(emit, String.format(Locale.US, "%2d  * * *  (no reply)\n", ttl))
                } else {
                    val name = reverseNameOf(ip)
                    val suffix = if (name != null) " ($name)" else ""
                    publish(emit, String.format(Locale.US, "%2d  %s%s  %s ms\n", ttl, ip, suffix, rtt ?: "?"))
                }
                if (replyLine != null) {
                    publish(emit, "\nDestination reached after $ttl hop(s).\n")
                    return@withContext
                }
            }
            publish(emit, "\nStopped at the hop limit ($hops) without reaching the destination.\n")
        }

    // ------------------------------------------------------------------ ports

    suspend fun scanPorts(
        host: String,
        ports: List<Int>,
        timeoutMs: Int,
        emit: (String) -> Unit,
        onProgress: (Float) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        require(ports.isNotEmpty()) { "No ports selected" }
        val addr = InetAddress.getByName(host)
        val total = ports.size
        val timeout = timeoutMs.coerceIn(100, 10_000)
        publish(emit, "Scanning ${addr.hostAddress}: $total port(s), timeout $timeout ms, concurrency 64\n\n")
        val start = System.nanoTime()
        val open = ConcurrentLinkedQueue<Int>()
        val scanned = AtomicInteger(0)
        val semaphore = Semaphore(64)
        coroutineScope {
            for (port in ports) {
                launch {
                    semaphore.withPermit {
                        ensureActive()
                        val isOpen = runCatching {
                            Socket().use { s ->
                                s.connect(InetSocketAddress(addr, port), timeout)
                                true
                            }
                        }.getOrDefault(false)
                        if (isOpen) open.add(port)
                        val done = scanned.incrementAndGet()
                        if (done % 4 == 0 || done == total) {
                            withContext(Dispatchers.Main) { onProgress(done.toFloat() / total) }
                        }
                    }
                }
            }
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        val sorted = open.sorted()
        publish(emit, "── Results ──\n")
        if (sorted.isEmpty()) {
            publish(emit, "No open ports found (closed/filtered, or host unreachable).\n")
        } else {
            for (p in sorted) {
                publish(emit, "OPEN  $p/tcp  ${SERVICE_NAMES[p] ?: "unknown"}\n")
            }
        }
        publish(emit, "\n── Stats ──\n")
        publish(emit, "Scanned: $total | Open: ${sorted.size} | Elapsed: $elapsedMs ms | Timeout: $timeout ms\n")
        publish(emit, "Note: results depend on firewalls; filtered ports appear closed.\n")
    }

    // -------------------------------------------------------------------- dns

    private fun typeConstant(name: String): Int = when (name.uppercase(Locale.US)) {
        "A" -> Type.A
        "AAAA" -> Type.AAAA
        "MX" -> Type.MX
        "NS" -> Type.NS
        "TXT" -> Type.TXT
        "CNAME" -> Type.CNAME
        "SOA" -> Type.SOA
        "CAA" -> Type.CAA
        "PTR" -> Type.PTR
        "SRV" -> Type.SRV
        else -> throw IllegalArgumentException("Unsupported DNS type: $name")
    }

    private fun resultName(code: Int): String = when (code) {
        Lookup.SUCCESSFUL -> "no error"
        Lookup.UNRECOVERABLE -> "unrecoverable error"
        Lookup.TRY_AGAIN -> "timeout / try again"
        Lookup.HOST_NOT_FOUND -> "host not found (NXDOMAIN)"
        Lookup.TYPE_NOT_FOUND -> "no records of this type"
        else -> "result code $code"
    }

    private fun resolverCandidates(): List<String> {
        val servers = mutableListOf<String>()
        runCatching {
            ResolverConfig.getCurrentConfig().servers().forEach { sa ->
                val a = sa.address
                if (a != null && !a.isLoopbackAddress) servers.add(a.hostAddress)
            }
        }
        servers.add("1.1.1.1")
        servers.add("8.8.8.8")
        return servers.distinct()
    }

    private fun reverseLookupName(ip: String): String {
        val bytes = InetAddress.getByName(ip).address
        return if (bytes.size == 4) {
            bytes.reversed().joinToString(".") { (it.toInt() and 0xFF).toString() } + ".in-addr.arpa"
        } else {
            bytes.joinToString("") { String.format(Locale.US, "%02x", it) }
                .reversed().toCharArray().joinToString(".") + ".ip6.arpa"
        }
    }

    private suspend fun queryOne(
        name: String,
        typeInt: Int,
        typeName: String,
        candidates: List<String>,
        emit: (String) -> Unit,
    ) {
        var answered = false
        for (server in candidates) {
            currentCoroutineContext().ensureActive()
            val resolver = try {
                SimpleResolver(server).apply { setTimeout(Duration.ofSeconds(5)) }
            } catch (e: Exception) {
                continue
            }
            val lookup = try {
                Lookup(name, typeInt)
            } catch (e: Exception) {
                publish(emit, "$typeName: invalid query name ($name)\n")
                return
            }
            lookup.setResolver(resolver)
            val t0 = System.nanoTime()
            val records = try {
                lookup.run()
            } catch (e: Exception) {
                null
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (records != null) {
                publish(emit, "$typeName — answered by $server in $ms ms:\n")
                for (r in records) {
                    publish(emit, "  ${r.name}  ${r.ttl}  IN  ${Type.string(r.type)}  ${r.rdataToString()}\n")
                }
                answered = true
                break
            }
            val code = lookup.result
            if (code == Lookup.HOST_NOT_FOUND || code == Lookup.TYPE_NOT_FOUND) {
                publish(emit, "$typeName — ${resultName(code)} ($server, $ms ms)\n")
                answered = true
                break
            }
        }
        if (!answered) {
            publish(emit, "$typeName — query failed via all resolvers (timeout or network error)\n")
        }
    }

    suspend fun dns(target: String, type: String, emit: (String) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            val ipLiteral = isIpLiteral(target)
            val queries: List<String> = if (type.equals("ALL", ignoreCase = true)) {
                buildList {
                    addAll(listOf("A", "AAAA", "MX", "NS", "TXT", "CNAME", "SOA", "CAA"))
                    if (ipLiteral) add("PTR")
                }
            } else {
                listOf(type.uppercase(Locale.US))
            }
            val candidates = resolverCandidates()
            publish(emit, "DNS lookup: $target\nCandidate resolvers: ${candidates.joinToString(", ")} (5 s timeout each)\n\n")
            for (t in queries) {
                currentCoroutineContext().ensureActive()
                val name = when (t) {
                    "PTR" -> {
                        if (!ipLiteral) {
                            publish(emit, "PTR: skipped (target is not an IP address)\n\n")
                            continue
                        }
                        reverseLookupName(target)
                    }
                    "SRV" -> if (target.startsWith("_")) target else "_sip._tcp.$target"
                    else -> target
                }
                queryOne(name, typeConstant(t), t, candidates, emit)
                publish(emit, "\n")
            }
        }

    // ------------------------------------------------------------------ whois

    private fun whoisQuery(server: String, port: Int, query: String): String {
        Socket().use { s ->
            s.connect(InetSocketAddress(server, port), 8000)
            s.soTimeout = 8000
            s.getOutputStream().apply {
                write(query.toByteArray(Charsets.UTF_8))
                flush()
            }
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            s.getInputStream().use { input ->
                while (buf.size() < 512 * 1024) {
                    val n = input.read(chunk)
                    if (n <= 0) break
                    buf.write(chunk, 0, n)
                }
            }
            return buf.toString("UTF-8")
        }
    }

    private fun findReferral(text: String): String? {
        val refer = Regex("(?im)^\\s*refer:\\s*(\\S+)").find(text)?.groupValues?.get(1)
        if (refer != null) return refer
        return Regex("(?im)^\\s*(?:whois|ReferralServer)\\s*:\\s*(?:whois://)?(\\S+)")
            .find(text)?.groupValues?.get(1)
    }

    suspend fun whois(target: String, emit: (String) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            publish(emit, "WHOIS for $target (via whois.iana.org, max 2 referrals, 8 s timeouts)\n\n")
            var server = "whois.iana.org"
            var port = 43
            var queries = 0
            val visited = mutableSetOf<String>()
            while (queries < 3) {
                currentCoroutineContext().ensureActive()
                visited.add(server)
                queries++
                publish(emit, "── Query $server:$port ──\n")
                val text = whoisQuery(server, port, "$target\r\n")
                publish(emit, text.trimEnd() + "\n\n")
                val referral = findReferral(text) ?: break
                val host = referral.substringBefore(':')
                if (host.isBlank() || visited.contains(host)) break
                port = referral.substringAfter(':', "43").toIntOrNull()?.coerceIn(1, 65535) ?: 43
                server = host
            }
        }

    // --------------------------------------------------------------- http/tls

    suspend fun httpTls(urlString: String, emit: (String) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            val url = try {
                URL(urlString)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid URL: $urlString")
            }
            require(url.protocol == "http" || url.protocol == "https") { "Only http/https URLs are supported" }
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "MultiKit/1.0 (Android network analyzer)")
            val sb = StringBuilder()
            val t0 = System.nanoTime()
            try {
                conn.connect()
                val connectMs = (System.nanoTime() - t0) / 1_000_000
                val status = conn.responseCode
                val headerMs = (System.nanoTime() - t0) / 1_000_000
                sb.append("URL: $urlString\n")
                sb.append("Final URL: ${conn.url}\n")
                sb.append("Status: $status ${conn.responseMessage.orEmpty()}\n")
                sb.append("Connect: $connectMs ms | Headers: $headerMs ms\n")
                sb.append("\n── Headers ──\n")
                for ((key, values) in conn.headerFields) {
                    if (key == null) sb.append(values.joinToString(" ")).append('\n')
                    else sb.append("$key: ${values.joinToString(", ")}\n")
                }
                if (conn is HttpsURLConnection) {
                    sb.append("\n── TLS ──\n")
                    runCatching {
                        sb.append("Cipher suite: ${conn.cipherSuite}\n")
                        val certs = conn.serverCertificates
                        certs.forEachIndexed { i, cert ->
                            if (cert is X509Certificate) {
                                sb.append("[$i] Subject: ${cert.subjectX500Principal.name}\n")
                                sb.append("    Issuer: ${cert.issuerX500Principal.name}\n")
                                sb.append("    Valid: ${df.format(cert.notBefore)} -> ${df.format(cert.notAfter)}\n")
                                sb.append("    Signature alg: ${cert.sigAlgName}\n")
                            }
                        }
                    }.onFailure { sb.append("Certificate details unavailable: ${it.message}\n") }
                }
                val bodyStart = System.nanoTime()
                var bodyBytes = 0L
                var truncated = false
                val stream = if (status >= 400) (conn.errorStream ?: conn.inputStream) else conn.inputStream
                stream?.use { input ->
                    val buf = ByteArray(16 * 1024)
                    while (bodyBytes < 64 * 1024) {
                        ensureActive()
                        val want = minOf(buf.size.toLong(), 64 * 1024 - bodyBytes).toInt()
                        val n = input.read(buf, 0, want)
                        if (n <= 0) break
                        bodyBytes += n
                    }
                    if (bodyBytes >= 64 * 1024 && input.read() != -1) truncated = true
                }
                val bodyMs = (System.nanoTime() - bodyStart) / 1_000_000
                sb.append("\n── Body ──\n")
                sb.append("Read: ${Files.formatBytes(bodyBytes)}")
                if (truncated) sb.append(" (truncated at 64 KB)")
                sb.append(" in $bodyMs ms\n")
                sb.append("Total: ${(System.nanoTime() - t0) / 1_000_000} ms\n")
                publish(emit, sb.toString())
            } finally {
                conn.disconnect()
            }
        }
}