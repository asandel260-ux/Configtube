package com.studyflow.app.p2p

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

object Hex {
    private const val D = "0123456789abcdef"
    fun enc(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xff
            sb.append(D[v ushr 4]).append(D[v and 15])
        }
        return sb.toString()
    }

    fun isHex(s: String, len: Int): Boolean = s.length == len && s.all { it in '0'..'9' || it in 'a'..'f' }

    fun dec(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex" }
        return ByteArray(s.length / 2) { i ->
            val hi = Character.digit(s[2 * i], 16)
            val lo = Character.digit(s[2 * i + 1], 16)
            require(hi >= 0 && lo >= 0) { "bad hex" }
            ((hi shl 4) or lo).toByte()
        }
    }
}

fun sha256(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    for (p in parts) md.update(p)
    return md.digest()
}

/** BIP-340 Schnorr signatures on secp256k1 (the scheme Nostr uses). */
object Schnorr {
    private val params = CustomNamedCurves.getByName("secp256k1")
    private val G: ECPoint = params.g
    private val N: BigInteger = params.n
    private val P: BigInteger = params.curve.field.characteristic
    private val rng = SecureRandom()

    private fun b32(x: BigInteger): ByteArray {
        val raw = x.toByteArray()
        val out = ByteArray(32)
        val start = maxOf(0, raw.size - 32)
        val len = raw.size - start
        System.arraycopy(raw, start, out, 32 - len, len)
        return out
    }

    private fun tagged(tag: String, vararg parts: ByteArray): ByteArray {
        val th = sha256(tag.toByteArray(Charsets.UTF_8))
        return sha256(th, th, *parts)
    }

    private fun liftX(x: BigInteger): ECPoint? {
        if (x >= P) return null
        val c = x.pow(3).add(BigInteger.valueOf(7)).mod(P)
        val y = c.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        if (y.pow(2).mod(P) != c) return null
        val yy = if (y.testBit(0)) P.subtract(y) else y
        return try {
            params.curve.validatePoint(x, yy)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun newSecret(): ByteArray {
        while (true) {
            val b = ByteArray(32)
            rng.nextBytes(b)
            val v = BigInteger(1, b)
            if (v.signum() > 0 && v < N) return b
        }
    }

    fun pubKey(sec: ByteArray): ByteArray {
        val d = BigInteger(1, sec)
        require(d.signum() > 0 && d < N) { "bad secret" }
        return b32(G.multiply(d).normalize().affineXCoord.toBigInteger())
    }

    fun sign(msg: ByteArray, sec: ByteArray, aux: ByteArray = ByteArray(32).also { rng.nextBytes(it) }): ByteArray {
        val d0 = BigInteger(1, sec)
        require(d0.signum() > 0 && d0 < N) { "bad secret" }
        val pt = G.multiply(d0).normalize()
        val d = if (pt.affineYCoord.toBigInteger().testBit(0)) N.subtract(d0) else d0
        val px = b32(pt.affineXCoord.toBigInteger())
        val dBytes = b32(d)
        val auxHash = tagged("BIP0340/aux", aux)
        val t = ByteArray(32) { i -> (dBytes[i].toInt() xor auxHash[i].toInt()).toByte() }
        val k0 = BigInteger(1, tagged("BIP0340/nonce", t, px, msg)).mod(N)
        require(k0.signum() != 0) { "bad nonce" }
        val r = G.multiply(k0).normalize()
        val k = if (r.affineYCoord.toBigInteger().testBit(0)) N.subtract(k0) else k0
        val rx = b32(r.affineXCoord.toBigInteger())
        val e = BigInteger(1, tagged("BIP0340/challenge", rx, px, msg)).mod(N)
        val s = k.add(e.multiply(d)).mod(N)
        return rx + b32(s)
    }

    fun verify(msg: ByteArray, pub: ByteArray, sig: ByteArray): Boolean {
        if (msg.size != 32 || pub.size != 32 || sig.size != 64) return false
        val pt = liftX(BigInteger(1, pub)) ?: return false
        val r = BigInteger(1, sig.copyOfRange(0, 32))
        val s = BigInteger(1, sig.copyOfRange(32, 64))
        if (r >= P || s >= N) return false
        val e = BigInteger(1, tagged("BIP0340/challenge", sig.copyOfRange(0, 32), pub, msg)).mod(N)
        val rp = G.multiply(s).subtract(pt.multiply(e)).normalize()
        if (rp.isInfinity) return false
        if (rp.affineYCoord.toBigInteger().testBit(0)) return false
        return rp.affineXCoord.toBigInteger() == r
    }
}
