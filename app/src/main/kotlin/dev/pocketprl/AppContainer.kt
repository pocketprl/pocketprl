package dev.pocketprl

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.pocketprl.core.chain.Network
import dev.pocketprl.data.Settings
import dev.pocketprl.data.WalletContext
import dev.pocketprl.data.WalletRegistry
import dev.pocketprl.data.notify.PaymentCheckJob
import dev.pocketprl.data.notify.PaymentNotifier
import dev.pocketprl.data.price.PriceApi
import dev.pocketprl.data.vault.SeedMaterial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Manual dependency wiring; built once by [PocketPrlApp]. Holds the global
 * pieces (settings, price feed, wallet registry) and opens one [WalletContext]
 * per wallet on demand. Exactly one wallet is *active* (shown in the UI); the
 * others stay locked but keep their context open so the background payment
 * check can sync them.
 */
class AppContainer(val appContext: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(appContext)
    val registry = WalletRegistry(appContext.filesDir)
    private val userAgent = "PocketPRL/${BuildConfig.VERSION_NAME} (Android)"
    val priceApi = PriceApi(userAgent = userAgent)

    private val contexts = HashMap<String, WalletContext>()

    /** Id of the wallet the UI shows; null when no wallet exists yet. */
    val activeId: StateFlow<String?> = registry.state.map { it.activeId }.stateIn(scope, SharingStarted.Eagerly, registry.activeId)

    /** A `pearl:` payment URI handed to the app (link tap, QR from another app) that Send should pick up. */
    val pendingPaymentUri = MutableStateFlow<String?>(null)

    @Volatile var isInForeground = false
        private set

    private var backgroundedAt = 0L

    @Volatile private var expectedReturn = 0L

    /**
     * Call right before starting something that covers the app but is part of the
     * user's flow (QR scanner, permission dialog, share sheet, explorer link), so
     * a short auto-lock does not fire while it is up.
     */
    fun expectReturnFromOwnActivity() {
        expectedReturn = System.currentTimeMillis()
    }

    init {
        // Background auto-lock.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                isInForeground = false
                backgroundedAt = System.currentTimeMillis()
            }

            override fun onStart(owner: LifecycleOwner) {
                isInForeground = true
                val timeout = settings.autoLockSeconds
                if (backgroundedAt > 0 && timeout >= 0 && !returningFromOwnActivity()) {
                    val away = System.currentTimeMillis() - backgroundedAt
                    if (timeout == 0 || away > timeout * 1000L) lockAll()
                }
                backgroundedAt = 0
                expectedReturn = 0
            }
        })
        // Idle auto-lock while on screen. "Immediately" (0) is a leaving-the-app rule, not a timer.
        scope.launch {
            while (true) {
                delay(IDLE_CHECK_MS)
                val timeout = settings.autoLockSeconds
                if (timeout <= 0 || !isInForeground) continue
                val current = active ?: continue
                if (current.session.isUnlocked && current.session.idleMillis() > timeout * 1000L) lockAll()
            }
        }

        // Re-arm the payment check in case the OS dropped it. A JobScheduler refusal must not crash startup.
        if (settings.notifyIncoming && registry.wallets.isNotEmpty()) {
            runCatching { PaymentNotifier.ensureChannel(appContext) }
            runCatching { PaymentCheckJob.schedule(appContext) }
        }
    }

    /** Opens (or returns the already open) context for a registered wallet. */
    @Synchronized
    fun context(id: String): WalletContext? {
        contexts[id]?.let { return it }
        val entry = registry.get(id) ?: return null
        return WalletContext(entry, appContext, settings, userAgent, priceApi).also { contexts[id] = it; watchIncoming(it) }
    }

    /**
     * Raises notifications for incoming money whichever sync found it: once when
     * a transaction first appears and again when it confirms.
     */
    private fun watchIncoming(ctx: WalletContext) {
        ctx.scope.launch {
            ctx.repository.incoming.collect { event ->
                if (!settings.notifyIncoming) return@collect
                val name = if (registry.wallets.size > 1) registry.get(ctx.id)?.name ?: ctx.entry.name else null
                runCatching {
                    PaymentNotifier.notify(appContext, event.fresh, ctx.repository.network, settings.hideBalance, walletName = name)
                    PaymentNotifier.notify(appContext, event.confirmed, ctx.repository.network, settings.hideBalance, walletName = name, confirmed = true)
                }
            }
        }
    }

    val active: WalletContext? get() = registry.activeId?.let { context(it) }

    /** Makes [id] the shown wallet and locks the one it replaces; only one wallet is ever unlocked. */
    fun switchTo(id: String) {
        val previous = active
        if (previous?.id == id || registry.get(id) == null) return
        registry.setActive(id)
        previous?.session?.lock()
    }

    suspend fun createWallet(name: String, network: Network, material: SeedMaterial, password: CharArray, onProgress: (String) -> Unit): WalletContext =
        openNew(name, network) { it.repository.createWallet(name, network, material, password, onProgress) }

    suspend fun restoreWallet(name: String, network: Network, material: SeedMaterial, password: CharArray, onProgress: (String) -> Unit): WalletContext =
        openNew(name, network) { it.repository.restoreWallet(name, network, material, password, onProgress) }

    private suspend fun openNew(name: String, network: Network, setup: suspend (WalletContext) -> Unit): WalletContext {
        val entry = registry.newEntry(name, network)
        val ctx = WalletContext(entry, appContext, settings, userAgent, priceApi)
        try {
            setup(ctx)
        } catch (e: Exception) {
            // Never leave a half-created wallet behind.
            runCatching { ctx.repository.deleteWallet() }
            runCatching { ctx.close() }
            appContext.deleteDatabase(entry.dbName)
            throw e
        }
        val previous = active
        synchronized(this) { contexts[entry.id] = ctx }
        watchIncoming(ctx)
        registry.add(entry, makeActive = true)
        previous?.session?.lock()
        if (settings.notifyIncoming) runCatching { PaymentCheckJob.schedule(appContext) }
        return ctx
    }

    /** Removes one wallet completely: keys, database, Keystore alias, registry entry. */
    suspend fun deleteWallet(id: String) = withContext(Dispatchers.IO) {
        val ctx = context(id) ?: return@withContext
        ctx.repository.deleteWallet()
        synchronized(this@AppContainer) { contexts.remove(id) }
        runCatching { ctx.close() }
        appContext.deleteDatabase(ctx.entry.dbName)
        registry.remove(id)
        if (registry.wallets.isEmpty()) {
            PaymentCheckJob.cancel(appContext)
            settings.notifyIncoming = false
        }
    }

    fun renameWallet(id: String, name: String) {
        context(id)?.repository?.renameWallet(name)
        registry.rename(id, name)
    }

    /** True when the app left the foreground for a self-started activity recently enough to still be that trip. */
    private fun returningFromOwnActivity(): Boolean {
        val marked = expectedReturn
        return marked > 0 && System.currentTimeMillis() - marked < OWN_ACTIVITY_GRACE_MS
    }

    fun lockAll() {
        val all = synchronized(this) { contexts.values.toList() }
        all.forEach { it.session.lock() }
    }

    companion object {
        /** How long a self-started activity may keep the app in the background before auto-lock applies again. */
        private const val OWN_ACTIVITY_GRACE_MS = 5 * 60_000L

        /** How often the idle timer is examined while the app is on screen. */
        private const val IDLE_CHECK_MS = 10_000L
    }
}
