package dev.pocketprl.core.crypto

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Hashes {
    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun dsha256(vararg parts: ByteArray): ByteArray = sha256(sha256(*parts))

    fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun hmacSha512(key: ByteArray, vararg parts: ByteArray): ByteArray = hmac("HmacSHA512", key, *parts)

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray = hmac("HmacSHA256", key, *parts)

    private fun hmac(alg: String, key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance(alg)
        mac.init(SecretKeySpec(key, alg))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    private val tagCache = ConcurrentHashMap<String, ByteArray>()

    /** BIP-340 tagged hash: SHA256(SHA256(tag) || SHA256(tag) || data). */
    fun taggedHash(tag: String, vararg parts: ByteArray): ByteArray {
        val th = tagCache.getOrPut(tag) { sha256(tag.toByteArray(Charsets.UTF_8)) }
        val md = MessageDigest.getInstance("SHA-256")
        md.update(th)
        md.update(th)
        for (p in parts) md.update(p)
        return md.digest()
    }

    /**
     * PBKDF2-HMAC-SHA512 (RFC 8018). Implemented directly on top of [Mac] so the
     * result does not depend on provider-specific password encoding quirks.
     */
    fun pbkdf2HmacSha512(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        require(iterations > 0 && dkLen > 0)
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(if (password.isEmpty()) ByteArray(1) else password, "HmacSHA512"))
        // SecretKeySpec rejects an empty key; HMAC zero-pads keys, so a single zero byte is equivalent.
        val hLen = 64
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        val u = ByteArray(hLen)
        val t = ByteArray(hLen)
        for (i in 1..blocks) {
            mac.update(salt)
            mac.update(beUInt32(i.toLong()))
            mac.doFinal(u, 0)
            System.arraycopy(u, 0, t, 0, hLen)
            for (c in 1 until iterations) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (k in 0 until hLen) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (i - 1) * hLen, hLen)
        }
        u.wipe(); t.wipe()
        return if (out.size == dkLen) out else out.copyOf(dkLen).also { out.wipe() }
    }

    /** HKDF-SHA256 (RFC 5869) extract + expand. A null salt means a zero-filled salt. */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray?, info: ByteArray, len: Int): ByteArray {
        val prk = hmacSha256(salt ?: ByteArray(32), ikm)
        val out = ByteArray(len)
        var prev = ByteArray(0)
        var o = 0
        var counter = 1
        while (o < len) {
            prev = hmacSha256(prk, prev, info, byteArrayOf(counter.toByte()))
            val n = minOf(32, len - o)
            System.arraycopy(prev, 0, out, o, n)
            o += n
            counter++
        }
        prk.wipe()
        return out
    }
}
