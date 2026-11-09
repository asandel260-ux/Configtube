package com.toolbox.multitool.crypto

import android.util.Base64
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

enum class Kind { Encoding, Hash, Cipher }

class Algo(val name: String, val kind: Kind, val needsKey: Boolean = false)

object CryptoEngine {
    val algos = listOf(
        Algo("Base64", Kind.Encoding), Algo("Base64 URL-safe", Kind.Encoding), Algo("Base32", Kind.Encoding),
        Algo("Hex", Kind.Encoding), Algo("Binary", Kind.Encoding), Algo("URL encoding", Kind.Encoding),
        Algo("ROT13", Kind.Encoding),
        Algo("MD5", Kind.Hash), Algo("SHA-1", Kind.Hash), Algo("SHA-224", Kind.Hash), Algo("SHA-256", Kind.Hash),
        Algo("SHA-384", Kind.Hash), Algo("SHA-512", Kind.Hash), Algo("SHA3-256", Kind.Hash),
        Algo("HMAC-SHA256", Kind.Hash, needsKey = true), Algo("HMAC-SHA512", Kind.Hash, needsKey = true),
        Algo("AES-256-GCM", Kind.Cipher, needsKey = true),
        Algo("ChaCha20-Poly1305", Kind.Cipher, needsKey = true),
        Algo("AES-256-CBC", Kind.Cipher, needsKey = true),
        Algo("Caesar (shift 3)", Kind.Cipher), Algo("Vigenère", Kind.Cipher, needsKey = true),
    )

    private val rng = SecureRandom()
    private const val PBKDF_ITER = 210_000

    fun encode(algo: Algo, text: String, key: String): String = when (algo.name) {
        "Base64" -> Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
        "Base64 URL-safe" -> Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        "Base32" -> Base32.encode(text.toByteArray())
        "Hex" -> text.toByteArray().joinToString("") { "%02x".format(it) }
        "Binary" -> text.toByteArray().joinToString(" ") { Integer.toBinaryString(it.toInt() and 0xFF).padStart(8, '0') }
        "URL encoding" -> URLEncoder.encode(text, "UTF-8")
        "ROT13" -> rot(text, 13)
        "MD5", "SHA-1", "SHA-224", "SHA-256", "SHA-384", "SHA-512", "SHA3-256" ->
            MessageDigest.getInstance(algo.name).digest(text.toByteArray()).hex()
        "HMAC-SHA256" -> hmac("HmacSHA256", key, text)
        "HMAC-SHA512" -> hmac("HmacSHA512", key, text)
        "AES-256-GCM" -> seal("AES/GCM/NoPadding", "AES", 12, key, text.toByteArray(), gcm = true)
        "ChaCha20-Poly1305" -> seal("ChaCha20-Poly1305", "ChaCha20", 12, key, text.toByteArray(), gcm = false)
        "AES-256-CBC" -> seal("AES/CBC/PKCS5Padding", "AES", 16, key, text.toByteArray(), cbc = true)
        "Caesar (shift 3)" -> rot(text, 3)
        "Vigenère" -> vigenere(text, key, 1)
        else -> error("Unsupported")
    }

    fun decode(algo: Algo, text: String, key: String): String = when (algo.name) {
        "Base64" -> String(Base64.decode(text.trim(), Base64.DEFAULT))
        "Base64 URL-safe" -> String(Base64.decode(text.trim(), Base64.URL_SAFE))
        "Base32" -> String(Base32.decode(text.trim()))
        "Hex" -> String(text.filter { !it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        "Binary" -> String(text.trim().split(Regex("\\s+")).map { it.toInt(2).toByte() }.toByteArray())
        "URL encoding" -> URLDecoder.decode(text, "UTF-8")
        "ROT13" -> rot(text, 13)
        "AES-256-GCM" -> String(open("AES/GCM/NoPadding", "AES", 12, key, text, gcm = true))
        "ChaCha20-Poly1305" -> String(open("ChaCha20-Poly1305", "ChaCha20", 12, key, text, gcm = false))
        "AES-256-CBC" -> String(open("AES/CBC/PKCS5Padding", "AES", 16, key, text, cbc = true))
        "Caesar (shift 3)" -> rot(text, 23)
        "Vigenère" -> vigenere(text, key, -1)
        else -> error("${algo.name} is one-way and cannot be decoded")
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun hmac(alg: String, key: String, text: String): String {
        val mac = Mac.getInstance(alg); mac.init(SecretKeySpec(key.toByteArray(), alg))
        return mac.doFinal(text.toByteArray()).hex()
    }

    private fun deriveKey(password: String, salt: ByteArray, algName: String): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF_ITER, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(raw, algName)
    }

    // Output layout (Base64): salt(16) | iv | ciphertext(+tag). Key comes from PBKDF2-HMAC-SHA256.
    private fun seal(transform: String, keyAlg: String, ivLen: Int, password: String, data: ByteArray, gcm: Boolean = false, cbc: Boolean = false): String {
        require(password.isNotEmpty()) { "Enter a password" }
        val salt = ByteArray(16).also(rng::nextBytes)
        val iv = ByteArray(ivLen).also(rng::nextBytes)
        val c = Cipher.getInstance(transform)
        val spec = if (gcm) GCMParameterSpec(128, iv) else IvParameterSpec(iv)
        c.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, keyAlg), spec)
        return Base64.encodeToString(salt + iv + c.doFinal(data), Base64.NO_WRAP)
    }

    private fun open(transform: String, keyAlg: String, ivLen: Int, password: String, b64: String, gcm: Boolean = false, cbc: Boolean = false): ByteArray {
        val all = Base64.decode(b64.trim(), Base64.DEFAULT)
        val salt = all.copyOfRange(0, 16)
        val iv = all.copyOfRange(16, 16 + ivLen)
        val body = all.copyOfRange(16 + ivLen, all.size)
        val c = Cipher.getInstance(transform)
        val spec = if (gcm) GCMParameterSpec(128, iv) else IvParameterSpec(iv)
        c.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, keyAlg), spec)
        return c.doFinal(body) // wrong password / tampering -> AEADBadTagException
    }

    private fun rot(s: String, n: Int) = s.map {
        when (it) {
            in 'a'..'z' -> 'a' + (it - 'a' + n) % 26
            in 'A'..'Z' -> 'A' + (it - 'A' + n) % 26
            else -> it
        }
    }.joinToString("")

    private fun vigenere(s: String, key: String, dir: Int): String {
        val k = key.filter { it.isLetter() }.lowercase()
        require(k.isNotEmpty()) { "Enter a key (letters)" }
        var i = 0
        return s.map {
            if (it.isLetter() && it.code < 128) {
                val base = if (it.isUpperCase()) 'A' else 'a'
                val shift = (k[i++ % k.length] - 'a') * dir
                base + Math.floorMod(it - base + shift, 26)
            } else it
        }.joinToString("")
    }
}

object Base32 {
    private const val A = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    fun encode(data: ByteArray): String {
        val sb = StringBuilder(); var buf = 0; var bits = 0
        for (b in data) {
            buf = (buf shl 8) or (b.toInt() and 0xFF); bits += 8
            while (bits >= 5) { sb.append(A[(buf shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) sb.append(A[(buf shl (5 - bits)) and 31])
        while (sb.length % 8 != 0) sb.append('=')
        return sb.toString()
    }
    fun decode(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(); var buf = 0; var bits = 0
        for (c in s.trimEnd('=').uppercase()) {
            val v = A.indexOf(c); require(v >= 0) { "Invalid Base32 character" }
            buf = (buf shl 5) or v; bits += 5
            if (bits >= 8) { out.write((buf shr (bits - 8)) and 0xFF); bits -= 8 }
        }
        return out.toByteArray()
    }
}
