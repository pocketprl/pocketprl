package dev.pocketprl.core.crypto

import dev.pocketprl.Vectors
import dev.pocketprl.int
import dev.pocketprl.str
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimitivesTest {
    @Test
    fun shake256MatchesGoVectors() {
        var n = 0
        for (v in Vectors.shake256) {
            val o = v.jsonObject
            val input = o.str("in").hexToBytes()
            val expected = o.str("out")
            assertEquals("shake256 in=${o.str("in").take(16)} len=${o.int("outLen")}", expected, Shake256.hash(input, o.int("outLen")).toHex())
            n++
        }
        assertTrue(n >= 20)
    }

    @Test
    fun shake256StreamingAndReuse() {
        val data = ByteArray(1000) { (it * 31).toByte() }
        val oneShot = Shake256.hash(data, 200)
        val s = Shake256()
        s.update(data, 0, 137).update(data, 137, 863)
        val out = ByteArray(200)
        s.digest(out, 0, 50)
        s.digest(out, 50, 150)
        assertArrayEquals(oneShot, out)
        // Reuse after reset must give the same answer.
        assertArrayEquals(oneShot, s.reset().update(data).digest(200))
    }

    @Test
    fun hkdfMatchesGoVectors() {
        for (v in Vectors.hkdf) {
            val o = v.jsonObject
            val out = Hashes.hkdfSha256(o.str("ikm").hexToBytes(), null, "XMSS-SEED-EXPANSION".toByteArray(), 96)
            assertEquals(o.str("out"), out.toHex())
        }
    }

    @Test
    fun pbkdf2KnownAnswer() {
        // RFC 6070-style vector for PBKDF2-HMAC-SHA512 ("password", "salt", 1, 64).
        val dk = Hashes.pbkdf2HmacSha512("password".toByteArray(), "salt".toByteArray(), 1, 64)
        assertEquals(
            "867f70cf1ade02cff3752599a3a53dc4af34c7a669815ae5d513554e1c8cf252c02d470a285a0501bad999bfe943c08f050235d7d68b1da55e63f73b60a57fce",
            dk.toHex(),
        )
    }

    @Test
    fun taggedHashMatchesBip340Definition() {
        val tag = "TapTweak"
        val data = ByteArray(32) { 7 }
        val th = Hashes.sha256(tag.toByteArray())
        assertArrayEquals(Hashes.sha256(th, th, data), Hashes.taggedHash(tag, data))
    }

    @Test
    fun xmssKeygenMatchesReferenceLibrary() {
        for (v in Vectors.xmss) {
            val o = v.jsonObject
            val pk = Xmss.keygen(o.str("privSeed").hexToBytes(), o.str("pubSeed").hexToBytes(), parallel = true)
            assertEquals(o.str("pubKey"), pk.toHex())
            val pkSerial = Xmss.keygen(o.str("privSeed").hexToBytes(), o.str("pubSeed").hexToBytes(), parallel = false)
            assertArrayEquals(pk, pkSerial)
        }
    }

    @Test
    fun bech32mRoundTripAndValidation() {
        val prog = ByteArray(32) { (it * 3).toByte() }
        val addr = Bech32.encodeSegwit("prl", 1, prog)
        assertTrue(addr.startsWith("prl1p"))
        val d = Bech32.decodeSegwit(addr)!!
        assertEquals("prl", d.hrp)
        assertEquals(1, d.witnessVersion)
        assertArrayEquals(prog, d.program)
        assertArrayEquals(prog, Bech32.decodeSegwit(addr.uppercase())!!.program)
        // Flip a character -> checksum failure.
        val bad = addr.substring(0, addr.length - 1) + (if (addr.last() == 'q') 'p' else 'q')
        assertTrue(Bech32.decodeSegwit(bad) == null)
        // Mixed case is invalid.
        assertTrue(Bech32.decodeSegwit(addr.replaceFirst("prl1", "PRL1")) == null)
        // A v0 program encoded with bech32m must fail (and vice versa).
        val v0 = Bech32.encodeSegwit("prl", 0, ByteArray(20))
        assertTrue(Bech32.decodeSegwit(v0)!!.witnessVersion == 0)
        assertFalse(v0.startsWith("prl1p"))
    }

    @Test
    fun bip39GenerateValidateSeed() {
        val m = Bip39.generate(128)
        assertEquals(12, m.split(' ').size)
        assertTrue(Bip39.validate(m) is Bip39.Validation.Ok)
        val m24 = Bip39.generate(256)
        assertEquals(24, m24.split(' ').size)
        assertTrue(Bip39.validate("  " + m24.uppercase().replace(" ", "\n ") + " ") is Bip39.Validation.Ok)

        val abandon = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        assertEquals(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            Bip39.toSeed(abandon).toHex(),
        )
        assertTrue(Bip39.validate(abandon.replace("about", "abandon")) is Bip39.Validation.Invalid)
        assertTrue(Bip39.validate("abandon abandon") is Bip39.Validation.Invalid)
        val bad = Bip39.validate(abandon.replace("about", "aboot")) as Bip39.Validation.Invalid
        assertEquals("aboot", bad.badWord)
        assertEquals(listOf("abandon"), Bip39.wordsStartingWith("aband"))
    }
}
