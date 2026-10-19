package com.studyflow.app.p2p

import org.json.JSONArray
import org.json.JSONObject

/** All StudyFlow data travels as Nostr "application specific data" events. */
const val KIND = 30078

data class Event(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun tag(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)
    val d: String get() = tag("d") ?: ""
    val topics: List<String> get() = tags.filter { it.size >= 2 && it[0] == "t" }.map { it[1] }
}

/** NIP-01 / NIP-16 replaceable ordering: newer wins, ties go to the lowest id. */
fun newer(a: Event, b: Event): Boolean =
    a.createdAt > b.createdAt || (a.createdAt == b.createdAt && a.id < b.id)

object Nostr {
    private fun esc(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    /** Canonical serialization used to compute the event id. */
    fun serialize(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
        val sb = StringBuilder()
        sb.append("[0,")
        esc(pubkey, sb)
        sb.append(',').append(createdAt).append(',').append(kind).append(",[")
        tags.forEachIndexed { i, t ->
            if (i > 0) sb.append(',')
            sb.append('[')
            t.forEachIndexed { j, s ->
                if (j > 0) sb.append(',')
                esc(s, sb)
            }
            sb.append(']')
        }
        sb.append("],")
        esc(content, sb)
        sb.append(']')
        return sb.toString()
    }

    fun idOf(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String =
        Hex.enc(sha256(serialize(pubkey, createdAt, kind, tags, content).toByteArray(Charsets.UTF_8)))

    fun create(sec: ByteArray, createdAt: Long, tags: List<List<String>>, content: String, kind: Int = KIND): Event {
        val pub = Hex.enc(Schnorr.pubKey(sec))
        val id = idOf(pub, createdAt, kind, tags, content)
        val sig = Hex.enc(Schnorr.sign(Hex.dec(id), sec))
        return Event(id, pub, createdAt, kind, tags, content, sig)
    }

    fun verify(e: Event): Boolean {
        if (!Hex.isHex(e.id, 64) || !Hex.isHex(e.pubkey, 64) || !Hex.isHex(e.sig, 128)) return false
        if (idOf(e.pubkey, e.createdAt, e.kind, e.tags, e.content) != e.id) return false
        return try {
            Schnorr.verify(Hex.dec(e.id), Hex.dec(e.pubkey), Hex.dec(e.sig))
        } catch (ex: Exception) {
            false
        }
    }

    fun toJson(e: Event): JSONObject = JSONObject()
        .put("id", e.id).put("pubkey", e.pubkey).put("created_at", e.createdAt)
        .put("kind", e.kind)
        .put("tags", JSONArray().also { arr -> e.tags.forEach { t -> arr.put(JSONArray().also { a -> t.forEach { a.put(it) } }) } })
        .put("content", e.content).put("sig", e.sig)

    fun parse(o: JSONObject): Event? = try {
        val tagsArr = o.getJSONArray("tags")
        val tags = (0 until tagsArr.length()).map { i ->
            val a = tagsArr.getJSONArray(i)
            (0 until a.length()).map { j -> a.optString(j) }
        }
        val content = o.getString("content")
        if (content.length > 60_000) null
        else Event(
            o.getString("id"), o.getString("pubkey"), o.getLong("created_at"),
            o.getInt("kind"), tags, content, o.getString("sig"),
        )
    } catch (e: Exception) {
        null
    }
}
