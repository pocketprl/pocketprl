package dev.pocketprl.data.vault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.pocketprl.core.chain.Network
import dev.pocketprl.core.crypto.Bip39
import dev.pocketprl.core.crypto.Hashes
import dev.pocketprl.core.crypto.hexToBytes
import dev.pocketprl.core.crypto.toHex
import dev.pocketprl.core.crypto.wipe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What the user backs up: either a BIP-39 mnemonic or a raw hex seed (both accepted by oyster). */
sealed class SeedMaterial {
    abstract fun toSeedBytes(): ByteArray
    abstract fun wipe()

    class Mnemonic(val words: String) : SeedMaterial() {
        override fun toSeedBytes(): ByteArray = Bip39.toSeed(words)
        override fun wipe() {}
    }

    class Hex(val bytes: ByteArray) : SeedMaterial() {
        override fun toSeedBytes(): ByteArray = bytes.copyOf()
        override fun wipe() = bytes.wipe()
    }
}

/**
 * A password check failed. [lockoutUntilMs] is the earliest wall-clock time the
 * next attempt is allowed; zero when still within the free allowance.
 * [remainingBeforeWipe] is non-null only when the user opted into erasing the
 * wallet after [AttemptPolicy.WIPE_AFTER_ATTEMPTS] failures.
 */
class WrongPasswordException(
    val attempts: Int = 0,
    val lockoutUntilMs: Long = 0,
    val remainingBeforeWipe: Int? = null,
) : Exception("wrong password")

/** The password was correct, but too many failures happened just before it; try again after [retryAfterMs]. */
class TooManyAttemptsException(val retryAfterMs: Long) : Exception("too many attempts")

/**
 * The failure that just happened reached the configured erase threshold. The
 * vault file counted it; the caller must now delete the wallet (keys, database,
 * Keystore alias and registry entry) because only this layer knows the policy.
 */
class WalletWipeException : Exception("password attempt limit reached")

/** Outcome of [KeyVault.verifyPassword] for screens that do not need the DEK. */
sealed class PasswordCheck {
    object Ok : PasswordCheck()
    data class Wrong(val remainingBeforeWipe: Int?, val lockoutUntilMs: Long) : PasswordCheck()
    data class Locked(val retryAfterMs: Long) : PasswordCheck()
    object WipeTriggered : PasswordCheck()
}

/** The vault file exists but cannot be decoded (corruption, truncation or a bad restore). */
class VaultUnreadableException : Exception("vault is unreadable (corrupt or truncated)")

/**
 * Envelope-encrypted secret store:
 *
 *   DEK (random 32 bytes)  --AES-256-GCM-->  seed material
 *   DEK is wrapped by (a) a PBKDF2-HMAC-SHA512 password key and, optionally,
 *   (b) an Android Keystore key that requires biometric authentication per use.
 *
 * Nothing in this file is ever included in OS backups (see manifest rules).
 */
class KeyVault(context: Context, fileName: String = "vault.json", private val keystoreAlias: String = "pocketprl.bio.dek") {
    private val file = File(context.filesDir, fileName)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val random = SecureRandom()

    @Serializable
    private data class Blob(val iv: String, val ct: String)

    @Serializable
    private data class VaultFile(
        val version: Int = 1,
        val walletName: String,
        val network: String,
        val createdAt: Long,
        val seedKind: String, // "mnemonic" | "hex"
        val kdfSalt: String,
        val kdfIterations: Int,
        val passwordWrappedDek: Blob,
        val biometricWrappedDek: Blob? = null,
        val seed: Blob,
        // Brute-force pacing, persisted so restarting the app does not clear it. Defaulted
        // so vault files written before these fields existed still decode.
        val failedAttempts: Int = 0,
        val lockoutUntil: Long = 0,
    )

    @Volatile
    private var cached: VaultFile? = null

    private fun load(): VaultFile? {
        cached?.let { return it }
        if (!file.exists()) return null
        val text = try { file.readText() } catch (_: Exception) { throw VaultUnreadableException() }
        val decoded = try { json.decodeFromString(VaultFile.serializer(), text) } catch (_: Exception) { throw VaultUnreadableException() }
        cached = decoded
        return decoded
    }

    /** Like [load], but a corrupt file reads as absent so UI getters never crash in composition. */
    private fun loadOrNull(): VaultFile? = runCatching { load() }.getOrNull()

    /** True when a vault file exists but cannot be decoded. */
    fun isUnreadable(): Boolean = file.exists() && runCatching { load() }.isFailure

    private fun save(v: VaultFile) {
        val bytes = json.encodeToString(VaultFile.serializer(), v).toByteArray(Charsets.UTF_8)
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                // Rename can fail across odd filesystems; fall back to a direct write.
                FileOutputStream(file).use { out ->
                    out.write(bytes)
                    out.flush()
                    out.fd.sync()
                }
                tmp.delete()
            }
        } finally {
            tmp.delete() // never leave a complete envelope behind if the rename did not happen
        }
        fsyncDir(file.parentFile)
        cached = v
    }

    fun exists(): Boolean = file.exists()
    val walletName: String? get() = loadOrNull()?.walletName
    val network: Network? get() = loadOrNull()?.let { Network.fromId(it.network) }
    val createdAt: Long? get() = loadOrNull()?.createdAt
    val biometricEnabled: Boolean get() = loadOrNull()?.biometricWrappedDek != null
    val seedKind: String? get() = loadOrNull()?.seedKind

    /**
     * Creates the vault and returns the fresh DEK so the caller can unlock the
     * session without running the (slow) password KDF a second time. The caller
     * owns the returned array and must wipe it.
     */
    fun create(walletName: String, network: Network, material: SeedMaterial, password: CharArray): ByteArray {
        require(!exists()) { "vault already exists" }
        val dek = ByteArray(32).also { random.nextBytes(it) }
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val pwKey = deriveKey(password, salt, KDF_ITERATIONS)
        try {
            val seedPlain = when (material) {
                is SeedMaterial.Mnemonic -> material.words.toByteArray(Charsets.UTF_8)
                is SeedMaterial.Hex -> material.bytes.copyOf()
            }
            val v = VaultFile(
                walletName = walletName,
                network = network.id,
                createdAt = System.currentTimeMillis(),
                seedKind = if (material is SeedMaterial.Mnemonic) "mnemonic" else "hex",
                kdfSalt = salt.toHex(),
                kdfIterations = KDF_ITERATIONS,
                passwordWrappedDek = encrypt(pwKey, dek, AAD_PW),
                seed = encrypt(dek, seedPlain, AAD_SEED),
            )
            seedPlain.wipe()
            save(v)
            return dek
        } catch (e: Exception) {
            dek.wipe()
            throw e
        } finally {
            pwKey.wipe()
        }
    }

    /**
     * Returns the DEK. Slow (PBKDF2); call off the main thread.
     *
     * Every wrong guess is counted in the vault and paced with an exponential
     * lockout. When [wipeAfter] is set (the user enabled "erase after 10 failed
     * attempts") the failure that reaches it throws [WalletWipeException] instead
     * of [WrongPasswordException]; the caller owns the actual deletion.
     */
    fun unlockWithPassword(password: CharArray, wipeAfter: Int? = null): ByteArray {
        val v = load() ?: error("no vault")
        val now = System.currentTimeMillis()
        if (v.lockoutUntil > now) throw TooManyAttemptsException(v.lockoutUntil - now)
        val pwKey = deriveKey(password, v.kdfSalt.hexToBytes(), v.kdfIterations)
        try {
            val dek = decrypt(pwKey, v.passwordWrappedDek, AAD_PW)
            if (dek != null) {
                // A correct password clears the pacing for the next time.
                if (v.failedAttempts != 0 || v.lockoutUntil != 0L) save(v.copy(failedAttempts = 0, lockoutUntil = 0L))
                return dek
            }
            val attempts = v.failedAttempts + 1
            save(v.copy(failedAttempts = attempts, lockoutUntil = now + AttemptPolicy.lockoutMs(attempts)))
            if (AttemptPolicy.isWipeThreshold(v.failedAttempts, wipeAfter)) throw WalletWipeException()
            throw WrongPasswordException(
                attempts = attempts,
                lockoutUntilMs = now + AttemptPolicy.lockoutMs(attempts),
                remainingBeforeWipe = AttemptPolicy.remainingBeforeWipe(attempts, wipeAfter),
            )
        } finally {
            pwKey.wipe()
        }
    }

    /** Non-throwing check for screens that only need a yes/no (send auth, reveal seed). */
    fun verifyPassword(password: CharArray, wipeAfter: Int? = null): PasswordCheck = try {
        unlockWithPassword(password, wipeAfter).wipe()
        PasswordCheck.Ok
    } catch (e: WrongPasswordException) {
        PasswordCheck.Wrong(e.remainingBeforeWipe, e.lockoutUntilMs)
    } catch (e: TooManyAttemptsException) {
        PasswordCheck.Locked(e.retryAfterMs)
    } catch (_: WalletWipeException) {
        PasswordCheck.WipeTriggered
    }

    /** Number of wrong guesses currently counted against this vault. */
    fun failedAttempts(): Int = loadOrNull()?.failedAttempts ?: 0

    /** Wall-clock time the current lockout expires; zero when not locked. */
    fun lockoutUntil(): Long = loadOrNull()?.lockoutUntil ?: 0L

    /** Clears the attempt counter, e.g. after the user re-enables biometrics or changes the password. */
    fun clearFailedAttempts() {
        val v = loadOrNull() ?: return
        if (v.failedAttempts != 0 || v.lockoutUntil != 0L) save(v.copy(failedAttempts = 0, lockoutUntil = 0L))
    }

    fun openSeed(dek: ByteArray): SeedMaterial {
        val v = load() ?: error("no vault")
        val plain = decrypt(dek, v.seed, AAD_SEED) ?: error("seed blob corrupt")
        return if (v.seedKind == "mnemonic") SeedMaterial.Mnemonic(String(plain, Charsets.UTF_8)).also { plain.wipe() }
        else SeedMaterial.Hex(plain)
    }

    /** Display name only; nothing cryptographic depends on it. */
    fun rename(name: String) {
        val v = load() ?: return
        save(v.copy(walletName = name.trim()))
    }

    fun changePassword(dek: ByteArray, newPassword: CharArray) {
        val v = load() ?: error("no vault")
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val pwKey = deriveKey(newPassword, salt, KDF_ITERATIONS)
        try {
            // A fresh password starts the brute-force pacing from zero.
            save(v.copy(kdfSalt = salt.toHex(), kdfIterations = KDF_ITERATIONS, passwordWrappedDek = encrypt(pwKey, dek, AAD_PW), failedAttempts = 0, lockoutUntil = 0L))
        } finally {
            pwKey.wipe()
        }
    }

    // ---- biometric wrapping (Android Keystore) ----

    /** Cipher to hand to BiometricPrompt for *enabling* biometrics (encrypt DEK). */
    fun biometricEncryptCipher(): Cipher {
        val key = getOrCreateKeystoreKey()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /** Cipher to hand to BiometricPrompt for *unlocking* (decrypt DEK). Null if biometrics not enabled. */
    fun biometricDecryptCipher(): Cipher? {
        val v = loadOrNull() ?: return null
        val blob = v.biometricWrappedDek ?: return null
        val key = getKeystoreKey() ?: return null
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.iv.hexToBytes()))
            }
        } catch (_: java.security.InvalidKeyException) {
            // The Keystore key was invalidated (a biometric was enrolled or the key
            // was cleared). Drop the unusable wrap so the UI stops offering
            // biometrics; a password unlock can re-enable it.
            runCatching { disableBiometric() }
            null
        } catch (_: java.security.GeneralSecurityException) {
            null
        }
    }

    /** After the user authenticated [cipher] (ENCRYPT mode) via BiometricPrompt. */
    fun enableBiometric(dek: ByteArray, authenticatedCipher: Cipher) {
        val v = load() ?: error("no vault")
        authenticatedCipher.updateAAD(AAD_BIO)
        val ct = authenticatedCipher.doFinal(dek)
        save(v.copy(biometricWrappedDek = Blob(authenticatedCipher.iv.toHex(), ct.toHex())))
    }

    /** After the user authenticated [cipher] (DECRYPT mode) via BiometricPrompt. Returns the DEK. */
    fun unlockWithBiometric(authenticatedCipher: Cipher): ByteArray {
        val v = load() ?: error("no vault")
        val blob = v.biometricWrappedDek ?: error("biometrics not enabled")
        authenticatedCipher.updateAAD(AAD_BIO)
        return authenticatedCipher.doFinal(blob.ct.hexToBytes())
    }

    fun disableBiometric() {
        val v = load() ?: return
        save(v.copy(biometricWrappedDek = null))
        runCatching { keyStore().deleteEntry(keystoreAlias) }
    }

    fun wipe() {
        runCatching { keyStore().deleteEntry(keystoreAlias) }
        cached = null
        // Remove the staging file too: a crash between write and rename can leave a
        // complete encrypted envelope behind that would otherwise survive deletion.
        for (f in listOf(file, File(file.parentFile, file.name + ".tmp"), File(file.parentFile, file.name + ".bak"))) {
            if (!f.exists()) continue
            // Overwrite before unlinking; flash wear-levelling makes this best-effort only.
            runCatching { f.writeBytes(ByteArray(f.length().toInt().coerceAtMost(1 shl 20))) }
            f.delete()
        }
        fsyncDir(file.parentFile)
    }

    // ---- internals ----

    /** Best-effort directory fsync so a rename is durable before the process can die. */
    private fun fsyncDir(dir: File?) {
        if (dir == null) return
        runCatching {
            val fd = android.system.Os.open(dir.absolutePath, android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val pw = String(password).toByteArray(Charsets.UTF_8)
        try {
            return Hashes.pbkdf2HmacSha512(pw, salt, iterations, 32)
        } finally {
            pw.wipe()
        }
    }

    private fun encrypt(key: ByteArray, plain: ByteArray, aad: ByteArray): Blob {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return Blob(iv.toHex(), c.doFinal(plain).toHex())
    }

    private fun decrypt(key: ByteArray, blob: Blob, aad: ByteArray): ByteArray? {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob.iv.hexToBytes()))
        c.updateAAD(aad)
        return try { c.doFinal(blob.ct.hexToBytes()) } catch (_: AEADBadTagException) { null }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getKeystoreKey(): SecretKey? = keyStore().getKey(keystoreAlias, null) as? SecretKey

    private fun getOrCreateKeystoreKey(): SecretKey {
        getKeystoreKey()?.let { return it }
        val builder = KeyGenParameterSpec.Builder(keystoreAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        try {
            gen.init(builder.setIsStrongBoxBacked(true).build())
            return gen.generateKey()
        } catch (_: Exception) {
            // No StrongBox on this device; fall back to a TEE-backed key.
        }
        gen.init(builder.setIsStrongBoxBacked(false).build())
        return gen.generateKey()
    }

    companion object {
        const val KDF_ITERATIONS = 200_000
        private val AAD_PW = "pocketprl/v1/password-dek".toByteArray()
        private val AAD_BIO = "pocketprl/v1/biometric-dek".toByteArray()
        private val AAD_SEED = "pocketprl/v1/seed".toByteArray()
    }
}
