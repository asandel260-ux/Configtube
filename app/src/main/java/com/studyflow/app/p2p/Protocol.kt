package com.studyflow.app.p2p

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const val APP_TAG = "sf1"       // every StudyFlow event carries ["t","sf1"]
const val T_CORE = "sfcore"     // group definition / membership / moderation
const val T_MSG = "sfm"         // chat messages
const val T_DIR = "sfdir"       // public group directory
const val T_BOARD = "sflb"      // global leaderboard entries
const val MAX_MEMBERS = 20

/** The `d` tag (replaceable-event identity) of each record type. */
object D {
    const val PROFILE = "sf:p"
    const val LIVE = "sf:l"
    const val BOARD = "sf:b"
    const val STATS = "sf:s:"
    const val GROUP = "sf:g:"
    const val JOIN = "sf:j:"
    const val MOD = "sf:m:"
    const val MSG = "sf:c:"
    fun stats(ym: String) = STATS + ym
    fun group(gid: String) = GROUP + gid
    fun join(gref: String) = JOIN + gref
    fun mod(gref: String) = MOD + gref
}

fun randHex(bytes: Int): String {
    val b = ByteArray(bytes)
    SecureRandom().nextBytes(b)
    return Hex.enc(b)
}

/** Strips control characters (relays and clients disagree about escaping them) and caps length. */
fun clean(s: String, max: Int): String {
    val sb = StringBuilder()
    for (c in s) {
        if (c == '\n' || c == '\t' || c >= ' ') sb.append(c)
        if (sb.length >= max) break
    }
    return sb.toString().trim()
}

private fun obj(s: String?): JSONObject? = try {
    if (s == null) null else JSONObject(s)
} catch (e: Exception) {
    null
}

// ---------------------------------------------------------------------------
// Group identity, invites, relays
// ---------------------------------------------------------------------------

/** A group is identified by "<founder pubkey>:<16 hex id>"; only the founder can sign its definition. */
object GRef {
    fun make(founder: String, gid: String) = "$founder:$gid"
    fun founder(g: String) = g.substringBefore(':')
    fun gid(g: String) = g.substringAfter(':')
    fun valid(g: String): Boolean {
        val p = g.split(':')
        return p.size == 2 && Hex.isHex(p[0], 64) && Hex.isHex(p[1], 16)
    }
}

object Relays {
    private val RX = Regex("^wss://[a-zA-Z0-9.-]+(:[0-9]{1,5})?(/[a-zA-Z0-9._~/-]*)?$")
    fun valid(u: String) = u.length <= 100 && RX.matches(u)
}

data class Invite(val gref: String, val secret: String?, val relays: List<String> = emptyList())

object InviteCodec {
    private val CODE = Regex("SF1\\.([0-9a-fA-F]{64})\\.([0-9a-fA-F]{16})(?:\\.([0-9a-fA-F]{32}))?")
    private val RELAY = Regex("[?&]r=([^&\\s]+)")

    fun code(i: Invite): String =
        "SF1." + GRef.founder(i.gref) + "." + GRef.gid(i.gref) + (if (i.secret != null) "." + i.secret else "")

    fun link(i: Invite): String {
        val sb = StringBuilder("studyflow://join?c=").append(code(i))
        for (r in i.relays.take(3)) sb.append("&r=").append(URLEncoder.encode(r, "UTF-8"))
        return sb.toString()
    }

    /** Accepts a bare code, a studyflow:// link, or a whole pasted message containing either. */
    fun parse(text: String): Invite? {
        val m = CODE.find(text) ?: return null
        val gref = GRef.make(m.groupValues[1].lowercase(), m.groupValues[2].lowercase())
        val secret = m.groupValues[3].lowercase().ifEmpty { null }
        val relays = RELAY.findAll(text).mapNotNull {
            try { URLDecoder.decode(it.groupValues[1], "UTF-8") } catch (e: Exception) { null }
        }.filter { Relays.valid(it) }.distinct().take(3).toList()
        return Invite(gref, secret, relays)
    }
}

// ---------------------------------------------------------------------------
// Private-group encryption (AES-256-GCM, key derived from the invite secret)
// ---------------------------------------------------------------------------

object Seal {
    private val rng = SecureRandom()

    fun key(secretHex: String): ByteArray = sha256("sf-group-key".toByteArray(Charsets.UTF_8), Hex.dec(secretHex))

    fun seal(plain: String, key: ByteArray?): String {
        if (key == null) return plain
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "e1:" + Base64.getEncoder().withoutPadding().encodeToString(iv + ct)
    }

    /** With a key, only properly encrypted content is accepted; without one, only plaintext. */
    fun open(content: String, key: ByteArray?): String? {
        val enc = content.startsWith("e1:")
        if (key == null) return if (enc) null else content
        if (!enc) return null
        return try {
            val raw = Base64.getDecoder().decode(content.substring(3))
            if (raw.size < 13) return null
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}

// ---------------------------------------------------------------------------
// Profile / live / board records
// ---------------------------------------------------------------------------

data class Profile(val pub: String, val name: String, val bio: String, val color: Int, val glyph: Int, val at: Long)
data class Live(val pub: String, val on: Boolean, val subject: String, val since: Long, val mode: Int, val at: Long) {
    fun active(now: Long) = on && now - at < 720
}

data class BoardEntry(
    val pub: String, val day: String, val daySec: Long, val week: String, val weekSec: Long,
    val month: String, val monthSec: Long, val at: Long,
)

object Codec {
    fun profile(e: Event): Profile? {
        val o = obj(e.content) ?: return null
        val name = clean(o.optString("n"), 24)
        if (name.isEmpty()) return null
        return Profile(e.pubkey, name, clean(o.optString("b"), 160), o.optInt("c", 0).coerceIn(0, 63), o.optInt("g", 0).coerceIn(0, 15), e.createdAt)
    }

    fun profileJson(name: String, bio: String, color: Int, glyph: Int): String =
        JSONObject().put("n", clean(name, 24)).put("b", clean(bio, 160)).put("c", color).put("g", glyph).put("v", 1).toString()

    fun live(e: Event): Live? {
        val o = obj(e.content) ?: return null
        return Live(e.pubkey, o.optBoolean("on", false), clean(o.optString("s"), 30), o.optLong("t", 0), o.optInt("m", 0).coerceIn(0, 1), e.createdAt)
    }

    fun liveJson(on: Boolean, subject: String, sinceSec: Long, mode: Int): String =
        JSONObject().put("on", on).put("s", clean(subject, 30)).put("t", sinceSec).put("m", mode).toString()

    fun board(e: Event): BoardEntry? {
        val o = obj(e.content) ?: return null
        if (o.optBoolean("off", false)) return null
        return BoardEntry(
            e.pubkey, o.optString("d"), o.optLong("ds").coerceIn(0, 86400),
            o.optString("w"), o.optLong("ws").coerceIn(0, 7 * 86400L),
            o.optString("m"), o.optLong("ms").coerceIn(0, 31 * 86400L), e.createdAt,
        )
    }

    fun boardJson(day: String, ds: Long, week: String, ws: Long, month: String, ms: Long): String =
        JSONObject().put("d", day).put("ds", ds).put("w", week).put("ws", ws).put("m", month).put("ms", ms).toString()

    fun boardOffJson(): String = JSONObject().put("off", true).toString()
}

// ---------------------------------------------------------------------------
// Study statistics
// ---------------------------------------------------------------------------

data class RawSession(val startMs: Long, val durSec: Long, val subject: String, val mode: Int, val manual: Boolean)
data class SessionLite(val startMin: Int, val durSec: Int, val subject: String, val mode: Int)
data class DayStat(val sec: Long, val sessions: List<SessionLite>)

fun ymOf(d: LocalDate): String = "%04d%02d".format(d.year, d.monthValue)

class StatsIndex(val months: Map<String, Map<Int, DayStat>>) {
    fun day(d: LocalDate): DayStat? = months[ymOf(d)]?.get(d.dayOfMonth)
    fun sec(d: LocalDate): Long = day(d)?.sec ?: 0L
    fun has(ym: String) = months.containsKey(ym)

    fun sum(from: LocalDate, to: LocalDate): Long {
        var t = 0L
        var d = from
        var guard = 0
        while (!d.isAfter(to) && guard++ < 800) {
            t += sec(d)
            d = d.plusDays(1)
        }
        return t
    }

    companion object { val EMPTY = StatsIndex(emptyMap()) }
}

enum class Period { DAY, WEEK, MONTH }

fun isoWeekKey(d: LocalDate): String =
    "%04d-W%02d".format(d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

fun weekStartOf(d: LocalDate): LocalDate = d.with(DayOfWeek.MONDAY)

fun secFor(idx: StatsIndex, p: Period, today: LocalDate): Long = when (p) {
    Period.DAY -> idx.sec(today)
    Period.WEEK -> idx.sum(weekStartOf(today), weekStartOf(today).plusDays(6))
    Period.MONTH -> idx.sum(today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()))
}

fun secFor(b: BoardEntry, p: Period, today: LocalDate): Long = when (p) {
    Period.DAY -> if (b.day == today.toString()) b.daySec else 0L
    Period.WEEK -> if (b.week == isoWeekKey(today)) b.weekSec else 0L
    Period.MONTH -> if (b.month == "%04d-%02d".format(today.year, today.monthValue)) b.monthSec else 0L
}

object StatsCodec {
    fun encodeMonth(days: Map<Int, DayStat>): String {
        val subj = ArrayList<String>()
        val d = JSONObject()
        for ((day, st) in days.toSortedMap()) {
            val o = JSONObject().put("t", st.sec)
            if (st.sessions.isNotEmpty()) {
                val x = JSONArray()
                for (s in st.sessions.take(60)) {
                    var i = subj.indexOf(s.subject)
                    if (i < 0) {
                        subj.add(s.subject)
                        i = subj.size - 1
                    }
                    x.put(JSONArray().put(s.startMin).put(s.durSec).put(i).put(s.mode))
                }
                o.put("x", x)
            }
            d.put(day.toString(), o)
        }
        return JSONObject().put("v", 1).put("s", JSONArray(subj.map { clean(it, 30) })).put("d", d).toString()
    }

    /** Defensive: everything from the network is clamped to plausible values. */
    fun decodeMonth(content: String): Map<Int, DayStat>? {
        val o = obj(content) ?: return null
        val subjArr = o.optJSONArray("s") ?: JSONArray()
        val subj = (0 until subjArr.length()).map { clean(subjArr.optString(it), 30) }
        val d = o.optJSONObject("d") ?: return emptyMap()
        val out = HashMap<Int, DayStat>()
        for (k in d.keys()) {
            val day = k.toIntOrNull() ?: continue
            if (day !in 1..31) continue
            val e = d.optJSONObject(k) ?: continue
            val list = ArrayList<SessionLite>()
            val x = e.optJSONArray("x")
            if (x != null) {
                for (i in 0 until minOf(x.length(), 60)) {
                    val a = x.optJSONArray(i) ?: continue
                    val dur = a.optInt(1, 0)
                    if (dur !in 1..(14 * 3600)) continue
                    list.add(
                        SessionLite(
                            a.optInt(0, 0).coerceIn(0, 1439), dur,
                            subj.getOrElse(a.optInt(2, -1)) { "" }, a.optInt(3, 0).coerceIn(0, 1),
                        )
                    )
                }
            }
            out[day] = DayStat(e.optLong("t", 0).coerceIn(0, 86400), list)
        }
        return out
    }
}

object StatsBuild {
    /** Groups raw sessions into per-month, per-day records. [detail] decides whether session lists are included. */
    fun build(raws: List<RawSession>, zone: ZoneId, detail: Boolean): Map<String, Map<Int, DayStat>> {
        val byDate = HashMap<LocalDate, MutableList<RawSession>>()
        for (r in raws) {
            if (r.durSec <= 0) continue
            val date = java.time.Instant.ofEpochMilli(r.startMs).atZone(zone).toLocalDate()
            byDate.getOrPut(date) { ArrayList() }.add(r)
        }
        val out = HashMap<String, MutableMap<Int, DayStat>>()
        for ((date, list) in byDate) {
            val sorted = list.sortedBy { it.startMs }
            val sec = sorted.sumOf { it.durSec }.coerceAtMost(86400L)
            val lite = if (!detail) emptyList() else sorted.map {
                val t = java.time.Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalTime()
                SessionLite(t.hour * 60 + t.minute, it.durSec.coerceAtMost(14L * 3600).toInt(), it.subject, it.mode)
            }
            out.getOrPut(ymOf(date)) { HashMap() }[date.dayOfMonth] = DayStat(sec, lite)
        }
        return out
    }
}

// ---------------------------------------------------------------------------
// Groups
// ---------------------------------------------------------------------------

const val VIS_PUBLIC = 0
const val VIS_PRIVATE = 1
const val JOIN_OPEN = 0
const val JOIN_APPROVAL = 1
const val JOIN_CLOSED = 2

const val ST_NONE = 0
const val ST_MEMBER = 1
const val ST_PENDING = 2
const val ST_BANNED = 3
const val ST_KICKED = 4
const val ST_FULL = 5
const val ST_CLOSED = 6

val CATEGORIES = listOf("general", "exam", "school", "university", "language", "coding", "science", "reading")

data class GroupDef(
    val gref: String, val founder: String, val gid: String,
    val name: String, val desc: String, val cat: String,
    val vis: Int, val join: Int, val max: Int,
    val mods: List<String>, val ann: String, val relays: List<String>,
    val closed: Boolean, val at: Long,
) {
    val isPrivate get() = vis == VIS_PRIVATE
    fun isMod(pub: String) = pub == founder || pub in mods
}

data class Msg(val id: String, val author: String, val text: String, val at: Long)

data class GroupView(
    val def: GroupDef,
    val members: List<String>,
    val joinAt: Map<String, Long>,
    val pending: List<String>,
    val banned: List<String>,
    val muted: Set<String>,
    val msgs: List<Msg>,
    val status: Map<String, Int>,
    /** admitted members that still lack a moderator "ok" (a moderator's phone should seal them in). */
    val unsealed: List<String>,
) {
    fun statusOf(pub: String): Int = if (pub == def.founder) ST_MEMBER else status[pub] ?: ST_NONE
    val full get() = members.size >= def.max
}

object Acts {
    const val BAN = "ban"
    const val UNBAN = "unban"
    const val MUTE = "mute"
    const val UNMUTE = "unmute"
    const val KICK = "kick"
    const val OK = "ok"
    const val DENY = "deny"
    val ALL = setOf(BAN, UNBAN, MUTE, UNMUTE, KICK, OK, DENY)
    fun cat(a: String) = when (a) {
        BAN, UNBAN -> "b"
        MUTE, UNMUTE -> "m"
        KICK -> "k"
        else -> "o"
    }
}

data class ModAct(val pub: String, val act: String, val ts: Long)

object GroupCodec {
    fun def(e: Event, gref: String, key: ByteArray?): GroupDef? {
        val o = obj(Seal.open(e.content, key)) ?: return null
        val modsArr = o.optJSONArray("mods") ?: JSONArray()
        val founder = GRef.founder(gref)
        val mods = (0 until modsArr.length()).map { modsArr.optString(it) }
            .filter { Hex.isHex(it, 64) && it != founder }.distinct().take(5)
        val rl = o.optJSONArray("rl") ?: JSONArray()
        val relays = (0 until rl.length()).map { rl.optString(it) }.filter { Relays.valid(it) }.distinct().take(3)
        val name = clean(o.optString("n"), 40)
        if (name.isEmpty()) return null
        val cat = clean(o.optString("c"), 16).lowercase().ifEmpty { "general" }
        return GroupDef(
            gref, founder, GRef.gid(gref), name, clean(o.optString("ds"), 240), cat,
            o.optInt("vis", 0).coerceIn(0, 1), o.optInt("join", 0).coerceIn(0, 2),
            o.optInt("max", MAX_MEMBERS).coerceIn(2, MAX_MEMBERS),
            mods, clean(o.optString("ann"), 200), relays, o.optBoolean("x", false), e.createdAt,
        )
    }

    fun defJson(d: GroupDef): String = JSONObject()
        .put("n", d.name).put("ds", d.desc).put("c", d.cat).put("vis", d.vis).put("join", d.join)
        .put("max", d.max).put("mods", JSONArray(d.mods)).put("ann", d.ann)
        .put("rl", JSONArray(d.relays)).put("x", d.closed).put("v", 1).toString()

    fun tokens(name: String): List<String> =
        name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length in 2..20 }.distinct().take(6)

    /** Tags for a group definition. Public groups also get directory + search-token tags. */
    fun defTags(d: GroupDef): List<List<String>> {
        val t = ArrayList<List<String>>()
        t.add(listOf("d", D.group(d.gid)))
        t.add(listOf("g", d.gref))
        t.add(listOf("t", APP_TAG))
        t.add(listOf("t", T_CORE))
        if (d.vis == VIS_PUBLIC && !d.closed) {
            t.add(listOf("t", T_DIR))
            t.add(listOf("t", "sfc:" + d.cat))
            for (k in tokens(d.name)) t.add(listOf("t", "sfn:$k"))
        }
        return t
    }

    fun joinJson(join: Boolean): String = JSONObject().put("a", if (join) "join" else "leave").toString()
    fun msgJson(text: String): String = JSONObject().put("t", clean(text, 500)).toString()

    fun modJson(acts: Collection<ModAct>, deleted: Collection<String>): String {
        val a = JSONArray()
        for (x in acts.sortedByDescending { it.ts }.take(300)) a.put(JSONArray().put(x.pub).put(x.act).put(x.ts))
        val x = JSONArray()
        for (id in deleted.toList().takeLast(200)) x.put(id)
        return JSONObject().put("a", a).put("x", x).put("v", 1).toString()
    }

    fun parseMod(content: String): Pair<List<ModAct>, List<String>>? {
        val o = obj(content) ?: return null
        val a = o.optJSONArray("a") ?: JSONArray()
        val acts = ArrayList<ModAct>()
        for (i in 0 until minOf(a.length(), 300)) {
            val r = a.optJSONArray(i) ?: continue
            val pub = r.optString(0)
            val act = r.optString(1)
            if (!Hex.isHex(pub, 64) || act !in Acts.ALL) continue
            acts.add(ModAct(pub, act, r.optLong(2, 0)))
        }
        val x = o.optJSONArray("x") ?: JSONArray()
        val del = (0 until minOf(x.length(), 200)).map { x.optString(it) }.filter { Hex.isHex(it, 64) }
        return Pair(acts, del)
    }
}

/**
 * Deterministic group state. Every phone runs this over the same signed events and must reach the same
 * answer: who is in the group, who is muted, which messages are visible. There is no server to ask.
 */
object GroupLogic {
    fun derive(gref: String, evs: Collection<Event>, key: ByteArray?, now: Long): GroupView? {
        if (!GRef.valid(gref)) return null
        val founder = GRef.founder(gref)
        val gid = GRef.gid(gref)

        var defEv: Event? = null
        for (e in evs) {
            if (e.pubkey == founder && e.d == D.group(gid) && e.tag("g") == gref) {
                val cur = defEv
                if (cur == null || newer(e, cur)) defEv = e
            }
        }
        val def = defEv?.let { GroupCodec.def(it, gref, key) } ?: return null

        val authorized = HashSet<String>()
        authorized.add(founder)
        authorized.addAll(def.mods)

        // Moderation actions: last-writer-wins per (person, category) across all authorised moderators.
        val latest = HashMap<String, ModAct>()
        val deleted = HashSet<String>()
        for (e in evs) {
            if (e.d != D.mod(gref) || e.tag("g") != gref || e.pubkey !in authorized) continue
            val plainMod = Seal.open(e.content, key) ?: continue
            val parsed = GroupCodec.parseMod(plainMod) ?: continue
            for (a in parsed.first) {
                if (a.ts > now + 86400) continue
                if (a.pub == founder) continue
                if (e.pubkey != founder && a.pub in def.mods) continue
                val k = Acts.cat(a.act) + a.pub
                val cur = latest[k]
                if (cur == null || a.ts > cur.ts || (a.ts == cur.ts && a.act > cur.act)) latest[k] = a
            }
            deleted.addAll(parsed.second)
        }
        fun act(cat: String, pub: String): ModAct? = latest[cat + pub]
        val banned = latest.values.filter { Acts.cat(it.act) == "b" && it.act == Acts.BAN }.map { it.pub }.sorted()
        val muted = latest.values.filter { it.act == Acts.MUTE }.map { it.pub }.toSet()

        // Newest join record per person.
        val joins = HashMap<String, Event>()
        for (e in evs) {
            if (e.d != D.join(gref) || e.tag("g") != gref) continue
            val cur = joins[e.pubkey]
            if (cur == null || newer(e, cur)) joins[e.pubkey] = e
        }
        val status = HashMap<String, Int>()
        val joinAt = HashMap<String, Long>()
        val okd = ArrayList<Pair<Long, String>>()     // sealed by a moderator: ordered by the moderator's clock
        val plain = ArrayList<Pair<Long, String>>()   // open-mode joiners not yet sealed: ordered by their own claim
        val pending = ArrayList<Pair<Long, String>>()
        for ((pub, e) in joins) {
            if (pub == founder) continue
            val body = obj(Seal.open(e.content, key)) ?: continue
            if (body.optString("a") != "join") continue
            if (e.createdAt > now + 3600) continue
            joinAt[pub] = e.createdAt
            val ban = act("b", pub)
            if (ban != null && ban.act == Acts.BAN) {
                status[pub] = ST_BANNED
                continue
            }
            val kick = act("k", pub)
            val kickTs = kick?.ts ?: 0L
            if (kick != null && kick.ts >= e.createdAt) {
                status[pub] = ST_KICKED
                continue
            }
            val ok = act("o", pub)
            val sealed = ok != null && ok.act == Acts.OK && ok.ts > kickTs
            when {
                sealed -> okd.add(Pair(ok!!.ts, pub))
                def.join == JOIN_OPEN -> plain.add(Pair(e.createdAt, pub))
                def.join == JOIN_APPROVAL -> {
                    status[pub] = ST_PENDING
                    pending.add(Pair(e.createdAt, pub))
                }
                else -> status[pub] = ST_CLOSED
            }
        }
        val order = Comparator<Pair<Long, String>> { a, b -> if (a.first != b.first) a.first.compareTo(b.first) else a.second.compareTo(b.second) }
        okd.sortWith(order)
        plain.sortWith(order)
        pending.sortWith(order)
        val members = ArrayList<String>()
        members.add(founder)
        val unsealed = ArrayList<String>()
        val room = def.max - 1
        var admitted = 0
        for (p in okd) {
            if (admitted < room) {
                members.add(p.second)
                status[p.second] = ST_MEMBER
                admitted++
            } else status[p.second] = ST_FULL
        }
        for (p in plain) {
            if (admitted < room) {
                members.add(p.second)
                status[p.second] = ST_MEMBER
                unsealed.add(p.second)
                admitted++
            } else status[p.second] = ST_FULL
        }

        val memberSet = members.toHashSet()
        val msgs = ArrayList<Msg>()
        for (e in evs) {
            if (!e.d.startsWith(D.MSG) || e.tag("g") != gref) continue
            if (e.pubkey !in memberSet || e.pubkey in muted || e.id in deleted) continue
            if (e.createdAt > now + 300) continue
            val o = obj(Seal.open(e.content, key)) ?: continue
            val text = clean(o.optString("t"), 500)
            if (text.isEmpty()) continue
            msgs.add(Msg(e.id, e.pubkey, text, e.createdAt))
        }
        msgs.sortWith(Comparator { a, b -> if (a.at != b.at) a.at.compareTo(b.at) else a.id.compareTo(b.id) })
        val tail = if (msgs.size > 200) msgs.subList(msgs.size - 200, msgs.size).toList() else msgs

        return GroupView(def, members, joinAt, pending.map { it.second }, banned, muted, tail, status, unsealed)
    }
}

// ---------------------------------------------------------------------------
// Filters, local store and gossip anti-entropy
// ---------------------------------------------------------------------------

data class Filter(
    val authors: List<String>? = null,
    val tags: Map<String, List<String>> = emptyMap(),
    val since: Long? = null,
    val limit: Int = 500,
) {
    fun matches(e: Event): Boolean {
        if (e.kind != KIND) return false
        if (authors != null && e.pubkey !in authors) return false
        if (since != null && e.createdAt < since) return false
        for ((k, vals) in tags) {
            if (e.tags.none { it.size >= 2 && it[0] == k && it[1] in vals }) return false
        }
        return true
    }

    fun toJson(): JSONObject {
        val o = JSONObject().put("kinds", JSONArray().put(KIND))
        if (authors != null) o.put("authors", JSONArray(authors))
        for ((k, v) in tags) o.put("#$k", JSONArray(v))
        if (since != null) o.put("since", since)
        o.put("limit", limit)
        return o
    }
}

object Gossip {
    /**
     * Anti-entropy: after a relay answered a subscription, anything we hold that matches the same
     * filters but the relay did not return is something that relay lacks. Re-publishing it lets a phone
     * that saw an event heal every relay it can reach, so data spreads even if its author went offline.
     */
    fun missing(local: Collection<Event>, filters: List<Filter>, relayHas: Set<String>, cap: Int): List<Event> {
        val limitSum = filters.sumOf { it.limit }
        if (relayHas.size >= limitSum) return emptyList() // the answer was truncated; can't tell what is missing
        val out = ArrayList<Event>()
        for (e in local) if (e.id !in relayHas && filters.any { it.matches(e) }) out.add(e)
        out.sortByDescending { it.createdAt }
        return if (out.size > cap) out.subList(0, cap).toList() else out
    }
}

class EventStore {
    private val byKey = HashMap<String, Event>()
    private val valid = LinkedHashSet<String>()
    private val bad = LinkedHashSet<String>()

    private fun key(e: Event) = e.pubkey + "|" + e.kind + "|" + e.d

    fun isValid(id: String) = id in valid
    fun isBad(id: String) = id in bad

    fun markValid(id: String) {
        valid.add(id)
        if (valid.size > 40000) valid.remove(valid.first())
    }

    fun markBad(id: String) {
        bad.add(id)
        if (bad.size > 5000) bad.remove(bad.first())
    }

    /** Replaceable semantics: the newest event per (author, d) wins. Returns true if the store changed. */
    fun put(e: Event): Boolean {
        val k = key(e)
        val old = byKey[k]
        if (old != null && !newer(e, old)) return false
        byKey[k] = e
        return true
    }

    fun get(pub: String, d: String): Event? = byKey[pub + "|" + KIND + "|" + d]
    fun all(): Collection<Event> = byKey.values
    fun size() = byKey.size
    fun contains(id: String) = byKey.values.any { it.id == id }

    fun removeIf(pred: (Event) -> Boolean): Int {
        val it = byKey.entries.iterator()
        var n = 0
        while (it.hasNext()) {
            if (pred(it.next().value)) {
                it.remove()
                n++
            }
        }
        return n
    }

    fun toLines(): List<String> = byKey.values.map { Nostr.toJson(it).toString() }

    fun load(lines: List<String>) {
        for (l in lines) {
            val e = try { Nostr.parse(JSONObject(l)) } catch (ex: Exception) { null } ?: continue
            put(e)
            markValid(e.id)
        }
    }

    /** Keeps storage bounded: old chat, stale presence, and other people's data we no longer need. */
    fun prune(now: Long, keepPubs: Set<String>, keepGroups: Set<String>) {
        val perGroupMsgs = HashMap<String, MutableList<Event>>()
        removeIf { e ->
            val d = e.d
            when {
                d.startsWith(D.MSG) -> {
                    val g = e.tag("g") ?: ""
                    if (now - e.createdAt > 30 * 86400L || g !in keepGroups) true
                    else {
                        perGroupMsgs.getOrPut(g) { ArrayList() }.add(e)
                        false
                    }
                }
                d == D.LIVE -> now - e.createdAt > 86400L
                d.startsWith(D.GROUP) -> (e.tag("g") ?: "") !in keepGroups && now - e.createdAt > 3 * 86400L
                d == D.BOARD || d == D.PROFILE || d.startsWith(D.STATS) -> e.pubkey !in keepPubs && now - e.createdAt > 7 * 86400L
                d.startsWith(D.JOIN) || d.startsWith(D.MOD) -> (e.tag("g") ?: "") !in keepGroups
                else -> false
            }
        }
        for ((_, list) in perGroupMsgs) {
            if (list.size > 400) {
                val drop = list.sortedBy { it.createdAt }.take(list.size - 400).map { it.id }.toHashSet()
                removeIf { it.id in drop }
            }
        }
    }
}
