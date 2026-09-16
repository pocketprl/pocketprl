package dev.pocketprl.core.crypto

import java.security.SecureRandom
import java.text.Normalizer

/** BIP-39 mnemonics (English wordlist), matching go-bip39 as used by oyster. */
object Bip39 {
    val VALID_WORD_COUNTS = setOf(12, 15, 18, 21, 24)

    val words: List<String> by lazy {
        val stream = Bip39::class.java.getResourceAsStream("/bip39/english.txt")
            ?: error("bip39 wordlist resource missing")
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }.also { check(it.size == 2048) { "wordlist must have 2048 words, got ${it.size}" } }
    }

    private val index: Map<String, Int> by lazy { words.withIndex().associate { it.value to it.index } }

    fun isWord(w: String): Boolean = index.containsKey(w)

    fun wordsStartingWith(prefix: String, limit: Int = 8): List<String> =
        if (prefix.isEmpty()) emptyList() else words.filter { it.startsWith(prefix) }.take(limit)

    fun normalize(mnemonic: String): String =
        Normalizer.normalize(mnemonic.trim().lowercase(), Normalizer.Form.NFKD)
            .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    fun generate(entropyBits: Int = 128, random: SecureRandom = SecureRandom()): String {
        require(entropyBits % 32 == 0 && entropyBits in 128..256)
        val entropy = ByteArray(entropyBits / 8)
        random.nextBytes(entropy)
        return try {
            fromEntropy(entropy)
        } finally {
            entropy.wipe()
        }
    }

    fun fromEntropy(entropy: ByteArray): String {
        require(entropy.size % 4 == 0 && entropy.size in 16..32)
        val csBits = entropy.size / 4
        val hash = Hashes.sha256(entropy)
        val bits = StringBuilder(entropy.size * 8 + csBits)
        for (b in entropy) bits.append(Integer.toBinaryString((b.toInt() and 0xff) or 0x100).substring(1))
        val hashBits = Integer.toBinaryString((hash[0].toInt() and 0xff) or 0x100).substring(1)
        bits.append(hashBits, 0, csBits)
        val out = StringBuilder()
        for (i in 0 until bits.length / 11) {
            val idx = bits.substring(i * 11, (i + 1) * 11).toInt(2)
            if (i > 0) out.append(' ')
            out.append(words[idx])
        }
        return out.toString()
    }

    sealed class Validation {
        data class Ok(val normalized: String, val wordCount: Int) : Validation()
        data class Invalid(val reason: String, val badWord: String? = null) : Validation()
    }

    fun validate(raw: String): Validation {
        val normalized = normalize(raw)
        if (normalized.isEmpty()) return Validation.Invalid("Enter your recovery phrase")
        val ws = normalized.split(' ')
        if (ws.size !in VALID_WORD_COUNTS) {
            return Validation.Invalid("A recovery phrase has 12, 15, 18, 21 or 24 words (got ${ws.size})")
        }
        val unknown = ws.firstOrNull { !index.containsKey(it) }
        if (unknown != null) return Validation.Invalid("\"$unknown\" is not a BIP39 word", unknown)
        if (!checksumOk(ws)) return Validation.Invalid("Checksum mismatch: check the word order and spelling")
        return Validation.Ok(normalized, ws.size)
    }

    private fun checksumOk(ws: List<String>): Boolean {
        val bits = StringBuilder(ws.size * 11)
        for (w in ws) bits.append(Integer.toBinaryString(index.getValue(w) or 0x800).substring(1))
        val entBits = bits.length / 33 * 32
        val entropy = ByteArray(entBits / 8)
        for (i in entropy.indices) entropy[i] = bits.substring(i * 8, i * 8 + 8).toInt(2).toByte()
        val hash = Hashes.sha256(entropy)
        val csLen = bits.length - entBits
        val hashBits = Integer.toBinaryString((hash[0].toInt() and 0xff) or 0x100).substring(1)
        return hashBits.substring(0, csLen) == bits.substring(entBits)
    }

    /** 64-byte BIP-32 seed. Mnemonic must already be valid; passphrase is empty for Pearl wallets. */
    fun toSeed(mnemonic: String, passphrase: String = ""): ByteArray {
        val pw = Normalizer.normalize(mnemonic, Normalizer.Form.NFKD).toByteArray(Charsets.UTF_8)
        val salt = ("mnemonic" + Normalizer.normalize(passphrase, Normalizer.Form.NFKD)).toByteArray(Charsets.UTF_8)
        return try {
            Hashes.pbkdf2HmacSha512(pw, salt, 2048, 64)
        } finally {
            pw.wipe()
        }
    }
}
