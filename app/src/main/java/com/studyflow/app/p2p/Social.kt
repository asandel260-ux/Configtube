package com.studyflow.app.p2p

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.studyflow.app.Store
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

val DEFAULT_RELAYS = listOf(
    "wss://relay.damus.io",
    "wss://nos.lol",
    "wss://relay.primal.net",
    "wss://relay.nostr.band",
    "wss://offchain.pub",
)

data class SocialCfg(
    val name: String = "",
    val bio: String = "",
    val color: Int = 5,
    val glyph: Int = 0,
    val share: Boolean = false,
    val detail: Int = 1,          // 0 = daily totals only, 1 = full session details
    val manual: Boolean = false,  // count manually added sessions on shared stats
    val board: Boolean = false,   // appear on the global leaderboard
    val keepLive: Boolean = true, // stay connected in the background while a timer is running
    val relays: List<String> = DEFAULT_RELAYS,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("bio", bio).put("color", color).put("glyph", glyph)
        .put("share", share).put("detail", detail).put("manual", manual).put("board", board)
        .put("keepLive", keepLive).put("relays", JSONArray(relays))

    companion object {
        fun fromJson(o: JSONObject): SocialCfg {
            val d = SocialCfg()
            val r = o.optJSONArray("relays")
            val relays = if (r == null) d.relays else (0 until r.length()).map { r.optString(it) }.filter { Relays.valid(it) }
            return SocialCfg(
                clean(o.optString("name"), 24), clean(o.optString("bio"), 160),
                o.optInt("color", d.color), o.optInt("glyph", d.glyph),
                o.optBoolean("share", d.share), o.optInt("detail", d.detail).coerceIn(0, 1),
                o.optBoolean("manual", d.manual), o.optBoolean("board", d.board),
                o.optBoolean("keepLive", d.keepLive), relays.distinct().take(8),
            )
        }
    }
}

data class JoinedGroup(val gref: String, val secret: String?)

class Snapshot(
    val profiles: Map<String, Profile>,
    val live: Map<String, Live>,
    val groups: Map<String, GroupView>,
    val stats: Map<String, StatsIndex>,
    val mine: StatsIndex,
    val board: List<BoardEntry>,
    val rev: Long,
) {
    companion object {
        val EMPTY = Snapshot(emptyMap(), emptyMap(), emptyMap(), emptyMap(), StatsIndex.EMPTY, emptyList(), 0)
    }
}

data class NetStatus(val open: Int = 0, val total: Int = 0, val updated: Long = 0)
data class DirState(val loading: Boolean = false, val items: List<GroupDef> = emptyList(), val searched: Boolean = false)

private class SubSpec(val id: String, val filters: List<Filter>, val heal: Boolean)
private class Query(val filters: List<Filter>, val asked: Set<String>, val done: (List<Event>) -> Unit) {
    val results = HashMap<String, Event>()
    val eose = HashSet<String>()
    var finished = false
}

/**
 * The social engine. Phones are the servers: each phone stores every signed record of the groups it
 * belongs to, answers its own UI from that local copy, and re-seeds relays that are missing data
 * (see [Gossip]). Relays are only dumb, replaceable mailboxes used to reach phones that are not online
 * at the same moment.
 */
class Social private constructor(ctx: Context) : RelayPool.Listener {
    companion object {
        @Volatile private var inst: Social? = null
        fun get(ctx: Context): Social = inst ?: synchronized(this) {
            inst ?: Social(ctx.applicationContext).also { inst = it }
        }
    }

    private val app = ctx.applicationContext
    private val ex = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sf-social").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("studyflow_social", Context.MODE_PRIVATE)
    private val store = EventStore()
    private val eventsFile = File(app.filesDir, "social-events.jsonl")
    private val pool = RelayPool(this)

    private val secret: ByteArray = loadSecret()
    val me: String = Hex.enc(Schnorr.pubKey(secret))

    private val initCfg = loadCfg()
    @Volatile private var cfgBox: SocialCfg = initCfg
    @Volatile private var joinedBox: List<JoinedGroup> = loadGroups()

    var cfg by mutableStateOf(initCfg); private set
    var joined by mutableStateOf(joinedBox); private set
    var snap by mutableStateOf(Snapshot.EMPTY); private set
    var net by mutableStateOf(NetStatus()); private set
    var directory by mutableStateOf(DirState()); private set

    // ---- engine-thread state ----
    private val pendingHints = ArrayList<String>()
    @Volatile private var settledFlag = false
    private val holders = HashSet<String>()
    private var online = false
    private val active = HashMap<String, SubSpec>()
    private val received = HashMap<String, HashSet<String>>()
    private val eoseMap = HashMap<String, HashSet<String>>()
    private val queries = HashMap<String, Query>()
    private var queryN = 0
    private val outbox = LinkedHashSet<String>()
    private val peeks = HashMap<String, String?>()
    private val watchers = HashMap<String, Int>()
    private val keyCache = HashMap<String, ByteArray>()
    private val statsCache = HashMap<String, Map<Int, DayStat>?>()
    private var groupsBox: Map<String, GroupView> = emptyMap()
    private var memberKey = ""
    private var localRaw: List<RawSession> = emptyList()
    private var rebuildPending = false
    private var statsPending = false
    private var persistPending = false
    private var profilePending = false
    private var rev = 0L
    private var liveOn = false
    private var liveSubject = ""
    private var liveSince = 0L
    private var liveMode = 0
    private var lastHeartbeat = 0L
    private var tick = 0
    private var mineIdx = StatsIndex.EMPTY
    private var lastBoardHash = ""

    init {
        ex.execute {
            try {
                if (eventsFile.exists()) store.load(eventsFile.readLines())
            } catch (e: Exception) {
            }
            outbox.addAll(prefs.getStringSet("outbox", emptySet()) ?: emptySet())
            try {
                val st = Store(app)
                val names = st.loadSubjects().associate { it.id to it.name }
                localRaw = st.loadSessions().map { RawSession(it.start, it.durSec, names[it.subjectId] ?: "", it.mode, it.manual) }
            } catch (e: Exception) {
            }
            rebuild()
            Background.ensure(app, needsBackground())
        }
        ex.scheduleWithFixedDelay({ try { housekeeping() } catch (e: Exception) { } }, 60, 60, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private fun loadSecret(): ByteArray {
        val h = prefs.getString("sec", null)
        if (h != null && Hex.isHex(h, 64)) return Hex.dec(h)
        val s = Schnorr.newSecret()
        prefs.edit().putString("sec", Hex.enc(s)).apply()
        return s
    }

    private fun loadCfg(): SocialCfg = try {
        prefs.getString("cfg", null)?.let { SocialCfg.fromJson(JSONObject(it)) } ?: SocialCfg()
    } catch (e: Exception) {
        SocialCfg()
    }

    private fun loadGroups(): List<JoinedGroup> = try {
        val a = JSONArray(prefs.getString("groups", "[]"))
        (0 until a.length()).mapNotNull {
            val o = a.getJSONObject(it)
            val g = o.getString("g")
            if (GRef.valid(g)) JoinedGroup(g, o.optString("s").ifEmpty { null }) else null
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveGroups(l: List<JoinedGroup>) {
        val a = JSONArray()
        for (g in l) a.put(JSONObject().put("g", g.gref).put("s", g.secret ?: ""))
        prefs.edit().putString("groups", a.toString()).apply()
    }

    private fun setJoined(l: List<JoinedGroup>) {
        joinedBox = l
        saveGroups(l)
        main.post { joined = l }
    }

    private fun schedulePersist() {
        if (persistPending) return
        persistPending = true
        ex.schedule({
            persistPending = false
            try {
                val tmp = File(app.filesDir, "social-events.tmp")
                tmp.writeText(store.toLines().joinToString("\n"))
                tmp.renameTo(eventsFile)
                prefs.edit().putStringSet("outbox", HashSet(outbox)).apply()
            } catch (e: Exception) {
            }
        }, 3, TimeUnit.SECONDS)
    }

    private fun loadHashes(): MutableMap<String, String> = try {
        val o = JSONObject(prefs.getString("hashes", "{}"))
        o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap()
    } catch (e: Exception) {
        HashMap()
    }

    private fun saveHashes(m: Map<String, String>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        prefs.edit().putString("hashes", o.toString()).apply()
    }

    // ------------------------------------------------------------------
    // Connectivity: connections exist only while someone needs them
    // ------------------------------------------------------------------

    fun acquire(tag: String) = ex.execute {
        holders.add(tag)
        goOnline()
    }

    fun release(tag: String) = ex.execute {
        holders.remove(tag)
        if (holders.isEmpty()) ex.schedule({ if (holders.isEmpty()) goOffline() }, 20, TimeUnit.SECONDS)
    }

    private fun relayUrls(): List<String> {
        val extra = ArrayList<String>()
        for (v in groupsBox.values) extra.addAll(v.def.relays)
        for (g in peeks.keys) groupsBox[g]?.def?.relays?.let { extra.addAll(it) }
        return (cfgBox.relays + extra + pendingHints).distinct().take(10)
    }

    private fun goOnline() {
        if (!online) {
            online = true
            received.clear()
            eoseMap.clear()
        }
        pool.setRelays(relayUrls())
        applySubs()
        updateNet()
    }

    private fun goOffline() {
        online = false
        pool.stop()
        for (id in active.keys.toList()) pool.close(id)
        active.clear()
        eoseMap.clear()
        received.clear()
        updateNet()
        persistNow()
    }

    private fun persistNow() {
        try {
            store.prune(nowSec(), interestPubs(), watchedGroups())
            val tmp = File(app.filesDir, "social-events.tmp")
            tmp.writeText(store.toLines().joinToString("\n"))
            tmp.renameTo(eventsFile)
            prefs.edit().putStringSet("outbox", HashSet(outbox)).apply()
        } catch (e: Exception) {
        }
    }

    private fun updateNet() {
        val n = NetStatus(pool.openCount(), pool.totalCount(), System.currentTimeMillis())
        main.post { net = n }
    }

    fun relayStatus(): Map<String, Boolean> = pool.status()

    /** Waits (for background workers) until relays have answered the core subscriptions, or the time is up. */
    suspend fun syncOnce(maxMs: Long) {
        acquire("worker")
        try {
            ex.execute { scheduleStats(); scheduleProfile() }
            val end = System.currentTimeMillis() + maxMs
            delay(2500)
            while (System.currentTimeMillis() < end) {
                if (settled()) break
                delay(500)
            }
            delay(800)
        } finally {
            release("worker")
        }
    }

    private fun settled(): Boolean = settledFlag

    private fun computeSettled() {
        val open = pool.openUrls()
        settledFlag = open.isNotEmpty() && active.values.all { s -> open.all { u -> eoseMap[s.id]?.contains(u) == true } }
    }

    // ------------------------------------------------------------------
    // Relay callbacks (arrive on network threads)
    // ------------------------------------------------------------------

    override fun onOpen(url: String) {
        ex.execute {
            received.keys.removeAll { it.endsWith("|$url") }
            for (s in eoseMap.values) s.remove(url)
            if (outbox.isNotEmpty()) {
                val byId = store.all().associateBy { it.id }
                for (id in outbox.toList()) byId[id]?.let { pool.publishTo(url, it) }
            }
            updateNet()
            computeSettled()
        }
    }

    override fun onClosed(url: String) {
        ex.execute { updateNet() }
    }

    override fun onEvent(url: String, sub: String, e: Event) {
        ex.execute {
            received.getOrPut("$sub|$url") { HashSet() }.add(e.id)
            val r = ingest(e)
            if (r == 0) return@execute
            queries[sub]?.results?.put(e.id, e)
            if (r == 2) scheduleRebuild()
        }
    }

    override fun onEose(url: String, sub: String) {
        ex.execute {
            eoseMap.getOrPut(sub) { HashSet() }.add(url)
            active[sub]?.let { if (it.heal) heal(url, it) }
            queries[sub]?.let { q ->
                q.eose.add(url)
                if (q.asked.all { it in q.eose }) finishQuery(sub)
            }
            computeSettled()
        }
    }

    override fun onOk(url: String, id: String, ok: Boolean, msg: String) {
        if (!ok) return
        ex.execute {
            if (outbox.remove(id)) schedulePersist()
        }
    }

    private fun heal(url: String, spec: SubSpec) {
        val got = received["${spec.id}|$url"] ?: emptySet<String>()
        for (e in Gossip.missing(store.all(), spec.filters, got, 40)) pool.publishTo(url, e)
    }

    /** 0 = rejected, 1 = valid but nothing new, 2 = valid and stored. */
    private fun ingest(e: Event): Int {
        if (store.isValid(e.id)) return 1
        if (store.isBad(e.id)) return 0
        val ok = e.kind == KIND &&
            e.tags.any { it.size >= 2 && it[0] == "t" && it[1] == APP_TAG } &&
            e.createdAt <= nowSec() + 3600 &&
            Nostr.verify(e)
        if (!ok) {
            store.markBad(e.id)
            return 0
        }
        store.markValid(e.id)
        if (!store.put(e)) return 1
        schedulePersist()
        return 2
    }

    // ------------------------------------------------------------------
    // One-shot queries (directory, global board)
    // ------------------------------------------------------------------

    private fun runQuery(filters: List<Filter>, timeoutMs: Long, done: (List<Event>) -> Unit) {
        val id = "q" + (++queryN)
        val q = Query(filters, pool.openUrls().toSet(), done)
        queries[id] = q
        pool.req(id, filters)
        ex.schedule({ finishQuery(id) }, timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun finishQuery(id: String) {
        val q = queries[id] ?: return
        if (q.finished) return
        q.finished = true
        queries.remove(id)
        pool.close(id)
        val list = q.results.values.toList()
        main.post { q.done(list) }
        scheduleRebuild()
    }

    fun searchDirectory(text: String, cat: String?) {
        directory = DirState(loading = true, items = directory.items, searched = true)
        ex.execute {
            goOnlineTemp()
            val now = nowSec()
            val tokens = GroupCodec.tokens(text)
            val f = when {
                tokens.isNotEmpty() -> Filter(tags = mapOf("t" to tokens.map { "sfn:$it" }), limit = 120)
                cat != null && cat != "all" -> Filter(tags = mapOf("t" to listOf("sfc:$cat")), limit = 120)
                else -> Filter(tags = mapOf("t" to listOf(T_DIR)), since = now - 180 * 86400L, limit = 200)
            }
            runQuery(listOf(f), 7000) { events ->
                val defs = HashMap<String, GroupDef>()
                for (e in events) {
                    if (!e.d.startsWith(D.GROUP)) continue
                    val g = e.tag("g") ?: continue
                    if (!GRef.valid(g) || GRef.founder(g) != e.pubkey || e.d != D.group(GRef.gid(g))) continue
                    val def = GroupCodec.def(e, g, null) ?: continue
                    if (def.closed || def.isPrivate) continue
                    val cur = defs[g]
                    if (cur == null || def.at > cur.at) defs[g] = def
                }
                val q = text.trim().lowercase()
                val list = defs.values.filter { cat == null || cat == "all" || it.cat == cat }
                    .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.desc.lowercase().contains(q) }
                    .sortedByDescending { it.at }
                directory = DirState(loading = false, items = list, searched = true)
            }
        }
    }

    private fun goOnlineTemp() {
        // Queries need a connection even if the UI has not acquired one (it normally has).
        if (!online) {
            acquire("query")
            ex.schedule({ release("query") }, 12, TimeUnit.SECONDS)
        }
    }

    fun refreshGlobal(done: () -> Unit) {
        ex.execute {
            goOnlineTemp()
            val now = nowSec()
            runQuery(listOf(Filter(tags = mapOf("t" to listOf(T_BOARD)), since = now - 3 * 86400L, limit = 500)), 7000) { events ->
                val pubs = events.filter { it.d == D.BOARD }.map { it.pubkey }.distinct().take(300)
                if (pubs.isEmpty()) {
                    done()
                } else {
                    ex.execute {
                        runQuery(
                            pubs.chunked(50).map { Filter(authors = it, tags = mapOf("d" to listOf(D.PROFILE)), limit = 100) },
                            6000,
                        ) { done() }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Subscriptions
    // ------------------------------------------------------------------

    private fun watchedGroups(): Set<String> = (joinedBox.map { it.gref } + peeks.keys).toSet()

    private fun interestPubs(): Set<String> {
        val s = HashSet<String>()
        s.add(me)
        for (v in groupsBox.values) s.addAll(v.members)
        s.addAll(watchers.keys)
        return s
    }

    private fun applySubs() {
        if (!online) return
        val want = LinkedHashMap<String, SubSpec>()
        want["me"] = SubSpec("me", listOf(Filter(authors = listOf(me), tags = mapOf("t" to listOf(APP_TAG)), limit = 500)), true)
        val grefs = watchedGroups().take(14)
        if (grefs.isNotEmpty()) {
            want["grp"] = SubSpec(
                "grp",
                listOf(
                    Filter(tags = mapOf("g" to grefs, "t" to listOf(T_CORE)), limit = 400),
                    Filter(tags = mapOf("g" to grefs, "t" to listOf(T_MSG)), since = nowSec() - 14 * 86400L, limit = 300),
                ),
                true,
            )
        }
        val others = interestPubs().filter { it != me && it !in watchers.keys }.sorted()
        if (others.isNotEmpty()) {
            val today = LocalDate.now()
            val ds = listOf(D.PROFILE, D.LIVE, D.stats(ymOf(today)), D.stats(ymOf(today.minusMonths(1))))
            want["mem"] = SubSpec("mem", others.take(160).chunked(40).map { Filter(authors = it, tags = mapOf("d" to ds), limit = 400) }, true)
        }
        if (watchers.isNotEmpty()) {
            val today = LocalDate.now()
            val ds = listOf(D.PROFILE, D.LIVE) + (0..11).map { D.stats(ymOf(today.minusMonths(it.toLong()))) }
            want["pf"] = SubSpec("pf", watchers.keys.sorted().take(10).map { Filter(authors = listOf(it), tags = mapOf("d" to ds), limit = 60) }, true)
        }
        for (id in active.keys.toList()) if (id !in want) {
            pool.close(id)
            active.remove(id)
            eoseMap.remove(id)
        }
        for ((id, spec) in want) {
            val cur = active[id]
            if (cur == null || cur.filters != spec.filters) {
                active[id] = spec
                eoseMap.remove(id)
                pool.req(id, spec.filters)
            }
        }
        computeSettled()
    }

    fun watchProfile(pub: String): () -> Unit {
        ex.execute {
            watchers[pub] = (watchers[pub] ?: 0) + 1
            applySubs()
        }
        return {
            ex.execute {
                val n = (watchers[pub] ?: 1) - 1
                if (n <= 0) watchers.remove(pub) else watchers[pub] = n
                applySubs()
            }
        }
    }

    fun peek(inv: Invite) {
        ex.execute {
            peeks[inv.gref] = inv.secret
            for (r in inv.relays) if (r !in pendingHints) pendingHints.add(r)
            if (online) {
                pool.setRelays(relayUrls())
                applySubs()
            }
            scheduleRebuild()
        }
    }

    fun unpeek(gref: String) {
        ex.execute {
            peeks.remove(gref)
            applySubs()
        }
    }

    // ------------------------------------------------------------------
    // Derived state for the UI
    // ------------------------------------------------------------------

    private fun scheduleRebuild() {
        if (rebuildPending) return
        rebuildPending = true
        ex.schedule({
            rebuildPending = false
            try { rebuild() } catch (e: Exception) { }
        }, 150, TimeUnit.MILLISECONDS)
    }

    private fun keyFor(gref: String): ByteArray? {
        val sec = joinedBox.firstOrNull { it.gref == gref }?.secret ?: peeks[gref] ?: return null
        return keyCache.getOrPut(sec) { Seal.key(sec) }
    }

    private fun rebuild() {
        val now = nowSec()
        val all = store.all()
        val profiles = HashMap<String, Profile>()
        val live = HashMap<String, Live>()
        val board = ArrayList<BoardEntry>()
        val byG = HashMap<String, MutableList<Event>>()
        val statsEv = HashMap<String, MutableMap<String, Event>>()
        for (e in all) {
            val d = e.d
            when {
                d == D.PROFILE -> Codec.profile(e)?.let { profiles[e.pubkey] = it }
                d == D.LIVE -> Codec.live(e)?.let { live[e.pubkey] = it }
                d == D.BOARD -> if (now - e.createdAt < 3 * 86400L) Codec.board(e)?.let { board.add(it) }
                d.startsWith(D.STATS) -> statsEv.getOrPut(e.pubkey) { HashMap() }[d.removePrefix(D.STATS)] = e
            }
            val g = e.tag("g")
            if (g != null) byG.getOrPut(g) { ArrayList() }.add(e)
        }

        val groups = HashMap<String, GroupView>()
        for (g in watchedGroups()) {
            GroupLogic.derive(g, byG[g] ?: emptyList(), keyFor(g), now)?.let { groups[g] = it }
        }
        groupsBox = groups

        val interest = interestPubs()
        val stats = HashMap<String, StatsIndex>()
        for ((pub, months) in statsEv) {
            if (pub !in interest || pub == me) continue
            val m = HashMap<String, Map<Int, DayStat>>()
            for ((ym, e) in months) {
                val dec = statsCache.getOrPut(e.id) { StatsCodec.decodeMonth(e.content) }
                if (dec != null) m[ym] = dec
            }
            stats[pub] = StatsIndex(m)
        }
        rebuildMine()
        stats[me] = mineIdx

        rev++
        val s = Snapshot(profiles, live, groups, stats, mineIdx, board, rev)
        main.post { snap = s }

        // membership of any group changed -> refresh the member subscription
        val mk = interest.sorted().joinToString(",")
        if (mk != memberKey) {
            memberKey = mk
            applySubs()
        }
        // groups that were closed by their founder disappear from the local list
        val closed = joinedBox.filter { jg -> groups[jg.gref]?.def?.closed == true }
        if (closed.isNotEmpty()) setJoined(joinedBox.filterNot { jg -> closed.any { it.gref == jg.gref } })
        sealMembers()
        if (statsCache.size > 600) statsCache.clear()
    }

    private fun rebuildMine() {
        val c = cfgBox
        val raws = localRaw.filter { c.manual || !it.manual }
        mineIdx = StatsIndex(StatsBuild.build(raws, ZoneId.systemDefault(), c.detail == 1))
    }

    /** A moderator's phone seals freshly admitted members so that later joiners cannot displace them. */
    private fun sealMembers() {
        for ((g, v) in groupsBox) {
            if (!v.def.isMod(me) || v.unsealed.isEmpty()) continue
            if (v.def.closed) continue
            applyActs(g, v.unsealed, Acts.OK)
        }
    }

    // ------------------------------------------------------------------
    // Publishing
    // ------------------------------------------------------------------

    private fun nowSec() = System.currentTimeMillis() / 1000

    private fun publish(tags: List<List<String>>, content: String): Event {
        val d = tags.firstOrNull { it[0] == "d" }?.get(1) ?: ""
        val prev = store.get(me, d)
        val at = maxOf(nowSec(), (prev?.createdAt ?: 0L) + 1)
        val e = Nostr.create(secret, at, tags, content)
        store.markValid(e.id)
        store.put(e)
        outbox.add(e.id)
        pool.publishAll(e)
        schedulePersist()
        scheduleRebuild()
        return e
    }

    private fun scheduleProfile() {
        if (profilePending) return
        profilePending = true
        ex.schedule({
            profilePending = false
            publishProfile()
        }, 1500, TimeUnit.MILLISECONDS)
    }

    private fun publishProfile() {
        val c = cfgBox
        if (c.name.isBlank()) return
        publish(
            listOf(listOf("d", D.PROFILE), listOf("t", APP_TAG)),
            Codec.profileJson(c.name, c.bio, c.color, c.glyph),
        )
    }

    private fun scheduleStats() {
        if (statsPending) return
        statsPending = true
        ex.schedule({
            statsPending = false
            try {
                rebuildMine()
                publishStats(false)
                publishBoard(false)
                scheduleRebuild()
            } catch (e: Exception) {
            }
        }, 12, TimeUnit.SECONDS)
    }

    private fun publishStats(force: Boolean) {
        val c = cfgBox
        if (!c.share || c.name.isBlank()) return
        val raws = localRaw.filter { c.manual || !it.manual }
        val months = StatsBuild.build(raws, ZoneId.systemDefault(), c.detail == 1)
        val cutoff = ymOf(LocalDate.now().minusMonths(11))
        val hashes = loadHashes()
        for (ym in (months.keys + hashes.keys).filter { it >= cutoff }.distinct().sorted()) {
            val days = months[ym] ?: emptyMap()
            if (days.isEmpty() && hashes[ym] == null) continue
            val content = StatsCodec.encodeMonth(days)
            val h = Hex.enc(sha256(content.toByteArray(Charsets.UTF_8))).take(16)
            if (!force && hashes[ym] == h) continue
            publish(listOf(listOf("d", D.stats(ym)), listOf("t", APP_TAG)), content)
            hashes[ym] = h
        }
        saveHashes(hashes)
    }

    private fun publishBoard(force: Boolean) {
        val c = cfgBox
        val on = c.share && c.board && c.name.isNotBlank()
        val today = LocalDate.now()
        val content = if (on) {
            Codec.boardJson(
                today.toString(), secFor(mineIdx, Period.DAY, today),
                isoWeekKey(today), secFor(mineIdx, Period.WEEK, today),
                "%04d-%02d".format(today.year, today.monthValue), secFor(mineIdx, Period.MONTH, today),
            )
        } else Codec.boardOffJson()
        val h = Hex.enc(sha256(content.toByteArray(Charsets.UTF_8))).take(16)
        if (!force && h == lastBoardHash) return
        if (!on && lastBoardHash.isEmpty() && prefs.getBoolean("boardPublished", false).not()) return
        lastBoardHash = h
        prefs.edit().putBoolean("boardPublished", on).apply()
        publish(listOf(listOf("d", D.BOARD), listOf("t", APP_TAG), listOf("t", T_BOARD)), content)
    }

    private fun publishLive(on: Boolean) {
        val c = cfgBox
        if (!c.share || c.name.isBlank()) return
        publish(
            listOf(listOf("d", D.LIVE), listOf("t", APP_TAG)),
            Codec.liveJson(on, if (c.detail == 1) liveSubject else "", liveSince / 1000, liveMode),
        )
        lastHeartbeat = nowSec()
    }

    private fun housekeeping() {
        tick++
        if (liveOn && nowSec() - lastHeartbeat >= 240) publishLive(true)
        if (tick % 10 == 0) {
            publishBoard(false)
            if (online) persistNow()
        }
    }

    // ------------------------------------------------------------------
    // Actions from the UI (call from the main thread)
    // ------------------------------------------------------------------

    fun updateCfg(f: (SocialCfg) -> SocialCfg) {
        val old = cfgBox
        val n0 = f(old)
        val n = n0.copy(
            name = clean(n0.name, 24), bio = clean(n0.bio, 160),
            relays = n0.relays.filter { Relays.valid(it) }.distinct().take(8).ifEmpty { DEFAULT_RELAYS },
        )
        cfgBox = n
        prefs.edit().putString("cfg", n.toJson().toString()).apply()
        cfg = n
        ex.execute {
            if (old.relays != n.relays && online) pool.setRelays(relayUrls())
            if (old.name != n.name || old.bio != n.bio || old.color != n.color || old.glyph != n.glyph) scheduleProfile()
            if (old.share && !n.share) retract()
            if (n.share && (!old.share || old.detail != n.detail || old.manual != n.manual)) {
                if (old.detail != n.detail || old.manual != n.manual) saveHashes(emptyMap())
                rebuildMine()
                scheduleStats()
                if (liveOn) publishLive(true)
            }
            if (old.board != n.board) publishBoard(true)
            Background.ensure(app, needsBackground())
            scheduleRebuild()
        }
    }

    /** Sharing switched off: overwrite what we published with empty records. Copies already held by others cannot be recalled. */
    private fun retract() {
        val hashes = loadHashes()
        for (ym in hashes.keys.toList()) {
            publish(listOf(listOf("d", D.stats(ym)), listOf("t", APP_TAG)), StatsCodec.encodeMonth(emptyMap()))
        }
        saveHashes(emptyMap())
        publish(listOf(listOf("d", D.LIVE), listOf("t", APP_TAG)), Codec.liveJson(false, "", 0, 0))
        publish(listOf(listOf("d", D.BOARD), listOf("t", APP_TAG), listOf("t", T_BOARD)), Codec.boardOffJson())
        lastBoardHash = ""
        prefs.edit().putBoolean("boardPublished", false).apply()
    }

    fun sessionsChanged(raws: List<RawSession>) {
        ex.execute {
            localRaw = raws
            rebuildMine()
            scheduleRebuild()
            scheduleStats()
        }
    }

    fun liveChanged(on: Boolean, subject: String, sinceMs: Long, mode: Int) {
        ex.execute {
            val changed = on != liveOn || subject != liveSubject
            liveOn = on
            liveSubject = subject
            liveSince = sinceMs
            liveMode = mode
            if (changed) publishLive(on)
        }
    }

    fun needsBackground(): Boolean = joinedBox.isNotEmpty() || (cfgBox.share && cfgBox.board)

    // ---- groups ----

    fun createGroup(name: String, desc: String, cat: String, vis: Int, join: Int, max: Int): Invite {
        val gid = randHex(8)
        val gref = GRef.make(me, gid)
        val secretHex = if (vis == VIS_PRIVATE) randHex(16) else null
        val def = GroupDef(
            gref, me, gid, clean(name, 40), clean(desc, 240), cat, vis, join, max.coerceIn(2, MAX_MEMBERS),
            emptyList(), "", cfgBox.relays.take(2), false, nowSec(),
        )
        val inv = Invite(gref, secretHex, def.relays)
        setJoined(joinedBox + JoinedGroup(gref, secretHex))
        ex.execute {
            publishDef(def, secretHex?.let { Seal.key(it) })
            publishProfile()
            if (online) applySubs()
            Background.ensure(app, true)
        }
        return inv
    }

    private fun publishDef(def: GroupDef, key: ByteArray?) {
        publish(GroupCodec.defTags(def), Seal.seal(GroupCodec.defJson(def), key))
    }

    fun inviteFor(gref: String): Invite? {
        val secret = joinedBox.firstOrNull { it.gref == gref }?.secret ?: peeks[gref]
        val relays = groupsBox[gref]?.def?.relays ?: emptyList()
        return Invite(gref, secret, relays)
    }

    fun joinGroup(inv: Invite) {
        if (joinedBox.size >= 8 && joinedBox.none { it.gref == inv.gref }) return
        if (cfgBox.name.isBlank()) return
        if (joinedBox.none { it.gref == inv.gref }) setJoined(joinedBox + JoinedGroup(inv.gref, inv.secret))
        ex.execute {
            peeks.remove(inv.gref)
            for (r in inv.relays) if (r !in pendingHints) pendingHints.add(r)
            val key = inv.secret?.let { Seal.key(it) }
            publish(
                listOf(listOf("d", D.join(inv.gref)), listOf("g", inv.gref), listOf("t", APP_TAG), listOf("t", T_CORE)),
                Seal.seal(GroupCodec.joinJson(true), key),
            )
            publishProfile()
            if (online) {
                pool.setRelays(relayUrls())
                applySubs()
            }
            Background.ensure(app, true)
        }
    }

    fun leaveGroup(gref: String) {
        val key = joinedBox.firstOrNull { it.gref == gref }?.secret?.let { Seal.key(it) }
        setJoined(joinedBox.filterNot { it.gref == gref })
        ex.execute {
            if (GRef.founder(gref) != me) {
                publish(
                    listOf(listOf("d", D.join(gref)), listOf("g", gref), listOf("t", APP_TAG), listOf("t", T_CORE)),
                    Seal.seal(GroupCodec.joinJson(false), key),
                )
            }
            applySubs()
            scheduleRebuild()
            Background.ensure(app, needsBackground())
        }
    }

    /** Removes the group from this phone only (used for groups we were removed from). */
    fun forgetGroup(gref: String) {
        setJoined(joinedBox.filterNot { it.gref == gref })
        ex.execute {
            applySubs()
            scheduleRebuild()
            Background.ensure(app, needsBackground())
        }
    }

    fun editGroup(gref: String, f: (GroupDef) -> GroupDef) {
        ex.execute {
            val cur = groupsBox[gref]?.def ?: return@execute
            if (cur.founder != me) return@execute
            val n0 = f(cur)
            val n = n0.copy(
                name = clean(n0.name, 40).ifEmpty { cur.name }, desc = clean(n0.desc, 240), ann = clean(n0.ann, 200),
                max = n0.max.coerceIn(maxOf(2, groupsBox[gref]?.members?.size ?: 2), MAX_MEMBERS),
                mods = n0.mods.filter { Hex.isHex(it, 64) && it != me }.distinct().take(5),
            )
            val key = keyFor(gref)
            publishDef(n, key)
            if (cur.join == JOIN_OPEN && n.join != JOIN_OPEN) {
                // leaving open mode: seal everyone who is already in so they keep their seat
                val v = groupsBox[gref]
                if (v != null) applyActs(gref, v.members.filter { it != me }, Acts.OK)
            }
        }
    }

    fun deleteGroup(gref: String) {
        editGroup(gref) { it.copy(closed = true) }
        ex.schedule({ setJoined(joinedBox.filterNot { it.gref == gref }) }, 1, TimeUnit.SECONDS)
    }

    /** Moderation: ban / unban / mute / unmute / kick / ok / deny for one person. */
    fun moderate(gref: String, pub: String, act: String) {
        ex.execute { applyActs(gref, listOf(pub), act) }
    }

    fun deleteMessage(gref: String, id: String) {
        ex.execute {
            val def = groupsBox[gref]?.def ?: return@execute
            if (!def.isMod(me)) return@execute
            val cur = myMod(gref)
            cur.second.add(id)
            publishMod(gref, cur.first, cur.second)
        }
    }

    private fun myMod(gref: String): Pair<MutableMap<String, ModAct>, MutableSet<String>> {
        val acts = HashMap<String, ModAct>()
        val del = LinkedHashSet<String>()
        val ev = store.get(me, D.mod(gref))
        val plain = ev?.let { Seal.open(it.content, keyFor(gref)) }
        val parsed = plain?.let { GroupCodec.parseMod(it) }
        if (parsed != null) {
            for (a in parsed.first) acts[Acts.cat(a.act) + a.pub] = a
            del.addAll(parsed.second)
        }
        return Pair(acts, del)
    }

    private fun applyActs(gref: String, targets: List<String>, act: String) {
        val v = groupsBox[gref] ?: return
        val def = v.def
        if (!def.isMod(me) || act !in Acts.ALL) return
        val cur = myMod(gref)
        val ts = nowSec()
        var changed = false
        for (t in targets) {
            if (t == def.founder || t == me) continue
            if (def.isMod(t) && me != def.founder) continue
            val k = Acts.cat(act) + t
            val prev = cur.first[k]
            if (prev != null && prev.act == act && prev.ts > ts - 5) continue
            cur.first[k] = ModAct(t, act, maxOf(ts, (prev?.ts ?: 0L) + 1))
            changed = true
        }
        if (changed) publishMod(gref, cur.first, cur.second)
    }

    private fun publishMod(gref: String, acts: Map<String, ModAct>, del: Set<String>) {
        val key = keyFor(gref)
        publish(
            listOf(listOf("d", D.mod(gref)), listOf("g", gref), listOf("t", APP_TAG), listOf("t", T_CORE)),
            Seal.seal(GroupCodec.modJson(acts.values, del), key),
        )
    }

    fun sendMessage(gref: String, text: String) {
        val t = clean(text, 500)
        if (t.isEmpty()) return
        ex.execute {
            val v = groupsBox[gref] ?: return@execute
            if (v.statusOf(me) != ST_MEMBER || me in v.muted) return@execute
            publish(
                listOf(listOf("d", D.MSG + randHex(8)), listOf("g", gref), listOf("t", APP_TAG), listOf("t", T_MSG)),
                Seal.seal(GroupCodec.msgJson(t), keyFor(gref)),
            )
        }
    }

    // ---- identity ----

    fun secretHex(): String = Hex.enc(secret)

    /** Replaces this phone's identity. The app must be restarted afterwards. */
    fun importIdentity(hex: String): Boolean {
        val h = hex.trim().lowercase()
        if (!Hex.isHex(h, 64)) return false
        try {
            Schnorr.pubKey(Hex.dec(h))
        } catch (e: Exception) {
            return false
        }
        prefs.edit().clear().putString("sec", h).commit()
        try { eventsFile.delete() } catch (e: Exception) { }
        return true
    }
}
