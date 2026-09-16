package dev.pocketprl.ui.vm

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pocketprl.AppContainer
import dev.pocketprl.PocketPrlApp
import dev.pocketprl.core.chain.Address
import dev.pocketprl.core.chain.Amount
import dev.pocketprl.core.chain.InsufficientFundsException
import dev.pocketprl.core.chain.Network
import dev.pocketprl.core.chain.TxBuilder
import dev.pocketprl.core.crypto.Bip39
import dev.pocketprl.core.crypto.hexToBytes
import dev.pocketprl.core.crypto.isHex
import dev.pocketprl.data.FeeRates
import dev.pocketprl.data.PreparedSend
import dev.pocketprl.data.ThemeMode
import dev.pocketprl.data.WalletContext
import dev.pocketprl.data.WalletList
import dev.pocketprl.data.db.AddressRow
import dev.pocketprl.data.db.Contact
import dev.pocketprl.data.notify.PaymentCheckJob
import dev.pocketprl.data.notify.PaymentNotifier
import dev.pocketprl.data.vault.SeedMaterial
import dev.pocketprl.data.vault.WrongPasswordException
import dev.pocketprl.ui.Export
import dev.pocketprl.ui.Qr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher

/** Wallet-scoped view models get the active [WalletContext]; onboarding only needs the container. */
class VmFactory(private val c: AppContainer, private val ctx: WalletContext?) : ViewModelProvider.Factory {
    private fun wallet(): WalletContext = ctx ?: throw IllegalStateException("no active wallet")

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(WalletViewModel::class.java) -> WalletViewModel(c, wallet())
        modelClass.isAssignableFrom(OnboardingViewModel::class.java) -> OnboardingViewModel(c)
        modelClass.isAssignableFrom(UnlockViewModel::class.java) -> UnlockViewModel(c, wallet())
        modelClass.isAssignableFrom(SendViewModel::class.java) -> SendViewModel(c, wallet())
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(c, wallet())
        else -> throw IllegalArgumentException("unknown ${modelClass.name}")
    } as T
}

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as PocketPrlApp).container

/** View models are keyed by the active wallet so switching wallets never reuses another wallet's state. */
@Composable
inline fun <reified T : ViewModel> appViewModel(): T {
    val c = appContainer()
    val activeId by c.activeId.collectAsStateWithLifecycle()
    val ctx = activeId?.let { c.context(it) }
    return viewModel(key = T::class.java.name + "#" + (activeId ?: "none"), factory = VmFactory(c, ctx))
}

// ---------------------------------------------------------------- wallet

class WalletViewModel(private val c: AppContainer, private val ctx: WalletContext) : ViewModel() {
    private val repo = ctx.repository
    val walletId: String get() = ctx.id
    val snapshot = repo.snapshot
    val sync = repo.syncState
    val blockSeconds = repo.blockSeconds
    val settings = c.settings.state
    val price = repo.priceState
    val unlocked = ctx.session.unlocked
    val wallets: StateFlow<WalletList> = c.registry.state
    val network: Network get() = repo.network

    private var polling: Job? = null
    private var lastTip = -1L

    /** Pull-to-refresh: a quick sync that never promotes itself to a full gap-window sweep. */
    fun refresh() {
        viewModelScope.launch { repo.sync(promote = false) }
        refreshPrice(force = true)
    }

    fun refreshPrice(force: Boolean = false) {
        viewModelScope.launch { runCatching { repo.refreshPrice(force) } }
    }

    fun toggleHideBalance() { c.settings.hideBalance = !c.settings.hideBalance }

    /** Started on every resume and cancelled on pause. */
    fun startPolling() {
        if (polling?.isActive == true) return
        polling = viewModelScope.launch {
            // Sockets pooled before the device slept are usually dead.
            ctx.api.dropIdleConnections()
            repo.ensureLookahead()
            repo.sync()
            runCatching { repo.refreshPrice() }
            lastTip = repo.tipHeight
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (c.registry.activeId != ctx.id) break
                if (!ctx.session.isUnlocked) continue
                val tip = repo.pollTip()
                val stale = System.currentTimeMillis() - repo.syncState.value.lastSyncAt > FULL_SYNC_INTERVAL_MS
                if (tip == null || tip != lastTip || stale) {
                    repo.sync()
                    lastTip = repo.tipHeight
                    runCatching { repo.refreshPrice() }
                }
            }
        }
    }

    fun stopPolling() {
        polling?.cancel()
        polling = null
    }

    fun lock() {
        stopPolling()
        ctx.session.lock()
    }

    fun switchWallet(id: String) = c.switchTo(id)

    suspend fun receiveAddress(): AddressRow? = withContext(Dispatchers.IO) { repo.receiveAddress() }
    suspend fun rotateReceiveAddress(): AddressRow? = withContext(Dispatchers.IO) { repo.rotateReceiveAddress() }
    suspend fun pqVariant(row: AddressRow): AddressRow? = withContext(Dispatchers.IO) { repo.pqVariantOf(row) }
    fun allTxs() = repo.allTxs()
    fun tx(txid: String) = repo.tx(txid)
    fun explorerTxUrl(txid: String) = "${network.explorerUrl}/tx/$txid"
    fun explorerAddressUrl(address: String) = "${network.explorerUrl}/address/$address"

    // contacts & notes
    fun contacts(): List<Contact> = repo.contacts()
    fun saveContact(address: String, name: String) = repo.saveContact(address, name)
    fun deleteContact(address: String) = repo.deleteContact(address)
    fun setNote(txid: String, note: String?) = repo.setNote(txid, note)

    fun exportCsvIntent(context: Context): Intent {
        val snap = snapshot.value
        val csv = Export.transactionsCsv(repo.allTxs(), network, repo.tipHeight, snap.contactNames, snap.notes)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return Export.shareText(context, "pocketprl-${network.id}-$stamp.csv", csv)
    }

    companion object {
        const val POLL_INTERVAL_MS = 15_000L
        const val FULL_SYNC_INTERVAL_MS = 90_000L
    }
}

// ---------------------------------------------------------------- onboarding

class OnboardingViewModel(private val c: AppContainer) : ViewModel() {
    data class State(
        val busy: Boolean = false,
        val progress: String? = null,
        val error: String? = null,
        val mnemonic: String? = null,
        val done: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    val hasWallets: Boolean get() = c.registry.wallets.isNotEmpty()

    val network: StateFlow<Network> = c.settings.state.map { it.preferredNetwork }
        .stateIn(viewModelScope, SharingStarted.Eagerly, c.settings.preferredNetwork)

    fun setNetwork(n: Network) { c.settings.preferredNetwork = n }

    fun generateMnemonic() {
        _state.update { it.copy(mnemonic = Bip39.generate(128)) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun create(name: String, password: String) {
        val m = _state.value.mnemonic ?: return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                c.createWallet(name.trim(), network.value, SeedMaterial.Mnemonic(m), password.toCharArray()) { p ->
                    _state.update { it.copy(progress = p) }
                }
                _state.update { it.copy(busy = false, done = true, progress = null) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.message ?: "Failed to create wallet", progress = null) }
            }
        }
    }

    sealed class SeedCheck {
        class Ok(val material: SeedMaterial, val description: String) : SeedCheck()
        class Bad(val reason: String) : SeedCheck()
    }

    /** Mirrors the desktop wallet: BIP-39 phrase (12/15/18/21/24 words) or a 16..64 byte hex seed. */
    fun checkSeedInput(raw: String): SeedCheck {
        val t = raw.trim()
        if (t.isEmpty()) return SeedCheck.Bad("Enter your recovery phrase")
        if (!t.contains(Regex("\\s"))) {
            val h = t.lowercase().removePrefix("0x")
            if (h.isHex()) {
                if (h.length % 2 != 0) return SeedCheck.Bad("Hex seed must have an even number of characters")
                val n = h.length / 2
                if (n < 16 || n > 64) return SeedCheck.Bad("Hex seed must be between 128 and 512 bits")
                return SeedCheck.Ok(SeedMaterial.Hex(h.hexToBytes()), "$n-byte hex seed")
            }
            return SeedCheck.Bad("Enter a 12–24 word recovery phrase or a hex seed")
        }
        return when (val v = Bip39.validate(t)) {
            is Bip39.Validation.Ok -> SeedCheck.Ok(SeedMaterial.Mnemonic(v.normalized), "${v.wordCount}-word recovery phrase")
            is Bip39.Validation.Invalid -> SeedCheck.Bad(v.reason)
        }
    }

    fun restore(name: String, material: SeedMaterial, password: String) {
        _state.update { it.copy(busy = true, error = null, progress = "Preparing wallet…") }
        viewModelScope.launch {
            try {
                c.restoreWallet(name.trim(), network.value, material, password.toCharArray()) { p ->
                    _state.update { it.copy(progress = p) }
                }
                _state.update { it.copy(busy = false, done = true, progress = null) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.message ?: "Restore failed", progress = null) }
            }
        }
    }
}

// ---------------------------------------------------------------- unlock

class UnlockViewModel(private val c: AppContainer, private val ctx: WalletContext) : ViewModel() {
    data class State(val busy: Boolean = false, val error: String? = null, val unlocked: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    val walletId: String get() = ctx.id
    val walletName: String get() = ctx.vault.walletName ?: ctx.entry.name
    val network: Network get() = ctx.repository.network
    val biometricEnabled: Boolean get() = ctx.vault.biometricEnabled
    val wallets: StateFlow<WalletList> = c.registry.state

    fun unlockWithPassword(password: String) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val dek = withContext(Dispatchers.Default) { ctx.vault.unlockWithPassword(password.toCharArray()) }
                ctx.session.unlock(dek)
                _state.update { it.copy(busy = false, unlocked = true) }
            } catch (_: WrongPasswordException) {
                _state.update { it.copy(busy = false, error = "Incorrect password") }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.message ?: "Unlock failed") }
            }
        }
    }

    fun biometricCipher(): Cipher? = runCatching { ctx.vault.biometricDecryptCipher() }.getOrNull()

    fun unlockWithBiometric(cipher: Cipher) {
        try {
            ctx.session.unlock(ctx.vault.unlockWithBiometric(cipher))
            _state.update { it.copy(unlocked = true, error = null) }
        } catch (e: Exception) {
            _state.update { it.copy(error = "Biometric unlock failed: ${e.message}. Use your password.") }
        }
    }

    fun setError(msg: String?) = _state.update { it.copy(error = msg) }

    fun switchWallet(id: String) = c.switchTo(id)

    suspend fun deleteWallet() = c.deleteWallet(ctx.id)
}

// ---------------------------------------------------------------- send

enum class FeeTier { FAST, MEDIUM, CUSTOM }

class SendViewModel(private val c: AppContainer, private val ctx: WalletContext) : ViewModel() {
    data class State(
        val address: String = "",
        val addressError: String? = null,
        val contactName: String? = null,
        val label: String? = null,
        val amountText: String = "",
        val amountError: String? = null,
        val sendMax: Boolean = false,
        val tier: FeeTier = FeeTier.MEDIUM,
        val customRateText: String = "",
        val fees: FeeRates? = null,
        val feeError: String? = null,
        val prepared: PreparedSend? = null,
        val prepareError: String? = null,
        val sending: Boolean = false,
        val sentTxid: String? = null,
        val error: String? = null,
    ) {
        val feeRatePerKb: Long?
            get() = when (tier) {
                FeeTier.FAST -> fees?.fastPerKb
                FeeTier.MEDIUM -> fees?.mediumPerKb
                FeeTier.CUSTOM -> customRateText.replace(',', '.').toDoubleOrNull()?.let { (it * 1000).toLong() }?.takeIf { it >= TxBuilder.MIN_RELAY_FEE_PER_KB }
            }
    }

    private val repo = ctx.repository
    private val _state = MutableStateFlow(State(tier = when (c.settings.feeTier) { "fast" -> FeeTier.FAST; else -> FeeTier.MEDIUM }))
    val state: StateFlow<State> = _state
    val network: Network get() = repo.network
    val requireAuth: Boolean get() = c.settings.requireAuthToSend
    val biometricEnabled: Boolean get() = ctx.vault.biometricEnabled
    val usdPerPrl: Double? get() = repo.priceState.value.usdPerPrl
    val showFiat: Boolean get() = c.settings.showFiat && network.isMainnet

    private var prepareJob: Job? = null

    init {
        loadFees()
    }

    fun loadFees() {
        viewModelScope.launch {
            try {
                val f = repo.feeRates(force = true)
                _state.update { it.copy(fees = f, feeError = null) }
                reprepare()
            } catch (e: Exception) {
                _state.update { it.copy(feeError = "Could not load fee estimates: ${e.message}") }
            }
        }
    }

    fun contacts(): List<Contact> = repo.contacts()

    fun setAddress(v: String) {
        val trimmed = v.trim()
        val name = repo.snapshot.value.contactNames[trimmed]
        _state.update { it.copy(address = v, addressError = null, contactName = name, error = null) }
        reprepare()
    }

    fun setAmount(v: String) { _state.update { it.copy(amountText = v, amountError = null, sendMax = false, error = null) }; reprepare() }

    fun setTier(t: FeeTier) {
        _state.update { it.copy(tier = t, error = null) }
        c.settings.feeTier = when (t) { FeeTier.FAST -> "fast"; else -> "medium" }
        reprepare()
    }

    fun setCustomRate(v: String) { _state.update { it.copy(customRateText = v, error = null) }; reprepare() }

    fun useMax() {
        val rate = _state.value.feeRatePerKb ?: return
        val max = repo.maxSendable(rate)
        _state.update { it.copy(sendMax = true, amountText = Amount.format(max), amountError = null, error = null) }
        reprepare()
    }

    /** Fills the form from a `pearl:` URI or a bare address. Returns false when it is not usable on this network. */
    fun applyPaymentRequest(raw: String): Boolean {
        val pr = Qr.parsePayment(raw, network)
        if (pr == null) {
            _state.update { it.copy(error = "That link is not a valid ${network.displayName} payment request.") }
            return false
        }
        setAddress(pr.address)
        pr.amountGrain?.let { setAmount(Amount.format(it)) }
        _state.update { it.copy(label = pr.label) }
        return true
    }

    fun validateAddressNow(): Boolean {
        val s = _state.value
        val r = Address.parse(s.address, network)
        return if (r is Address.Result.Invalid && s.address.isNotBlank()) { _state.update { it.copy(addressError = r.reason) }; false } else true
    }

    private fun reprepare() {
        prepareJob?.cancel()
        prepareJob = viewModelScope.launch {
            delay(150)
            val s = _state.value
            val rate = s.feeRatePerKb
            val amount = Amount.parse(s.amountText)
            if (rate == null || s.address.isBlank() || (amount == null && !s.sendMax) || Address.parse(s.address, network) !is Address.Result.Valid) {
                _state.update { it.copy(prepared = null, prepareError = null) }
                return@launch
            }
            try {
                val p = withContext(Dispatchers.Default) { repo.prepareSend(s.address.trim(), amount ?: 0L, rate, s.sendMax) }
                _state.update {
                    // With MAX the amount depends on the fee rate, so show what will actually be sent.
                    val text = if (it.sendMax) Amount.format(p.build.amount) else it.amountText
                    it.copy(prepared = p, prepareError = null, amountText = text)
                }
            } catch (e: Exception) {
                _state.update { it.copy(prepared = null, prepareError = friendly(e)) }
            }
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is InsufficientFundsException ->
            "Insufficient funds: need ${Amount.pretty(e.needed, 8)} ${network.ticker} including the fee, have ${Amount.pretty(e.available, 8)}"
        else -> e.message ?: e.javaClass.simpleName
    }

    /**
     * Called after the user authenticated (or when auth is not required). [prepared] is the
     * exact transaction the user approved; it is passed in rather than re-read from state so a
     * form rewritten during authentication (e.g. by a `pearl:` link) can never be broadcast unseen.
     */
    fun send(prepared: PreparedSend) {
        val p = _state.value.prepared
        if (p !== prepared) {
            _state.update { it.copy(sending = false, error = "The payment changed while you were confirming it. Check the details and try again.") }
            return
        }
        _state.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            try {
                val txid = repo.signAndBroadcast(p)
                _state.value.label?.takeIf { it.isNotBlank() }?.let { repo.setNote(txid, it) }
                _state.update { it.copy(sending = false, sentTxid = txid) }
            } catch (e: Exception) {
                _state.update { it.copy(sending = false, error = friendly(e)) }
            }
        }
    }

    fun verifyPassword(pw: String): Boolean = ctx.vault.verifyPassword(pw.toCharArray())
    fun biometricCipher(): Cipher? = runCatching { ctx.vault.biometricDecryptCipher() }.getOrNull()
    fun confirmBiometric(cipher: Cipher): Boolean = runCatching { ctx.vault.unlockWithBiometric(cipher).fill(0); true }.getOrDefault(false)

    fun reset() { _state.update { State(tier = it.tier, fees = it.fees) } }
}

// ---------------------------------------------------------------- settings

class SettingsViewModel(private val c: AppContainer, private val ctx: WalletContext) : ViewModel() {
    val settings = c.settings.state
    val wallets: StateFlow<WalletList> = c.registry.state
    val walletId: String get() = ctx.id
    val network: Network get() = ctx.repository.network
    val walletName: String get() = ctx.vault.walletName ?: ctx.entry.name
    val biometricEnabled: Boolean get() = ctx.vault.biometricEnabled
    val seedKind: String get() = ctx.vault.seedKind ?: "mnemonic"
    val createdAt: Long get() = ctx.vault.createdAt ?: ctx.entry.createdAt
    val snapshot = ctx.repository.snapshot
    val syncState = ctx.repository.syncState

    /** Full sweep of every derived address, both variants. */
    fun rescan() {
        viewModelScope.launch { ctx.repository.sync(full = true) }
    }

    fun setAutoLock(seconds: Int) { c.settings.autoLockSeconds = seconds }
    fun setRequireAuthToSend(v: Boolean) { c.settings.requireAuthToSend = v }
    fun setHideBalance(v: Boolean) { c.settings.hideBalance = v }
    fun setThemeMode(mode: ThemeMode) { c.settings.themeMode = mode }

    fun setShowFiat(v: Boolean) {
        c.settings.showFiat = v
        viewModelScope.launch { runCatching { ctx.repository.refreshPrice(force = true) } }
    }

    /** Only call once POST_NOTIFICATIONS has been granted (or is not required). */
    fun setNotifyIncoming(v: Boolean) {
        c.settings.notifyIncoming = v
        if (v) {
            // Create the channel now so it is visible in system settings before the first payment.
            runCatching { PaymentNotifier.ensureChannel(c.appContext) }
            runCatching { PaymentCheckJob.schedule(c.appContext) }
        } else {
            runCatching { PaymentCheckJob.cancel(c.appContext) }
        }
    }

    fun blockbookUrl(): String = c.settings.blockbookUrl(network)
    fun defaultBlockbookUrl(): String = network.defaultBlockbookUrl
    fun setBlockbookUrl(url: String?) = c.settings.setBlockbookUrl(network, url)

    fun verifyPassword(pw: String): Boolean = ctx.vault.verifyPassword(pw.toCharArray())

    suspend fun changePassword(current: String, new: String): String? = withContext(Dispatchers.Default) {
        try {
            val dek = ctx.vault.unlockWithPassword(current.toCharArray())
            try { ctx.vault.changePassword(dek, new.toCharArray()) } finally { dek.fill(0) }
            null
        } catch (_: WrongPasswordException) { "Current password is incorrect" } catch (e: Exception) { e.message ?: "Failed" }
    }

    fun biometricEncryptCipher(): Cipher? = runCatching { ctx.vault.biometricEncryptCipher() }.getOrNull()

    fun enableBiometric(cipher: Cipher): String? = try {
        ctx.session.withDek { dek -> ctx.vault.enableBiometric(dek, cipher) }
        null
    } catch (e: Exception) { e.message ?: "Failed to enable biometrics" }

    fun disableBiometric() = ctx.vault.disableBiometric()

    /** Requires the session to be unlocked; the screen additionally re-authenticates. */
    fun revealSeed(): SeedMaterial = ctx.session.withDek { dek -> ctx.vault.openSeed(dek) }

    fun renameWallet(name: String) = c.renameWallet(ctx.id, name)
    fun switchWallet(id: String) = c.switchTo(id)
    suspend fun deleteWallet() = c.deleteWallet(ctx.id)

    fun addresses() = ctx.repository.addresses()
    fun explorerUrl() = network.explorerUrl

    fun contacts(): List<Contact> = ctx.repository.contacts()
    fun saveContact(address: String, name: String): String? {
        if (Address.parse(address, network) !is Address.Result.Valid) return "Not a valid ${network.displayName} address"
        if (name.isBlank()) return "Give the contact a name"
        ctx.repository.saveContact(address, name)
        return null
    }
    fun deleteContact(address: String) = ctx.repository.deleteContact(address)
}
