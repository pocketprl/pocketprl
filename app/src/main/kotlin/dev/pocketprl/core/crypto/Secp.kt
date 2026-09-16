package dev.pocketprl.core.crypto

import fr.acinq.secp256k1.Secp256k1

/**
 * Thin wrapper over libsecp256k1 (via secp256k1-kmp). All scalar/point math the
 * wallet needs for BIP-32, BIP-340 and BIP-341 goes through here so the
 * constant-time C implementation does the sensitive work.
 */
object Secp {
    /** 33-byte compressed public key for a 32-byte private key. */
    fun pubkey(priv32: ByteArray): ByteArray {
        require(priv32.size == 32)
        return Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(priv32))
    }

    fun isValidPrivateKey(priv32: ByteArray): Boolean = priv32.size == 32 && Secp256k1.secKeyVerify(priv32)

    /** (priv + tweak) mod n; throws if the result is invalid. */
    fun privAdd(priv32: ByteArray, tweak32: ByteArray): ByteArray = Secp256k1.privKeyTweakAdd(priv32, tweak32)

    fun privNegate(priv32: ByteArray): ByteArray = Secp256k1.privKeyNegate(priv32)

    /** Returns the uncompressed (65-byte) point P + tweak*G. */
    fun pubAdd(pub: ByteArray, tweak32: ByteArray): ByteArray = Secp256k1.pubKeyTweakAdd(pub, tweak32)

    /** Negated point, compressed (33 bytes). */
    fun pubNegate(pub: ByteArray): ByteArray = Secp256k1.pubKeyCompress(Secp256k1.pubKeyNegate(pub))

    fun compress(pub: ByteArray): ByteArray = Secp256k1.pubKeyCompress(pub)

    fun hasOddY(pub33: ByteArray): Boolean {
        require(pub33.size == 33)
        return pub33[0] == 0x03.toByte()
    }

    fun xOnly(pub33: ByteArray): ByteArray {
        require(pub33.size == 33)
        return pub33.copyOfRange(1, 33)
    }

    /** BIP-340 signature (64 bytes). [aux32] should be fresh random bytes. */
    fun signSchnorr(msg32: ByteArray, priv32: ByteArray, aux32: ByteArray): ByteArray {
        require(msg32.size == 32 && priv32.size == 32 && aux32.size == 32)
        return Secp256k1.signSchnorr(msg32, priv32, aux32)
    }

    fun verifySchnorr(sig64: ByteArray, msg32: ByteArray, xOnlyPub32: ByteArray): Boolean =
        sig64.size == 64 && msg32.size == 32 && xOnlyPub32.size == 32 &&
            runCatching { Secp256k1.verifySchnorr(sig64, msg32, xOnlyPub32) }.getOrDefault(false)
}
