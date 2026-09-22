package dev.pocketprl.data

import dev.pocketprl.core.chain.Network
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.SecureRandom

/** One wallet on this device. Everything here is public metadata; secrets live in the vault file. */
@Serializable
data class WalletEntry(
    val id: String,
    val name: String,
    val network: String,
    val createdAt: Long,
    /** Vault file name inside `filesDir`. */
    val vaultFile: String,
    /** SQLite database name. */
    val dbName: String,
    /** Android Keystore alias for the biometric DEK wrap. */
    val keystoreAlias: String,
)

@Serializable
data class WalletList(val version: Int = 1, val wallets: List<WalletEntry> = emptyList(), val activeId: String? = null)

/**
 * Index of the wallets on this device (`filesDir/wallets.json`). An install with
 * the single-wallet layout (`vault.json` + `wallet.db`) is registered in place as
 * the default wallet on first load; nothing is moved or re-encrypted.
 */
class WalletRegistry(private val dir: File) {
    private val file = File(dir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    /** True when `wallets.json` exists but could not be decoded; the file is left untouched. */
    private val _loadFailed = MutableStateFlow(false)

    /**
     * The wallet list on disk exists but is unreadable. Returning an empty list
     * keeps the app from crashing, but the UI must not treat it as a fresh
     * install: that would orphan the existing `vault-*.json`/`wallet-*.db` files.
     */
    val loadFailed: StateFlow<Boolean> = _loadFailed

    private val _state = MutableStateFlow(load())
    val state: StateFlow<WalletList> = _state

    val wallets: List<WalletEntry> get() = _state.value.wallets
    val activeId: String? get() = _state.value.activeId

    fun get(id: String): WalletEntry? = wallets.firstOrNull { it.id == id }

    private fun load(): WalletList {
        if (file.exists()) {
            val parsed = runCatching { json.decodeFromString(WalletList.serializer(), file.readText()) }.getOrNull()
            if (parsed == null) {
                // Leave the corrupt file exactly where it is; a user-driven restore
                // (or manual repair) can still recover the wallets it indexes.
                _loadFailed.value = true
                return WalletList()
            }
            // A wallet may have been registered without being activated (first-run
            // biometric setup, or a crash in between). Never strand the user on the
            // onboarding flow with a wallet they cannot reach.
            val fixed = parsed.withResolvedActive()
            if (fixed != parsed) persist(fixed)
            return fixed
        }
        val legacy = File(dir, LEGACY_VAULT)
        if (legacy.exists()) {
            val (name, network, created) = legacyMeta(legacy)
            val entry = WalletEntry(LEGACY_ID, name, network, created, LEGACY_VAULT, LEGACY_DB, LEGACY_ALIAS)
            // persist(), not save(): this runs from the constructor, before the state flow exists.
            return WalletList(wallets = listOf(entry), activeId = LEGACY_ID).also { persist(it) }
        }
        return WalletList()
    }

    private fun legacyMeta(vault: File): Triple<String, String, Long> = runCatching {
        val o = json.parseToJsonElement(vault.readText()).jsonObject
        Triple(
            o["walletName"]?.jsonPrimitive?.content?.ifBlank { null } ?: "My Pearl Wallet",
            Network.fromId(o["network"]?.jsonPrimitive?.content).id,
            o["createdAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: vault.lastModified(),
        )
    }.getOrElse { Triple("My Pearl Wallet", Network.MAINNET.id, vault.lastModified()) }

    private fun persist(v: WalletList) {
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(WalletList.serializer(), v))
        if (!tmp.renameTo(file)) {
            file.writeText(json.encodeToString(WalletList.serializer(), v))
            tmp.delete()
        }
    }

    @Synchronized
    private fun save(v: WalletList) {
        persist(v)
        // A successful write replaces the unreadable list, so the app may leave the error screen.
        _loadFailed.value = false
        _state.value = v
    }

    /** Allocates file names for a wallet that is about to be created; nothing is persisted until [add]. */
    fun newEntry(name: String, network: Network): WalletEntry {
        var id: String
        do {
            val b = ByteArray(4).also { random.nextBytes(it) }
            id = b.joinToString("") { "%02x".format(it) }
        } while (get(id) != null || id == LEGACY_ID)
        return WalletEntry(id, name.trim().ifBlank { "Wallet" }, network.id, System.currentTimeMillis(), "vault-$id.json", "wallet-$id.db", "$LEGACY_ALIAS.$id")
    }

    /** Falls back to the most recently created wallet when [activeId] is missing or dangling. */
    private fun WalletList.withResolvedActive(): WalletList =
        if (wallets.isNotEmpty() && (activeId == null || wallets.none { it.id == activeId })) {
            copy(activeId = wallets.maxByOrNull { it.createdAt }?.id)
        } else {
            this
        }

    fun add(entry: WalletEntry, makeActive: Boolean = true) {
        val cur = _state.value
        val list = cur.wallets.filter { it.id != entry.id } + entry
        save(cur.copy(wallets = list, activeId = if (makeActive || cur.activeId == null) entry.id else cur.activeId))
    }

    /**
     * Registers a wallet without making it active. Used when first-run creation
     * defers activation until biometric setup has finished, so the onboarding
     * screen stays put instead of the tree rebuilding underneath the system prompt.
     */
    fun addInactive(entry: WalletEntry) {
        val cur = _state.value
        save(cur.copy(wallets = cur.wallets.filter { it.id != entry.id } + entry))
    }

    /** Removes the entry; if it was active, the most recently created remaining wallet becomes active. */
    fun remove(id: String) {
        val cur = _state.value
        val list = cur.wallets.filter { it.id != id }
        val active = if (cur.activeId == id) list.maxByOrNull { it.createdAt }?.id else cur.activeId
        save(cur.copy(wallets = list, activeId = active))
    }

    fun setActive(id: String) {
        val cur = _state.value
        if (cur.activeId == id || get(id) == null) return
        save(cur.copy(activeId = id))
    }

    fun rename(id: String, name: String) {
        val cur = _state.value
        save(cur.copy(wallets = cur.wallets.map { if (it.id == id) it.copy(name = name.trim()) else it }))
    }

    companion object {
        const val FILE_NAME = "wallets.json"
        const val LEGACY_ID = "default"
        const val LEGACY_VAULT = "vault.json"
        const val LEGACY_DB = "wallet.db"
        const val LEGACY_ALIAS = "pocketprl.bio.dek"
    }
}
