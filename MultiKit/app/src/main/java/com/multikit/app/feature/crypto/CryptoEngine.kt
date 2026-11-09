package com.multikit.app.feature.crypto

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import java.util.zip.CRC32
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Pure crypto/encoding logic for the Crypto screen. All functions are blocking and
 * must be called from Dispatchers.IO (except [hashFile], which manages its own IO).
 */
internal object CryptoEngine {

    // ------------------------------------------------------------ encode/decode

    enum class EncFormat(val label: String) {
        BASE64("Base64"),
        BASE64_URL("Base64 URL-safe"),
        HEX("Hex"),
        URL("URL (percent)"),
        HTML("HTML entities"),
        UNICODE("Unicode escapes"),
        BINARY("Binary (8-bit)"),
        DECIMAL("Decimal bytes"),
        ROT13("ROT13"),
        MORSE("Morse code"),
    }

    data class Detection(val format: String, val decoded: String)

    fun encode(format: EncFormat, text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return when (format) {
            EncFormat.BASE64 -> Base64.getEncoder().encodeToString(bytes)
            EncFormat.BASE64_URL -> Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            EncFormat.HEX -> toHex(bytes)
            EncFormat.URL -> URLEncoder.encode(text, "UTF-8")
            EncFormat.HTML -> htmlEncode(text)
            EncFormat.UNICODE -> text.toCharArray().joinToString("") { String.format(Locale.US, "\\u%04x", it.code) }
            EncFormat.BINARY -> bytes.joinToString(" ") { String.format(Locale.US, "%8s", Integer.toBinaryString(it.toInt() and 0xFF)).replace(' ', '0') }
            EncFormat.DECIMAL -> bytes.joinToString(" ") { (it.toInt() and 0xFF).toString() }
            EncFormat.ROT13 -> rot13(text)
            EncFormat.MORSE -> morseEncode(text)
        }
    }

    fun decode(format: EncFormat, text: String): String {
        val bytes: ByteArray = when (format) {
            EncFormat.BASE64 -> base64Decode(text, urlSafe = false)
            EncFormat.BASE64_URL -> base64Decode(text, urlSafe = true)
            EncFormat.HEX -> parseHex(text)
            EncFormat.URL -> try {
                URLDecoder.decode(text, "UTF-8").toByteArray(Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid percent-encoding: ${e.message}")
            }
            EncFormat.HTML -> htmlDecode(text).toByteArray(Charsets.UTF_8)
            EncFormat.UNICODE -> unicodeUnescape(text).toByteArray(Charsets.UTF_8)
            EncFormat.BINARY -> binaryDecode(text)
            EncFormat.DECIMAL -> decimalDecode(text)
            EncFormat.ROT13 -> rot13(text).toByteArray(Charsets.UTF_8)
            EncFormat.MORSE -> morseDecode(text).toByteArray(Charsets.UTF_8)
        }
        return String(bytes, Charsets.UTF_8)
    }

    /** Tries Base64 -> Hex -> URL -> Unicode escapes; returns the first plausible decoding. */
    fun autoDetect(input: String): Detection? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        // Base64 (standard, then URL-safe).
        val b64 = trimmed.replace(Regex("\\s"), "")
        if (b64.length >= 4 && b64.length % 4 == 0 && b64.matches(Regex("^[A-Za-z0-9+/]+={0,2}$"))) {
            runCatching { base64Decode(b64, urlSafe = false) }.getOrNull()
                ?.takeIf { isPlausibleText(it) }
                ?.let { return Detection("Base64", String(it, Charsets.UTF_8)) }
        }
        if (b64.length >= 4 && (b64.contains('-') || b64.contains('_')) && b64.matches(Regex("^[A-Za-z0-9_-]+={0,2}$"))) {
            runCatching { base64Decode(b64, urlSafe = true) }.getOrNull()
                ?.takeIf { isPlausibleText(it) }
                ?.let { return Detection("Base64 URL-safe", String(it, Charsets.UTF_8)) }
        }

        // Hex.
        val hex = trimmed.replace(Regex("[\\s:]"), "")
        if (hex.length >= 2 && hex.length % 2 == 0 && hex.matches(Regex("^[0-9a-fA-F]+$"))) {
            runCatching { parseHex(hex) }.getOrNull()
                ?.takeIf { isPlausibleText(it) }
                ?.let { return Detection("Hex", String(it, Charsets.UTF_8)) }
        }

        // URL percent-encoding.
        if (Regex("%[0-9a-fA-F]{2}").containsMatchIn(trimmed) || trimmed.contains('+')) {
            runCatching { URLDecoder.decode(trimmed, "UTF-8) ".trimEnd()) }.getOrNull()
                ?.takeIf { it != trimmed }
                ?.let { return Detection("URL (percent)", it) }
        }

        // Unicode escapes.
        if (Regex("\\\\u[0-9a-fA-F]{4}").containsMatchIn(trimmed)) {
            runCatching { unicodeUnescape(trimmed) }.getOrNull()
                ?.takeIf { it != trimmed }
                ?.let { return Detection("Unicode escapes", it) }
        }
        return null
    }

    private fun isPlausibleText(bytes: ByteArray): Boolean = try {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val s = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        s.isNotEmpty() && s.count { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' } <= s.length / 10
    } catch (e: Exception) {
        false
    }

    private fun base64Decode(text: String, urlSafe: Boolean): ByteArray {
        val clean = text.replace(Regex("\\s"), "")
        require(clean.isNotEmpty()) { "Nothing to decode." }
        return try {
            if (urlSafe) Base64.getUrlDecoder().decode(clean) else Base64.getDecoder().decode(clean)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid Base64 input: ${e.message}")
        }
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { String.format(Locale.US, "%02x", it) }

    fun parseHex(text: String): ByteArray {
        val clean = text.replace(Regex("[\\s:]"), "")
        require(clean.isNotEmpty()) { "Nothing to decode." }
        require(clean.length % 2 == 0) { "Hex input must have an even number of digits." }
        require(clean.matches(Regex("^[0-9a-fA-F]+$"))) { "Hex input contains non-hex characters." }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun binaryDecode(text: String): ByteArray {
        val clean = text.replace(Regex("\\s"), "")
        require(clean.isNotEmpty()) { "Nothing to decode." }
        require(clean.length % 8 == 0 && clean.matches(Regex("^[01]+$"))) { "Binary input must be groups of 8 bits (0/1)." }
        return ByteArray(clean.length / 8) { i -> clean.substring(i * 8, i * 8 + 8).toInt(2).toByte() }
    }

    private fun decimalDecode(text: String): ByteArray {
        val parts = text.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        require(parts.isNotEmpty()) { "Nothing to decode." }
        return ByteArray(parts.size) { i ->
            val v = parts[i].toIntOrNull() ?: throw IllegalArgumentException("Invalid decimal byte: ${parts[i]}")
            require(v in 0..255) { "Decimal byte out of range (0-255): $v" }
            v.toByte()
        }
    }

    private fun htmlEncode(text: String): String = buildString {
        for (c in text) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\'' -> append("&#39;")
                c.code in 32..126 -> append(c)
                else -> append("&#${c.code};")
            }
        }
    }

    private val HTML_NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
    )

    private fun htmlDecode(text: String): String {
        var s = Regex("&#x([0-9a-fA-F]+);").replace(text) { m ->
            val cp = m.groupValues[1].toIntOrNull(16)
            if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else m.value
        }
        s = Regex("&#([0-9]+);").replace(s) { m ->
            val cp = m.groupValues[1].toIntOrNull()
            if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else m.value
        }
        s = Regex("&([a-zA-Z]+);").replace(s) { m -> HTML_NAMED[m.groupValues[1]] ?: m.value }
        return s
    }

    private fun unicodeUnescape(text: String): String =
        Regex("\\\\u([0-9a-fA-F]{4})").replace(text) { m -> m.groupValues[1].toInt(16).toChar().toString() }

    private fun rot13(text: String): String = buildString {
        for (c in text) {
            when (c) {
                in 'a'..'z' -> append('a' + (c - 'a' + 13) % 26)
                in 'A'..'Z' -> append('A' + (c - 'A' + 13) % 26)
                else -> append(c)
            }
        }
    }

    private val MORSE_TABLE: Map<Char, String> = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.",
        'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..",
        'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.",
        'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-",
        'Y' to "-.--", 'Z' to "--..",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
        '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.",
        '.' to ".-.-.-", ',' to "--..--", '?' to "..--..", '\'' to ".----.", '!' to "-.-.--",
        '/' to "-..-.", '(' to "-.--.", ')' to "-.--.-", '&' to ".-...", ':' to "---...",
        ';' to "-.-.-.", '=' to "-...-", '+' to ".-.-.", '-' to "-....-", '_' to "..--.-",
        '"' to ".-..-.", '$' to "...-..-", '@' to ".--.-.",
    )
    private val MORSE_REVERSE: Map<String, Char> = MORSE_TABLE.entries.associate { (k, v) -> v to k }

    private fun morseEncode(text: String): String {
        val words = text.uppercase(Locale.US).split(Regex("\\s+")).filter { it.isNotBlank() }
        require(words.isNotEmpty()) { "Nothing to encode." }
        return words.joinToString(" / ") { word ->
            word.map { c ->
                MORSE_TABLE[c] ?: throw IllegalArgumentException("Unsupported Morse character: '$c'")
            }.joinToString(" ")
        }
    }

    private fun morseDecode(text: String): String {
        val words = text.trim().split('/')
        require(words.any { it.isNotBlank() }) { "Nothing to decode." }
        return words.joinToString(" ") { word ->
            word.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString("") { token ->
                (MORSE_REVERSE[token] ?: throw IllegalArgumentException("Unknown Morse token: '$token'")).toString()
            }
        }
    }

    // -------------------------------------------------------------------- hash

    val HASH_ALGORITHMS = listOf("MD5", "SHA-1", "SHA-224", "SHA-256", "SHA-384", "SHA-512", "SHA3-256", "CRC32", "HMAC-SHA256")

    data class DigestResult(val hex: String, val base64: String, val byteCount: Long)

    private fun newDigest(algorithm: String): MessageDigest = try {
        MessageDigest.getInstance(algorithm)
    } catch (e: NoSuchAlgorithmException) {
        throw IllegalArgumentException("$algorithm is not available on this device.")
    }

    private fun newMac(key: String): Mac {
        require(key.isNotEmpty()) { "Enter an HMAC key." }
        return Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }
    }

    private fun crcBytes(value: Long): ByteArray = ByteBuffer.allocate(4).putInt(value.toInt()).array()

    fun digestText(algorithm: String, text: String, hmacKey: String): DigestResult {
        val data = text.toByteArray(Charsets.UTF_8)
        return when (algorithm) {
            "CRC32" -> {
                val crc = CRC32().apply { update(data) }
                DigestResult(String.format(Locale.US, "%08x", crc.value), Base64.getEncoder().encodeToString(crcBytes(crc.value)), data.size.toLong())
            }
            "HMAC-SHA256" -> {
                val out = newMac(hmacKey).doFinal(data)
                DigestResult(toHex(out), Base64.getEncoder().encodeToString(out), data.size.toLong())
            }
            else -> {
                val out = newDigest(algorithm).digest(data)
                DigestResult(toHex(out), Base64.getEncoder().encodeToString(out), data.size.toLong())
            }
        }
    }

    /** Streams a document through the digest, publishing progress (0..1) on Main. */
    suspend fun hashFile(
        context: Context,
        uri: Uri,
        algorithm: String,
        hmacKey: String,
        onProgress: (Float) -> Unit,
    ): DigestResult = withContext(Dispatchers.IO) {
        val size = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getLong(idx) else -1L
            } ?: -1L
        }.getOrDefault(-1L)

        val md = if (algorithm != "CRC32" && algorithm != "HMAC-SHA256") newDigest(algorithm) else null
        val mac = if (algorithm == "HMAC-SHA256") newMac(hmacKey) else null
        val crc = if (algorithm == "CRC32") CRC32() else null

        var total = 0L
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open the selected file." }
            val buf = ByteArray(64 * 1024)
            while (true) {
                ensureActive()
                val n = input.read(buf)
                if (n <= 0) break
                md?.update(buf, 0, n)
                mac?.update(buf, 0, n)
                crc?.update(buf, 0, n)
                total += n
                if (size > 0) {
                    val p = (total.toFloat() / size).coerceIn(0f, 1f)
                    withContext(Dispatchers.Main) { onProgress(p) }
                }
            }
        }
        val out = when {
            mac != null -> mac.doFinal()
            md != null -> md.digest()
            else -> crcBytes(crc!!.value)
        }
        val hex = if (algorithm == "CRC32") String.format(Locale.US, "%08x", crc!!.value) else toHex(out)
        DigestResult(hex, Base64.getEncoder().encodeToString(out), total)
    }

    // ----------------------------------------------------------------- encrypt

    enum class EncScheme(val label: String, val transformation: String, val keyBytes: Int, val ivBytes: Int, val keyAlg: String) {
        AES_256_GCM("AES-256-GCM (recommended)", "AES/GCM/NoPadding", 32, 12, "AES"),
        AES_256_CBC("AES-256-CBC", "AES/CBC/PKCS5Padding", 32, 16, "AES"),
        CHACHA20_POLY1305("ChaCha20-Poly1305", "ChaCha20-Poly1305", 32, 12, "ChaCha20"),
        DESEDE("Triple DES (legacy)", "DESede/CBC/PKCS5Padding", 24, 8, "DESede"),
    }

    const val KDF_ITERATIONS = 65536
    private const val SALT_BYTES = 16

    fun schemeDetails(scheme: EncScheme): String =
        "${scheme.label} · PBKDF2-HMAC-SHA256, $KDF_ITERATIONS iterations, ${SALT_BYTES}-byte salt, " +
            "${scheme.ivBytes}-byte IV" + (if (scheme == EncScheme.AES_256_GCM) ", 128-bit tag" else "") +
            " · output = Base64(salt || iv || ciphertext)"

    private fun deriveKey(passphrase: String, salt: ByteArray, scheme: EncScheme): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, KDF_ITERATIONS, scheme.keyBytes * 8)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return SecretKeySpec(factory.generateSecret(spec).encoded, scheme.keyAlg)
        } finally {
            spec.clearPassword()
        }
    }

    private fun cipherFor(scheme: EncScheme): Cipher = try {
        Cipher.getInstance(scheme.transformation)
    } catch (e: NoSuchAlgorithmException) {
        throw IllegalArgumentException("${scheme.label} is not available on this device.")
    } catch (e: Exception) {
        throw IllegalArgumentException("${scheme.label} is not available on this device: ${e.message}")
    }

    fun encrypt(scheme: EncScheme, passphrase: String, plaintext: ByteArray): String {
        require(passphrase.isNotEmpty()) { "Enter a passphrase." }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(scheme.ivBytes).also { random.nextBytes(it) }
        val key = deriveKey(passphrase, salt, scheme)
        val cipher = cipherFor(scheme)
        if (scheme == EncScheme.AES_256_GCM) {
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
        }
        val ct = cipher.doFinal(plaintext)
        return Base64.getEncoder().encodeToString(salt + iv + ct)
    }

    fun decrypt(scheme: EncScheme, passphrase: String, encoded: String): ByteArray {
        require(passphrase.isNotEmpty()) { "Enter a passphrase." }
        val raw = base64Decode(encoded, urlSafe = false)
        val headerLen = SALT_BYTES + scheme.ivBytes
        require(raw.size > headerLen) { "Ciphertext too short — expected Base64(salt || iv || ciphertext)." }
        val salt = raw.copyOfRange(0, SALT_BYTES)
        val iv = raw.copyOfRange(SALT_BYTES, headerLen)
        val ct = raw.copyOfRange(headerLen, raw.size)
        val key = deriveKey(passphrase, salt, scheme)
        val cipher = cipherFor(scheme)
        if (scheme == EncScheme.AES_256_GCM) {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        } else {
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        }
        return try {
            cipher.doFinal(ct)
        } catch (e: AEADBadTagException) {
            throw IllegalArgumentException("Authentication failed — wrong passphrase or corrupted data.")
        } catch (e: BadPaddingException) {
            throw IllegalArgumentException("Decryption failed — wrong passphrase or corrupted data.")
        }
    }

    // --------------------------------------------------------------- classical

    fun caesar(text: String, shift: Int, decrypt: Boolean): String {
        val s = ((if (decrypt) -shift else shift) % 26 + 26) % 26
        return buildString {
            for (c in text) {
                when (c) {
                    in 'a'..'z' -> append('a' + (c - 'a' + s) % 26)
                    in 'A'..'Z' -> append('A' + (c - 'A' + s) % 26)
                    else -> append(c)
                }
            }
        }
    }

    fun vigenere(text: String, key: String, decrypt: Boolean): String {
        val shifts = key.filter { it.isLetter() }.map { it.uppercaseChar() - 'A' }
        require(shifts.isNotEmpty()) { "The Vigenère key must contain at least one letter." }
        var ki = 0
        return buildString {
            for (c in text) {
                if (c.isLetter()) {
                    val base = if (c.isUpperCase()) 'A' else 'a'
                    val sh = shifts[ki % shifts.size]
                    ki++
                    val v = ((c - base) + (if (decrypt) -sh else sh)) % 26
                    append(base + (v + 26) % 26)
                } else {
                    append(c)
                }
            }
        }
    }

    fun atbash(text: String): String = buildString {
        for (c in text) {
            when (c) {
                in 'a'..'z' -> append('z' - (c - 'a'))
                in 'A'..'Z' -> append('Z' - (c - 'A'))
                else -> append(c)
            }
        }
    }

    fun xorEncryptToHex(text: String, hexKey: String): String {
        val key = parseHex(hexKey)
        require(key.isNotEmpty()) { "Enter a hex key (e.g. 0a1b2c)." }
        val data = text.toByteArray(Charsets.UTF_8)
        val out = ByteArray(data.size) { i -> (data[i].toInt() xor key[i % key.size].toInt()).toByte() }
        return toHex(out)
    }

    fun xorDecryptFromHex(hexText: String, hexKey: String): String {
        val key = parseHex(hexKey)
        require(key.isNotEmpty()) { "Enter a hex key (e.g. 0a1b2c)." }
        val data = parseHex(hexText)
        val out = ByteArray(data.size) { i -> (data[i].toInt() xor key[i % key.size].toInt()).toByte() }
        return String(out, Charsets.UTF_8)
    }

    // --------------------------------------------------------------------- rsa

    private const val RSA_BITS = 2048
    private val OAEP_SPEC = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)

    fun rsaGenerateKeyPair(): Pair<String, String> {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(RSA_BITS)
        val kp = gen.generateKeyPair()
        return Base64.getEncoder().encodeToString(kp.public.encoded) to
            Base64.getEncoder().encodeToString(kp.private.encoded)
    }

    private fun cleanKeyMaterial(s: String): String =
        s.replace(Regex("-----[A-Z0-9 ]+-----"), "").replace(Regex("\\s"), "")

    private fun rsaPublicKey(material: String): PublicKey {
        require(material.isNotBlank()) { "Paste or generate a public key first." }
        val bytes = try {
            Base64.getDecoder().decode(cleanKeyMaterial(material))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Public key is not valid Base64.")
        }
        return try {
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes))
        } catch (e: InvalidKeySpecException) {
            throw IllegalArgumentException("Invalid public key — expected Base64 X.509 / SubjectPublicKeyInfo.")
        }
    }

    private fun rsaPrivateKey(material: String): PrivateKey {
        require(material.isNotBlank()) { "Paste or generate a private key first." }
        val bytes = try {
            Base64.getDecoder().decode(cleanKeyMaterial(material))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Private key is not valid Base64.")
        }
        return try {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
        } catch (e: InvalidKeySpecException) {
            throw IllegalArgumentException("Invalid private key — expected Base64 PKCS#8.")
        }
    }

    /** OAEP-SHA256 encrypts at most (keyBytes - 66) bytes per block; long inputs are chunked. */
    fun rsaEncrypt(publicMaterial: String, data: ByteArray): String {
        val key = rsaPublicKey(publicMaterial) as RSAPublicKey
        val keyBytes = (key.modulus.bitLength() + 7) / 8
        val capacity = keyBytes - 2 * 32 - 2
        require(capacity > 0) { "Key is too small for OAEP-SHA256." }
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, OAEP_SPEC)
        val out = ByteArrayOutputStream()
        if (data.isEmpty()) {
            out.write(cipher.doFinal())
        } else {
            var off = 0
            while (off < data.size) {
                val len = minOf(capacity, data.size - off)
                out.write(cipher.doFinal(data, off, len))
                off += len
            }
        }
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    fun rsaDecrypt(privateMaterial: String, encoded: String): ByteArray {
        val key = rsaPrivateKey(privateMaterial) as RSAPrivateKey
        val keyBytes = (key.modulus.bitLength() + 7) / 8
        val raw = try {
            Base64.getDecoder().decode(encoded.replace(Regex("\\s"), ""))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Ciphertext is not valid Base64.")
        }
        require(raw.isNotEmpty() && raw.size % keyBytes == 0) {
            "Ciphertext length must be a positive multiple of the key size ($keyBytes bytes)."
        }
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, OAEP_SPEC)
        val out = ByteArrayOutputStream()
        var off = 0
        while (off < raw.size) {
            out.write(cipher.doFinal(raw, off, keyBytes))
            off += keyBytes
        }
        return out.toByteArray()
    }

    fun rsaSign(privateMaterial: String, data: ByteArray): String {
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(rsaPrivateKey(privateMaterial))
        sig.update(data)
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    fun rsaVerify(publicMaterial: String, data: ByteArray, signatureB64: String): Boolean {
        val sigBytes = try {
            Base64.getDecoder().decode(signatureB64.replace(Regex("\\s"), ""))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Signature is not valid Base64.")
        }
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initVerify(rsaPublicKey(publicMaterial))
        sig.update(data)
        return sig.verify(sigBytes)
    }
}