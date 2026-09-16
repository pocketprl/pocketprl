package dev.pocketprl.data

import dev.pocketprl.core.wallet.WalletKeys
import dev.pocketprl.core.crypto.wipe
import dev.pocketprl.data.vault.KeyVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory unlock state. Holds only the 32-byte data-encryption key; seed
 * bytes and account keys are materialised per operation and wiped right after.
 */
class Session(private val vault: KeyVault) {
    private val lock = Any()
    private var dek: ByteArray? = null
    private var lastActive = 0L

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked

    val isUnlocked: Boolean get() = synchronized(lock) { dek != null }

    fun unlock(dekBytes: ByteArray) {
        synchronized(lock) {
            dek?.wipe()
            dek = dekBytes.copyOf()
            dekBytes.wipe()
            lastActive = System.currentTimeMillis()
        }
        _unlocked.value = true
    }

    fun lock() {
        synchronized(lock) {
            dek?.wipe()
            dek = null
        }
        _unlocked.value = false
    }

    fun touch() {
        synchronized(lock) { lastActive = System.currentTimeMillis() }
    }

    fun idleMillis(): Long = synchronized(lock) { System.currentTimeMillis() - lastActive }

    /** Runs [block] with a fresh copy of the DEK; the copy is wiped afterwards. */
    fun <T> withDek(block: (ByteArray) -> T): T {
        val copy = synchronized(lock) { dek?.copyOf() } ?: throw LockedException()
        try {
            return block(copy)
        } finally {
            copy.wipe()
        }
    }

    /** Runs [block] with the wallet's account keys; everything is wiped afterwards. */
    fun <T> withKeys(network: dev.pocketprl.core.chain.Network, block: (WalletKeys) -> T): T = withDek { d ->
        val material = vault.openSeed(d)
        val seed = material.toSeedBytes()
        material.wipe()
        try {
            WalletKeys(seed, network).use(block)
        } finally {
            seed.wipe()
        }
    }

    class LockedException : IllegalStateException("wallet is locked")
}
