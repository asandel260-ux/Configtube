package com.studyflow.app.p2p

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Outbound-only WebSocket connections to a handful of interchangeable relays. A relay is only a mailbox:
 * everything that passes through is signed by its author, so a relay can drop data but cannot forge it.
 * Phones never accept inbound connections, which is what makes this work behind carrier NAT.
 */
class RelayPool(private val listener: Listener) {
    interface Listener {
        fun onOpen(url: String)
        fun onClosed(url: String)
        fun onEvent(url: String, sub: String, e: Event)
        fun onEose(url: String, sub: String)
        fun onOk(url: String, id: String, ok: Boolean, msg: String)
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sf-relay-timer").apply { isDaemon = true } }

    private inner class Conn(val url: String) {
        @Volatile var ws: WebSocket? = null
        @Volatile var open = false
        @Volatile var wanted = true
        var attempts = 0
        var retry: ScheduledFuture<*>? = null

        fun connect() {
            synchronized(this) {
                if (!wanted || ws != null) return
                val req = try { Request.Builder().url(url).build() } catch (e: Exception) { return }
                ws = client.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        synchronized(this@Conn) {
                            if (ws !== webSocket) return
                            open = true
                            attempts = 0
                            for ((id, fs) in subs) sendReq(this@Conn, id, fs)
                        }
                        listener.onOpen(url)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (ws !== webSocket || text.length > 120_000) return
                        handle(url, text)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)
                })
            }
        }

        fun lost(webSocket: WebSocket) {
            val wasOpen: Boolean
            synchronized(this) {
                if (ws !== webSocket) return
                wasOpen = open
                open = false
                ws = null
                if (!wanted) {
                    if (wasOpen) listener.onClosed(url)
                    return
                }
                attempts++
                val delay = minOf(60L, 2L shl minOf(attempts, 5))
                retry = timer.schedule({ connect() }, delay, TimeUnit.SECONDS)
            }
            if (wasOpen) listener.onClosed(url)
        }

        fun close() {
            wanted = false
            retry?.cancel(false)
            val w = ws
            ws = null
            open = false
            try { w?.close(1000, "bye") } catch (e: Exception) { }
        }
    }

    private val conns = ConcurrentHashMap<String, Conn>()
    private val subs = ConcurrentHashMap<String, List<Filter>>()

    fun setRelays(urls: List<String>) {
        val want = urls.toSet()
        for (u in conns.keys.toList()) if (u !in want) conns.remove(u)?.close()
        for (u in want) if (!conns.containsKey(u)) conns[u] = Conn(u).also { it.connect() }
        for (c in conns.values) if (!c.wanted) { c.wanted = true; c.connect() }
    }

    fun stop() {
        for (c in conns.values) c.close()
        conns.clear()
    }

    fun openCount(): Int = conns.values.count { it.open }
    fun totalCount(): Int = conns.size
    fun openUrls(): List<String> = conns.values.filter { it.open }.map { it.url }
    fun status(): Map<String, Boolean> = conns.values.associate { it.url to it.open }

    fun req(id: String, filters: List<Filter>) {
        subs[id] = filters
        for (c in conns.values) if (c.open) sendReq(c, id, filters)
    }

    fun close(id: String) {
        if (subs.remove(id) == null) return
        val m = JSONArray().put("CLOSE").put(id).toString()
        for (c in conns.values) if (c.open) c.ws?.send(m)
    }

    private fun sendReq(c: Conn, id: String, filters: List<Filter>) {
        val a = JSONArray().put("REQ").put(id)
        for (f in filters) a.put(f.toJson())
        c.ws?.send(a.toString())
    }

    fun publishAll(e: Event) {
        val m = JSONArray().put("EVENT").put(Nostr.toJson(e)).toString()
        for (c in conns.values) if (c.open) c.ws?.send(m)
    }

    fun publishTo(url: String, e: Event) {
        val c = conns[url] ?: return
        if (c.open) c.ws?.send(JSONArray().put("EVENT").put(Nostr.toJson(e)).toString())
    }

    private fun handle(url: String, text: String) {
        try {
            val a = JSONArray(text)
            when (a.optString(0)) {
                "EVENT" -> {
                    val e = Nostr.parse(a.getJSONObject(2)) ?: return
                    listener.onEvent(url, a.getString(1), e)
                }
                "EOSE" -> listener.onEose(url, a.getString(1))
                "OK" -> listener.onOk(url, a.optString(1), a.optBoolean(2, false), a.optString(3))
            }
        } catch (e: Exception) {
            // malformed frame: ignore
        }
    }
}
