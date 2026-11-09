package com.toolbox.multitool.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.xbill.DNS.Lookup
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.Type
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection

/** All functions emit human-readable lines and are cancellable (coroutine cancel kills child processes/sockets). */
object NetworkEngine {

    private val serviceNames = mapOf(
        21 to "ftp", 22 to "ssh", 23 to "telnet", 25 to "smtp", 53 to "dns", 80 to "http", 110 to "pop3",
        123 to "ntp", 143 to "imap", 443 to "https", 465 to "smtps", 587 to "submission", 993 to "imaps",
        995 to "pop3s", 1433 to "mssql", 1723 to "pptp", 3306 to "mysql", 3389 to "rdp", 5432 to "postgres",
        5900 to "vnc", 6379 to "redis", 8000 to "http-alt", 8080 to "http-proxy", 8443 to "https-alt", 27017 to "mongodb",
    )
    val commonPorts = serviceNames.keys.sorted()

    fun ping(host: String, count: Int = 5): Flow<String> = flow {
        val p = ProcessBuilder("ping", "-c", "$count", "-W", "3", host).redirectErrorStream(true).start()
        try {
            val r = p.inputStream.bufferedReader()
            while (true) { val line = r.readLine() ?: break; emit(line) }
        } finally { p.destroy() }
    }.flowOn(Dispatchers.IO)

    /** TTL-stepping traceroute built on the system ping binary (no root needed). */
    fun trace(host: String, maxHops: Int = 30): Flow<String> = flow {
        val ipRegex = Regex("""[Ff]rom\s+([0-9a-fA-F:.]+)""")
        val target = runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()
        emit("traceroute to $host (${target ?: "?"}), max $maxHops hops")
        for (ttl in 1..maxHops) {
            val t0 = System.nanoTime()
            val p = ProcessBuilder("ping", "-c", "1", "-W", "2", "-t", "$ttl", host).redirectErrorStream(true).start()
            val out = try { p.inputStream.bufferedReader().readText() } finally { p.destroy() }
            val ms = (System.nanoTime() - t0) / 1_000_000
            val ip = ipRegex.find(out)?.groupValues?.get(1)?.trimEnd(':')
            if (ip == null) emit("%2d  *".format(ttl))
            else {
                val name = runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrDefault(ip)
                emit("%2d  %s (%s)  ~%d ms".format(ttl, name, ip, ms))
                if (out.contains("bytes from") || ip == target) { emit("Reached destination."); return@flow }
            }
        }
    }.flowOn(Dispatchers.IO)

    fun ports(host: String, ports: List<Int>, timeoutMs: Int = 800): Flow<String> = channelFlow {
        val addr = InetAddress.getByName(host)
        send("Scanning ${addr.hostAddress}: ${ports.size} ports")
        val gate = Semaphore(150)
        val open = java.util.concurrent.atomic.AtomicInteger()
        val jobs = ports.map { port ->
            launch(Dispatchers.IO) {
                gate.withPermit {
                    val ok = runCatching { Socket().use { it.connect(InetSocketAddress(addr, port), timeoutMs) } }.isSuccess
                    if (ok) { open.incrementAndGet(); send("$port/tcp open  ${serviceNames[port] ?: ""}") }
                }
            }
        }
        jobs.forEach { it.join() }
        send("Done. ${open.get()} open of ${ports.size}.")
    }

    fun parsePorts(spec: String): List<Int> =
        if (spec.isBlank()) commonPorts
        else spec.split(',').flatMap { part ->
            val r = part.trim().split('-')
            when (r.size) {
                1 -> listOfNotNull(r[0].toIntOrNull())
                2 -> (r[0].toIntOrNull() ?: 1)..(r[1].toIntOrNull() ?: 1024)
                else -> emptyList()
            }.toList()
        }.filter { it in 1..65535 }.distinct()

    fun dns(host: String, resolver: String = "1.1.1.1"): Flow<String> = flow {
        val res = SimpleResolver(resolver).apply { timeout = java.time.Duration.ofSeconds(4) }
        emit("Resolver $resolver")
        for ((label, type) in listOf("A" to Type.A, "AAAA" to Type.AAAA, "CNAME" to Type.CNAME, "MX" to Type.MX,
            "NS" to Type.NS, "TXT" to Type.TXT, "SOA" to Type.SOA, "CAA" to Type.CAA)) {
            val answers = runCatching { Lookup(host, type).apply { setResolver(res); setCache(null) }.run() }.getOrNull()
            if (answers.isNullOrEmpty()) emit("$label: —") else answers.forEach { emit("$label: ${it.rdataToString()}") }
        }
        runCatching {
            val all = InetAddress.getAllByName(host)
            all.forEach { emit("Reverse ${it.hostAddress} → ${it.canonicalHostName}") }
        }
    }.flowOn(Dispatchers.IO)

    private fun whoisRaw(server: String, query: String): String =
        Socket().use { s ->
            s.connect(InetSocketAddress(server, 43), 6000); s.soTimeout = 8000
            s.getOutputStream().apply { write("$query\r\n".toByteArray()); flush() }
            s.getInputStream().bufferedReader().readText().take(60_000)
        }

    fun whois(query: String): Flow<String> = flow {
        val iana = whoisRaw("whois.iana.org", query)
        val refer = Regex("""(?im)^(?:refer|whois):\s*(\S+)""").find(iana)?.groupValues?.get(1)
        if (refer == null) emit(iana) else {
            emit("# via $refer")
            emit(whoisRaw(refer, query))
        }
    }.flowOn(Dispatchers.IO)

    fun http(host: String): Flow<String> = flow {
        val url = URL(if (host.startsWith("http")) host else "https://$host")
        val c = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false; connectTimeout = 6000; readTimeout = 6000
            setRequestProperty("User-Agent", "MultiTool/0.1")
        }
        emit("HTTP ${c.responseCode} ${c.responseMessage}")
        c.headerFields.forEach { (k, v) -> if (k != null) emit("$k: ${v.joinToString(", ")}") }
        if (c is HttpsURLConnection) {
            emit("")
            emit("TLS ${c.cipherSuite}")
            (c.serverCertificates.firstOrNull() as? X509Certificate)?.let { x ->
                emit("Subject: ${x.subjectX500Principal.name}")
                emit("Issuer:  ${x.issuerX500Principal.name}")
                emit("Valid:   ${x.notBefore} → ${x.notAfter}")
                emit("SigAlg:  ${x.sigAlgName}")
                x.subjectAlternativeNames?.let { sans -> emit("SANs:    " + sans.joinToString { it[1].toString() }) }
            }
        }
        c.disconnect()
    }.flowOn(Dispatchers.IO)

    /** IP / network ownership through RDAP (open, keyless). */
    fun ipInfo(host: String): Flow<String> = flow {
        val ips = InetAddress.getAllByName(host).map { it.hostAddress!! }
        for (ip in ips) {
            emit("IP $ip")
            runCatching {
                val c = URL("https://rdap.org/ip/$ip").openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 8000; c.setRequestProperty("Accept", "application/rdap+json")
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                listOf("name", "country", "startAddress", "endAddress", "type", "handle").forEach { k ->
                    j.optString(k).takeIf { it.isNotEmpty() }?.let { emit("  $k: $it") }
                }
            }.onFailure { emit("  RDAP lookup failed: ${it.message}") }
        }
    }.flowOn(Dispatchers.IO)

    /** Everything about a domain/IP in one go. */
    fun full(host: String): Flow<String> = flow {
        for ((title, f) in listOf<Pair<String, (String) -> Flow<String>>>(
            "IP / OWNER" to ::ipInfo, "DNS" to { h -> dns(h) }, "HTTP + TLS" to ::http, "WHOIS" to ::whois,
        )) {
            emit("\n=== $title ===")
            runCatching { f(host).collect { emit(it) } }.onFailure { emit("(failed: ${it.message})") }
        }
    }
}
