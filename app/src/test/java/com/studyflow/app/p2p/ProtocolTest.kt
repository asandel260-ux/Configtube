package com.studyflow.app.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

private class Key(val sec: ByteArray = Schnorr.newSecret()) {
    val pub: String = Hex.enc(Schnorr.pubKey(sec))
}

private const val GID = "0123456789abcdef"

private fun def(
    f: Key, name: String = "Study club", vis: Int = VIS_PUBLIC, join: Int = JOIN_OPEN, max: Int = MAX_MEMBERS,
    mods: List<String> = emptyList(), at: Long = 100, key: ByteArray? = null, closed: Boolean = false,
): Event {
    val g = GRef.make(f.pub, GID)
    val d = GroupDef(g, f.pub, GID, name, "", "general", vis, join, max, mods, "", emptyList(), closed, at)
    return Nostr.create(f.sec, at, GroupCodec.defTags(d), Seal.seal(GroupCodec.defJson(d), key))
}

private fun gref(f: Key) = GRef.make(f.pub, GID)

private fun joinEv(m: Key, g: String, at: Long, key: ByteArray? = null, join: Boolean = true) = Nostr.create(
    m.sec, at, listOf(listOf("d", D.join(g)), listOf("g", g), listOf("t", APP_TAG), listOf("t", T_CORE)),
    Seal.seal(GroupCodec.joinJson(join), key),
)

private fun modEv(by: Key, g: String, at: Long, acts: List<ModAct>, del: List<String> = emptyList(), key: ByteArray? = null) = Nostr.create(
    by.sec, at, listOf(listOf("d", D.mod(g)), listOf("g", g), listOf("t", APP_TAG), listOf("t", T_CORE)),
    Seal.seal(GroupCodec.modJson(acts, del), key),
)

private fun msgEv(m: Key, g: String, at: Long, text: String, key: ByteArray? = null) = Nostr.create(
    m.sec, at, listOf(listOf("d", D.MSG + randHex(8)), listOf("g", g), listOf("t", APP_TAG), listOf("t", T_MSG)),
    Seal.seal(GroupCodec.msgJson(text), key),
)

private const val NOW = 10_000L

class CryptoTest {
    private fun h(s: String) = Hex.dec(s)

    @Test
    fun bip340Vector0() {
        val sec = h("0000000000000000000000000000000000000000000000000000000000000003")
        val pub = Schnorr.pubKey(sec)
        assertEquals("f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9", Hex.enc(pub))
        val msg = ByteArray(32)
        val sig = Schnorr.sign(msg, sec, ByteArray(32))
        assertEquals(
            "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0",
            Hex.enc(sig),
        )
        assertTrue(Schnorr.verify(msg, pub, sig))
    }

    @Test
    fun bip340Vector1Verifies() {
        val pub = h("dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659")
        val msg = h("243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89")
        val sig = h("6896bd60eeae296db48a229ff71dfe071bde413e6d43f917dc8dcf8c78de33418906d11ac976abccb20b091292bff4ea897efcb639ea871cfa95f6de339e4b0a")
        assertTrue(Schnorr.verify(msg, pub, sig))
        sig[10] = (sig[10].toInt() xor 1).toByte()
        assertFalse(Schnorr.verify(msg, pub, sig))
    }

    @Test
    fun signVerifyRoundTrip() {
        repeat(5) {
            val k = Key()
            val msg = sha256(randHex(8).toByteArray())
            val sig = Schnorr.sign(msg, k.sec)
            assertTrue(Schnorr.verify(msg, Hex.dec(k.pub), sig))
            assertFalse(Schnorr.verify(sha256(msg), Hex.dec(k.pub), sig))
        }
    }

    @Test
    fun eventVerifyAndTamper() {
        val k = Key()
        val e = Nostr.create(k.sec, 123, listOf(listOf("d", "x"), listOf("t", APP_TAG)), "héllo \"quoted\"\nline")
        assertTrue(Nostr.verify(e))
        assertFalse(Nostr.verify(e.copy(content = "other")))
        assertFalse(Nostr.verify(e.copy(createdAt = 124)))
        val other = Key()
        assertFalse(Nostr.verify(e.copy(pubkey = other.pub)))
        // survives a JSON round trip
        val back = Nostr.parse(Nostr.toJson(e))
        assertNotNull(back)
        assertTrue(Nostr.verify(back!!))
    }

    @Test
    fun sealRoundTrip() {
        val key = Seal.key(randHex(16))
        val c = Seal.seal("secret text", key)
        assertEquals("secret text", Seal.open(c, key))
        assertNull(Seal.open(c, Seal.key(randHex(16))))
        assertNull(Seal.open(c, null))
        assertNull(Seal.open("plain", key)) // a keyed group refuses unencrypted content
        assertEquals("plain", Seal.open("plain", null))
    }

    @Test
    fun inviteRoundTrip() {
        val f = Key()
        val inv = Invite(GRef.make(f.pub, GID), randHex(16), listOf("wss://relay.example.com"))
        val parsed = InviteCodec.parse("Join me! " + InviteCodec.link(inv) + " thanks")
        assertEquals(inv, parsed)
        assertEquals(inv.copy(relays = emptyList()), InviteCodec.parse(InviteCodec.code(inv)))
        assertNull(InviteCodec.parse("hello"))
        assertFalse(Relays.valid("ws://insecure.example.com"))
        assertFalse(Relays.valid("wss://evil.com/<script>"))
    }
}

class StatsTest {
    @Test
    fun roundTripAndClamp() {
        val zone = ZoneId.of("UTC")
        val base = LocalDate.of(2026, 10, 5).atTime(9, 30).atZone(zone).toInstant().toEpochMilli()
        val raws = listOf(
            RawSession(base, 1800, "Math", 1, false),
            RawSession(base + 3_600_000, 900, "Bio", 0, false),
        )
        val months = StatsBuild.build(raws, zone, true)
        val day = months["202610"]!![5]!!
        assertEquals(2700L, day.sec)
        assertEquals(570, day.sessions[0].startMin)
        val enc = StatsCodec.encodeMonth(months["202610"]!!)
        val dec = StatsCodec.decodeMonth(enc)!!
        assertEquals(day, dec[5])
        assertEquals(0, StatsBuild.build(raws, zone, false)["202610"]!![5]!!.sessions.size)
    }

    @Test
    fun hostileValuesAreClamped() {
        val evil = """{"v":1,"s":["x"],"d":{"40":{"t":5},"3":{"t":999999999,"x":[[2000,5,0,9],[10,99999999,0,0],[100,60,7,1]]}}}"""
        val dec = StatsCodec.decodeMonth(evil)!!
        assertFalse(dec.containsKey(40))
        assertEquals(86400L, dec[3]!!.sec)
        assertEquals(1, dec[3]!!.sessions.size) // only the plausible session survives
        assertEquals(60, dec[3]!!.sessions[0].durSec)
    }

    @Test
    fun periods() {
        val d = LocalDate.of(2026, 10, 21) // Wednesday
        val idx = StatsIndex(mapOf("202610" to mapOf(19 to DayStat(100, emptyList()), 21 to DayStat(50, emptyList()), 30 to DayStat(7, emptyList()))))
        assertEquals(50L, secFor(idx, Period.DAY, d))
        assertEquals(150L, secFor(idx, Period.WEEK, d))
        assertEquals(157L, secFor(idx, Period.MONTH, d))
    }
}

class GroupTest {
    private fun view(g: String, evs: List<Event>, key: ByteArray? = null, now: Long = NOW) = GroupLogic.derive(g, evs, key, now)

    @Test
    fun openGroupMembersAndCapacity() {
        val f = Key(); val a = Key(); val b = Key(); val c = Key()
        val g = gref(f)
        val v = view(g, listOf(def(f, max = 3), joinEv(a, g, 110), joinEv(b, g, 120), joinEv(c, g, 130)))!!
        assertEquals(listOf(f.pub, a.pub, b.pub), v.members)
        assertEquals(ST_FULL, v.statusOf(c.pub))
        assertEquals(ST_MEMBER, v.statusOf(a.pub))
    }

    @Test
    fun hardCapIsTwenty() {
        val f = Key()
        val g = gref(f)
        val evs = ArrayList<Event>()
        evs.add(def(f, max = 50))
        repeat(25) { evs.add(joinEv(Key(), g, 200L + it)) }
        assertEquals(20, view(g, evs)!!.members.size)
    }

    @Test
    fun forgedEarlyJoinCannotDisplaceSealedMember() {
        val f = Key(); val a = Key(); val b = Key(); val x = Key()
        val g = gref(f)
        val base = listOf(def(f, max = 3), joinEv(a, g, 110), joinEv(b, g, 120))
        val sealedActs = listOf(ModAct(a.pub, Acts.OK, 125), ModAct(b.pub, Acts.OK, 126))
        val evs = base + modEv(f, g, 130, sealedActs) + joinEv(x, g, 50) // attacker backdates the join
        val v = view(g, evs)!!
        assertEquals(listOf(f.pub, a.pub, b.pub), v.members)
        assertEquals(ST_FULL, v.statusOf(x.pub))
        // without sealing, the backdated join would win - which is why moderator phones seal new members
        val unsealed = view(g, base + joinEv(x, g, 50))!!
        assertTrue(x.pub in unsealed.members)
        assertTrue(unsealed.unsealed.contains(a.pub))
    }

    @Test
    fun approvalMode() {
        val f = Key(); val a = Key()
        val g = gref(f)
        val pending = view(g, listOf(def(f, join = JOIN_APPROVAL), joinEv(a, g, 110)))!!
        assertEquals(ST_PENDING, pending.statusOf(a.pub))
        assertEquals(listOf(a.pub), pending.pending)
        val ok = view(g, listOf(def(f, join = JOIN_APPROVAL), joinEv(a, g, 110), modEv(f, g, 120, listOf(ModAct(a.pub, Acts.OK, 120)))))!!
        assertEquals(ST_MEMBER, ok.statusOf(a.pub))
        // an approval signed by a stranger is ignored
        val s = Key()
        val bad = view(g, listOf(def(f, join = JOIN_APPROVAL), joinEv(a, g, 110), modEv(s, g, 120, listOf(ModAct(a.pub, Acts.OK, 120)))))!!
        assertEquals(ST_PENDING, bad.statusOf(a.pub))
    }

    @Test
    fun banKickUnbanAndRejoin() {
        val f = Key(); val a = Key()
        val g = gref(f)
        val base = listOf(def(f), joinEv(a, g, 110))
        assertEquals(ST_BANNED, view(g, base + modEv(f, g, 120, listOf(ModAct(a.pub, Acts.BAN, 120))))!!.statusOf(a.pub))
        // later unban by the same moderator wins (last writer)
        val unban = base + modEv(f, g, 150, listOf(ModAct(a.pub, Acts.BAN, 120), ModAct(a.pub, Acts.UNBAN, 150)))
        assertEquals(ST_MEMBER, view(g, unban)!!.statusOf(a.pub))
        // kick removes, a fresh join afterwards restores (open group)
        val kicked = base + modEv(f, g, 200, listOf(ModAct(a.pub, Acts.KICK, 200)))
        assertEquals(ST_KICKED, view(g, kicked)!!.statusOf(a.pub))
        assertEquals(ST_MEMBER, view(g, kicked + joinEv(a, g, 300))!!.statusOf(a.pub))
    }

    @Test
    fun moderatorsCannotTouchFounderOrEachOther() {
        val f = Key(); val m1 = Key(); val m2 = Key(); val a = Key()
        val g = gref(f)
        val evs = listOf(
            def(f, mods = listOf(m1.pub, m2.pub)), joinEv(a, g, 110), joinEv(m2, g, 111),
            modEv(m1, g, 120, listOf(ModAct(f.pub, Acts.BAN, 120), ModAct(m2.pub, Acts.BAN, 120), ModAct(a.pub, Acts.MUTE, 120))),
        )
        val v = view(g, evs)!!
        assertEquals(ST_MEMBER, v.statusOf(f.pub))
        assertEquals(ST_MEMBER, v.statusOf(m2.pub))
        assertTrue(a.pub in v.muted) // but a moderator may mute an ordinary member
    }

    @Test
    fun chatMuteAndDelete() {
        val f = Key(); val a = Key(); val b = Key(); val outsider = Key()
        val g = gref(f)
        val m1 = msgEv(a, g, 200, "hello")
        val m2 = msgEv(b, g, 210, "spam")
        val m3 = msgEv(outsider, g, 220, "not a member")
        val m4 = msgEv(f, g, 230, "welcome")
        val evs = listOf(def(f), joinEv(a, g, 110), joinEv(b, g, 111), m1, m2, m3, m4)
        assertEquals(listOf("hello", "spam", "welcome"), view(g, evs)!!.msgs.map { it.text })
        val muted = evs + modEv(f, g, 300, listOf(ModAct(b.pub, Acts.MUTE, 300)), listOf(m1.id))
        assertEquals(listOf("welcome"), view(g, muted)!!.msgs.map { it.text })
    }

    @Test
    fun privateGroupNeedsKey() {
        val f = Key(); val a = Key()
        val secret = randHex(16)
        val key = Seal.key(secret)
        val g = gref(f)
        val evs = listOf(def(f, vis = VIS_PRIVATE, key = key), joinEv(a, g, 110, key), msgEv(a, g, 200, "private hello", key))
        assertNull(view(g, evs, null))
        assertNull(view(g, evs, Seal.key(randHex(16))))
        val v = view(g, evs, key)!!
        assertEquals(listOf(f.pub, a.pub), v.members)
        assertEquals("private hello", v.msgs.single().text)
        // an outsider without the key cannot fake a membership
        val x = Key()
        val forged = evs + joinEv(x, g, 120, null)
        assertEquals(ST_NONE, view(g, forged, key)!!.statusOf(x.pub))
        // private groups are not tagged for the public directory
        assertFalse(evs[0].topics.contains(T_DIR))
    }

    @Test
    fun newestDefinitionWinsAndOnlyFounderCounts() {
        val f = Key(); val imposter = Key()
        val g = gref(f)
        val v1 = def(f, name = "Old", at = 100)
        val v2 = def(f, name = "New", at = 200)
        assertEquals("New", view(g, listOf(v1, v2))!!.def.name)
        assertEquals("New", view(g, listOf(v2, v1))!!.def.name)
        // someone else signing a definition for the same group id is not the founder
        val fake = Nostr.create(imposter.sec, 300, GroupCodec.defTags(GroupDef(g, imposter.pub, GID, "Hijack", "", "general", 0, 0, 20, emptyList(), "", emptyList(), false, 300)), GroupCodec.defJson(GroupDef(g, imposter.pub, GID, "Hijack", "", "general", 0, 0, 20, emptyList(), "", emptyList(), false, 300)))
        assertEquals("New", view(g, listOf(v1, v2, fake))!!.def.name)
    }

    @Test
    fun directoryTags() {
        val f = Key()
        val e = def(f, name = "Organic Chemistry Finals")
        assertTrue(e.topics.contains(T_DIR))
        assertTrue(e.topics.contains("sfn:organic"))
        assertTrue(e.topics.contains("sfn:chemistry"))
    }
}

class GossipTest {
    private class Relay {
        val db = HashMap<String, Event>()
        var online = true
        fun query(fs: List<Filter>): List<Event> =
            db.values.filter { e -> fs.any { it.matches(e) } }.sortedByDescending { it.createdAt }.take(fs.sumOf { it.limit })
    }

    private class Phone(val k: Key = Key()) {
        val local = HashMap<String, Event>()
        fun sync(relays: List<Relay>, fs: List<Filter>) {
            for (r in relays) {
                if (!r.online) continue
                val res = r.query(fs)
                for (e in res) if (Nostr.verify(e)) local[e.id] = e
                val has = res.map { it.id }.toSet()
                for (m in Gossip.missing(local.values, fs, has, 40)) r.db[m.id] = m
            }
        }
        fun post(relays: List<Relay>, e: Event) {
            local[e.id] = e
            for (r in relays) if (r.online) r.db[e.id] = e
        }
    }

    @Test
    fun dataCrossesRelaysThroughABridgePhone() {
        val f = Key()
        val g = gref(f)
        val fs = listOf(Filter(tags = mapOf("g" to listOf(g), "t" to listOf(T_CORE)), limit = 400))
        val relayA = Relay(); val relayB = Relay()
        val p1 = Phone(); val p2 = Phone(); val bridge = Phone()

        // p1 only reaches relay A, p2 only reaches relay B, they are never online together
        val e1 = def(f)
        p1.post(listOf(relayA), e1)
        p2.sync(listOf(relayB), fs)
        assertFalse(p2.local.containsKey(e1.id))

        bridge.sync(listOf(relayA, relayB), fs) // a phone that sees both relays carries the record across
        p2.sync(listOf(relayB), fs)
        assertTrue(p2.local.containsKey(e1.id))
    }

    @Test
    fun phonesReseedARelayThatLostData() {
        val f = Key()
        val g = gref(f)
        val fs = listOf(Filter(tags = mapOf("g" to listOf(g), "t" to listOf(T_CORE)), limit = 400))
        val relay = Relay()
        val a = Phone(); val b = Phone()
        val e = def(f)
        a.post(listOf(relay), e)
        b.sync(listOf(relay), fs)
        relay.db.clear() // relay wiped / replaced
        b.sync(listOf(relay), fs)
        assertTrue(relay.db.containsKey(e.id))
    }

    @Test
    fun truncatedAnswersAreNotUsedToGuessMissingEvents() {
        val f = Key()
        val g = gref(f)
        val fs = listOf(Filter(tags = mapOf("g" to listOf(g)), limit = 2))
        val local = (1..5).map { joinEv(Key(), g, 100L + it) }
        val answer = local.take(2).map { it.id }.toSet()
        assertTrue(Gossip.missing(local, fs, answer, 40).isEmpty())
    }
}

class StoreTest {
    @Test
    fun replaceableSemantics() {
        val k = Key()
        val s = EventStore()
        val e1 = Nostr.create(k.sec, 100, listOf(listOf("d", "sf:p"), listOf("t", APP_TAG)), "{\"n\":\"a\"}")
        val e2 = Nostr.create(k.sec, 200, listOf(listOf("d", "sf:p"), listOf("t", APP_TAG)), "{\"n\":\"b\"}")
        assertTrue(s.put(e1))
        assertTrue(s.put(e2))
        assertFalse(s.put(e1))
        assertEquals(e2.id, s.get(k.pub, "sf:p")!!.id)
        assertEquals(1, s.size())
    }

    @Test
    fun saveAndLoad() {
        val k = Key()
        val s = EventStore()
        s.put(Nostr.create(k.sec, 100, listOf(listOf("d", "a"), listOf("t", APP_TAG)), "x"))
        s.put(Nostr.create(k.sec, 100, listOf(listOf("d", "b"), listOf("t", APP_TAG)), "y"))
        val t = EventStore()
        t.load(s.toLines())
        assertEquals(2, t.size())
    }
}
