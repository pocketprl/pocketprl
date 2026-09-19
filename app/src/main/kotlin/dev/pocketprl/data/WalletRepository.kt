package dev.pocketprl.data

import dev.pocketprl.R
import dev.pocketprl.core.chain.Address
import dev.pocketprl.core.chain.Amount
import dev.pocketprl.core.chain.BuildResult
import dev.pocketprl.core.chain.Network
import dev.pocketprl.core.chain.SpendableUtxo
import dev.pocketprl.core.chain.TaprootSigner
import dev.pocketprl.core.chain.TxBuilder
import dev.pocketprl.core.chain.TxOut
import dev.pocketprl.core.crypto.toHex
import dev.pocketprl.core.crypto.wipe
import dev.pocketprl.core.wallet.AddressVariant
import dev.pocketprl.core.wallet.BRANCH_EXTERNAL
import dev.pocketprl.core.wallet.BRANCH_INTERNAL
import dev.pocketprl.data.blockbook.BbTx
import dev.pocketprl.data.blockbook.BlockbookApi
import dev.pocketprl.data.db.AddressRow
import dev.pocketprl.data.db.Contact
import dev.pocketprl.data.db.TxKind
import dev.pocketprl.data.db.TxRow
import dev.pocketprl.data.db.UtxoRow
import dev.pocketprl.data.db.WalletDb
import dev.pocketprl.data.price.PriceApi
import dev.pocketprl.data.vault.KeyVault
import dev.pocketprl.data.vault.SeedMaterial
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import dev.pocketprl.data.blockbook.BbStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class Balances(
    val confirmed: Long = 0,
    val unconfirmed: Long = 0,
    val immature: Long = 0,
    /**
     * Net coins arriving in still-unconfirmed transactions. Change from an
     * outgoing payment sits in [unconfirmed] too but is deliberately excluded
     * here: it is money you already had, not a payment on its way in.
     */
    val pendingIncoming: Long = 0,
) {
    val total: Long get() = confirmed + unconfirmed + immature
    val spendable: Long get() = confirmed + unconfirmed
}

data class SyncState(
    val syncing: Boolean = false,
    val tipHeight: Long = 0,
    val lastSyncAt: Long = 0,
    val error: String? = null,
    val progress: String? = null,
)

/** Summary of coinbase rewards, shown to people who mine to this wallet. */
data class MiningStats(
    val blocks: Int,
    val totalMined: Long,
    val last7Days: Long,
    val blocksLast7Days: Int,
    val immature: Long,
    /** Blocks until the oldest immature reward becomes spendable; null when nothing is maturing. */
    val nextMatureIn: Int?,
)

data class WalletSnapshot(
    val walletName: String = "",
    val network: Network = Network.MAINNET,
    val balances: Balances = Balances(),
    val recentTxs: List<TxRow> = emptyList(),
    val txCount: Int = 0,
    val receiveAddress: AddressRow? = null,
    val addressCount: Int = 0,
    val contactNames: Map<String, String> = emptyMap(),
    val notes: Map<String, String> = emptyMap(),
    val mining: MiningStats? = null,
    /** Bumps on every local change so screens holding derived lists know to re-query. */
    val revision: Long = 0,
)

data class FeeRates(val fastPerKb: Long, val mediumPerKb: Long, val fetchedAt: Long)

data class PriceState(val usdPerPrl: Double? = null, val fetchedAt: Long = 0, /** Percent, last 24 h; null when the provider had none. */ val change24h: Double? = null)

class PreparedSend(val build: BuildResult, val toAddress: String, val changeAddress: AddressRow?)

class WalletRepository(
    val db: WalletDb,
    val api: BlockbookApi,
    val session: Session,
    val vault: KeyVault,
    val settings: Settings,
    private val scope: CoroutineScope,
    private val context: android.content.Context,
    private val priceApi: PriceApi? = null,
) {
    private fun tr(resId: Int, vararg args: Any): String = context.getString(resId, *args)
    private fun trPlural(resId: Int, quantity: Int, vararg args: Any): String =
        context.resources.getQuantityString(resId, quantity, quantity, *args)
    val network: Network get() = vault.network ?: settings.preferredNetwork

    private val changed = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    private val revision = AtomicLong(0)
    private val _sync = MutableStateFlow(SyncState(tipHeight = db.getMeta(META_TIP)?.toLongOrNull() ?: 0, lastSyncAt = db.getMeta(META_LAST_SYNC)?.toLongOrNull() ?: 0))
    val syncState: StateFlow<SyncState> = _sync

    val snapshot: StateFlow<WalletSnapshot> = changed
        .onStart { emit(Unit) }
        .map { buildSnapshot() }
        .flowOn(Dispatchers.IO)
        .combine(_sync) { s, _ -> s }
        .stateIn(scope, SharingStarted.Eagerly, WalletSnapshot())

    private val syncMutex = Mutex()
    private val deriveMutex = Mutex()

    @Volatile private var feeCache: FeeRates? = null

    private val _price = MutableStateFlow(PriceState())

    /**
     * Incoming money a sync discovered, whoever ran it. [fresh] is seen for the
     * first time (usually still in the mempool); [confirmed] was pending and has a
     * block now. A wallet's first sync emits nothing, or a restore would announce
     * the whole history.
     */
    class IncomingEvent(val fresh: List<TxRow>, val confirmed: List<TxRow>)

    private val _incoming = MutableSharedFlow<IncomingEvent>(extraBufferCapacity = 16)
    val incoming: SharedFlow<IncomingEvent> = _incoming
    val priceState: StateFlow<PriceState> = _price

    private fun notifyChanged() {
        revision.incrementAndGet()
        changed.tryEmit(Unit)
    }

    val tipHeight: Long get() = _sync.value.tipHeight

    // ---------------------------------------------------------------- snapshot

    private fun buildSnapshot(): WalletSnapshot {
        val tip = _sync.value.tipHeight
        val coinbase = db.coinbaseTxids()
        var confirmed = 0L; var unconfirmed = 0L; var immature = 0L
        for (u in db.utxos()) {
            val conf = u.confirmations(tip)
            when {
                u.txid in coinbase && conf < COINBASE_MATURITY -> immature += u.value
                conf == 0 -> unconfirmed += u.value
                else -> confirmed += u.value
            }
        }
        val pendingIncoming = db.pendingTxRows().filter { !it.coinbase }.sumOf { it.netAmount.coerceAtLeast(0L) }
        return WalletSnapshot(
            walletName = vault.walletName ?: "",
            network = network,
            balances = Balances(confirmed, unconfirmed, immature, pendingIncoming),
            recentTxs = db.txs(limit = 5),
            txCount = db.txCount(),
            receiveAddress = db.firstUnused(BRANCH_EXTERNAL),
            addressCount = db.addressCount(BRANCH_EXTERNAL) + db.addressCount(BRANCH_INTERNAL),
            contactNames = db.contactNames(),
            notes = db.notes(),
            mining = miningStats(tip),
            revision = revision.get(),
        )
    }

    private fun miningStats(tip: Long): MiningStats? {
        val rewards = db.minedRewards()
        if (rewards.isEmpty()) return null
        val weekAgo = System.currentTimeMillis() / 1000 - 7 * 86_400
        var total = 0L; var week = 0L; var weekBlocks = 0; var immature = 0L
        var oldestImmatureHeight = Long.MAX_VALUE
        for ((height, time, amount) in rewards) {
            total += amount
            if (time >= weekAgo) { week += amount; weekBlocks++ }
            val conf = if (height > 0 && tip >= height) tip - height + 1 else 0
            if (conf < COINBASE_MATURITY) {
                immature += amount
                if (height > 0 && height < oldestImmatureHeight) oldestImmatureHeight = height
            }
        }
        val nextMatureIn = if (oldestImmatureHeight == Long.MAX_VALUE) null
        else (COINBASE_MATURITY - (tip - oldestImmatureHeight + 1)).toInt().coerceAtLeast(1)
        return MiningStats(rewards.size, total, week, weekBlocks, immature, nextMatureIn)
    }

    fun allTxs(): List<TxRow> = db.txs()
    fun tx(txid: String): TxRow? = db.tx(txid)
    fun addresses(): List<AddressRow> = db.addresses()

    // ---------------------------------------------------------------- contacts & notes

    fun contacts(): List<Contact> = db.contacts()

    fun saveContact(address: String, name: String) {
        require(name.isNotBlank()) { "name required" }
        db.upsertContact(address.trim(), name.trim())
        notifyChanged()
    }

    fun deleteContact(address: String) {
        db.deleteContact(address)
        notifyChanged()
    }

    fun setNote(txid: String, note: String?) {
        db.setNote(txid, note)
        notifyChanged()
    }

    // ---------------------------------------------------------------- wallet lifecycle

    /** Creates the vault, unlocks the session and derives an initial address lookahead. */
    suspend fun createWallet(name: String, net: Network, material: SeedMaterial, password: CharArray, onProgress: (String) -> Unit = {}) =
        withContext(Dispatchers.Default) {
            val dek = vault.create(name, net, material, password)
            settings.preferredNetwork = net
            session.unlock(dek) // wipes dek
            db.wipeChainState()
            _sync.value = SyncState()
            ensureLookahead(external = INITIAL_EXTERNAL, internal = INITIAL_INTERNAL, onProgress = onProgress)
            notifyChanged()
        }

    /**
     * Restores from a seed: derives the full gap-limit window up front, then
     * runs discovery (sync with expanding lookahead) until no new used
     * addresses are found.
     */
    suspend fun restoreWallet(name: String, net: Network, material: SeedMaterial, password: CharArray, onProgress: (String) -> Unit) =
        withContext(Dispatchers.Default) {
            createWallet(name, net, material, password, onProgress)
            // Nothing is marked used yet, so the default window would be too small for discovery.
            ensureLookahead(external = GAP_EXTERNAL, internal = GAP_INTERNAL, onProgress = onProgress)
            sync(full = true, onProgress = onProgress)
        }

    fun renameWallet(name: String) {
        vault.rename(name)
        notifyChanged()
    }

    /** Wipes this wallet's local state (keys, chain cache, contacts, notes). The container removes the files. */
    suspend fun deleteWallet() = withContext(Dispatchers.IO) {
        session.lock()
        vault.wipe()
        db.wipeAll()
        feeCache = null
        _price.value = PriceState()
        _sync.value = SyncState()
        notifyChanged()
    }

    // ---------------------------------------------------------------- derivation

    /**
     * Makes sure at least [external]/[internal] unused addresses exist past the
     * last used index on each branch. Requires an unlocked session.
     */
    suspend fun ensureLookahead(external: Int? = null, internal: Int? = null, onProgress: (String) -> Unit = {}): Boolean =
        deriveMutex.withLock {
            if (!session.isUnlocked) return false
            var derived = false
            withContext(Dispatchers.Default) {
                for ((branch, requested) in listOf(BRANCH_EXTERNAL to external, BRANCH_INTERNAL to internal)) {
                    val lastUsed = db.lastUsedIndex(branch)
                    // Small window until anything is used, then the full discovery gap.
                    val gap = requested ?: when {
                        lastUsed < 0 -> if (branch == BRANCH_EXTERNAL) INITIAL_EXTERNAL else INITIAL_INTERNAL
                        else -> if (branch == BRANCH_EXTERNAL) GAP_EXTERNAL else GAP_INTERNAL
                    }
                    val target = lastUsed + gap + 1
                    val have = db.addressCount(branch)
                    if (have >= target) continue
                    session.withKeys(network) { keys ->
                        for (i in have until target) {
                            onProgress(tr(if (branch == BRANCH_EXTERNAL) R.string.wallet_deriving_receive else R.string.wallet_deriving_change, i + 1, target))
                            val (plain, pq) = keys.deriveAddresses(branch, i)
                            db.transaction { db.insertAddress(plain); db.insertAddress(pq) }
                            derived = true
                        }
                    }
                }
            }
            if (derived) notifyChanged()
            derived
        }

    /** Current receive address (first unused external), deriving more if needed. */
    suspend fun receiveAddress(): AddressRow? {
        db.firstUnused(BRANCH_EXTERNAL)?.let { return it }
        ensureLookahead()
        return db.firstUnused(BRANCH_EXTERNAL)
    }

    /** The XMSS-committed twin of a plain address (same branch/index), if derived. */
    fun pqVariantOf(row: AddressRow): AddressRow? =
        if (row.variant == AddressVariant.PQ) row else db.addressAt(row.branch, row.index, AddressVariant.PQ)

    /** Skips the current receive address. Refuses to open a gap wider than the discovery window. */
    suspend fun rotateReceiveAddress(): AddressRow? {
        val current = db.firstUnused(BRANCH_EXTERNAL) ?: return receiveAddress()
        val lastUsed = db.lastUsedIndex(BRANCH_EXTERNAL)
        if (current.index - lastUsed >= GAP_EXTERNAL - 1) return current
        db.markUsed(current.address)
        ensureLookahead()
        notifyChanged()
        return db.firstUnused(BRANCH_EXTERNAL)
    }

    // ---------------------------------------------------------------- sync

    suspend fun pollTip(): Long? = runCatching { api.status().also { observeTip(it) }.blockbook.bestHeight }.getOrNull()

    // ---------------------------------------------------------------- block clock

    private val _blockSeconds = MutableStateFlow(BlockClock.load(db.getMeta(META_BLOCK_SAMPLES)).secondsPerBlock ?: Network.TARGET_BLOCK_SECONDS)
    /** Seconds per block measured from observed tips; the chain's 194 s target until enough blocks have gone by. */
    val blockSeconds: StateFlow<Long> = _blockSeconds

    /** Every status response carries the tip's timestamp: one free sample. */
    private fun observeTip(status: BbStatus) {
        val t = BlockClock.parseTime(status.blockbook.lastBlockTime) ?: return
        val clock = BlockClock.load(db.getMeta(META_BLOCK_SAMPLES)).add(status.blockbook.bestHeight, t)
        db.setMeta(META_BLOCK_SAMPLES, clock.save())
        clock.secondsPerBlock?.let { _blockSeconds.value = it }
    }

    /**
     * Refreshes history, balances and UTXOs. Serialized; concurrent callers wait.
     * Returns true on success.
     *
     * A quick sync checks the addresses in use plus a short lookahead. A full
     * sweep checks the whole gap window, which is only needed to discover
     * activity from another wallet on the same seed: it runs on the first sync,
     * on an explicit rescan ([full]), whenever discovery derives new addresses
     * and, unless [promote] is false, every [FULL_SWEEP_INTERVAL_MS].
     */
    suspend fun sync(full: Boolean = false, receiveOnly: Boolean = false, promote: Boolean = true, onProgress: (String) -> Unit = {}): Boolean = syncMutex.withLock {
        _sync.value = _sync.value.copy(syncing = true, error = null, progress = null)
        try {
            val lastFull = db.getMeta(META_LAST_FULL_SYNC)?.toLongOrNull() ?: 0L
            val seenBefore = db.getMeta(META_LAST_SYNC) != null
            val knownIncoming = if (seenBefore) db.incomingTxids() else null
            val knownPending = if (seenBefore) db.pendingIncomingTxids() else emptySet()
            val everything = full || lastFull == 0L
            var sweepAll = everything || (promote && System.currentTimeMillis() - lastFull > FULL_SWEEP_INTERVAL_MS)
            // The first request after the device wakes often lands on a dead pooled socket.
            val status = withRetry { api.status() }
            observeTip(status)
            val tip = status.blockbook.bestHeight
            val seen: MutableSet<String> = Collections.synchronizedSet(HashSet())
            val failed = AtomicInteger(0)
            var lastFailure: String? = null
            var round = 0
            val checked = HashSet<String>()
            do {
                round++
                val rows = when {
                    sweepAll -> sweepRows(everything)
                    // Change addresses only ever receive our own change, recorded locally at broadcast.
                    receiveOnly -> quickRows(db.activeAddresses(POLL_LOOKAHEAD, BRANCH_EXTERNAL))
                    else -> quickRows(db.activeAddresses(POLL_LOOKAHEAD))
                }
                // Ownership is judged against every known address, not just the ones being
                // checked, or a payment to an unchecked change address is filed as outgoing.
                val own = db.addresses().map { it.address }.toHashSet()
                val msg = trPlural(R.plurals.wallet_checking, rows.size)
                onProgress(msg)
                _sync.value = _sync.value.copy(progress = msg)
                val sem = Semaphore(CONCURRENCY)
                coroutineScope {
                    rows.map { row ->
                        async(Dispatchers.IO) {
                            sem.withPermit {
                                try {
                                    withRetry { syncAddress(row, own, seen) }
                                } catch (e: IOException) {
                                    failed.incrementAndGet()
                                    lastFailure = e.message
                                }
                            }
                        }
                    }.awaitAll()
                }
                checked += rows.map { it.address }
                val derived = ensureLookahead(onProgress = onProgress)
                // New addresses mean a higher index was used; the narrow window is not enough.
                if (derived) sweepAll = true
            } while (derived && round < MAX_DISCOVERY_ROUNDS)

            // Local pending transactions that no address reports were dropped from the mempool
            // (or replaced). Only after a complete sweep: an unchecked address may still hold them.
            if (failed.get() == 0 && sweepAll) {
                val cutoff = System.currentTimeMillis() / 1000 - PENDING_GRACE_SECONDS
                for ((txid, firstSeen) in db.pendingTxs()) {
                    if (txid !in seen && firstSeen < cutoff) db.deleteTx(txid)
                }
            }

            // UTXOs only for addresses just checked; clearing is skipped unless the address has rows.
            val withUtxos = db.utxoAddresses()
            val sem = Semaphore(CONCURRENCY)
            coroutineScope {
                db.addresses().filter { it.address in checked }.map { row ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            if (row.balance != 0L || row.unconfirmed != 0L) {
                                try {
                                    val utxos = withRetry { api.utxos(row.address) }.map {
                                        UtxoRow(it.txid, it.vout, it.value.toLong(), row.address, row.branch, row.index, if (it.height > 0) it.height else 0)
                                    }
                                    db.replaceUtxos(row.address, utxos)
                                } catch (e: IOException) {
                                    failed.incrementAndGet() // keep the previous UTXO set rather than showing zero
                                    lastFailure = e.message
                                }
                            } else if (row.address in withUtxos) {
                                db.replaceUtxos(row.address, emptyList())
                            }
                        }
                    }
                }.awaitAll()
            }
            val now = System.currentTimeMillis()
            db.setMeta(META_TIP, tip.toString())
            db.setMeta(META_LAST_SYNC, now.toString())
            if (sweepAll && failed.get() == 0) db.setMeta(META_LAST_FULL_SYNC, now.toString())
            if (knownIncoming != null) {
                val fresh = db.incomingTxids() - knownIncoming
                val settled = knownPending - db.pendingIncomingTxids()
                if (fresh.isNotEmpty() || settled.isNotEmpty()) {
                    val rows = db.txs()
                    _incoming.tryEmit(IncomingEvent(
                        fresh = rows.filter { it.txid in fresh },
                        confirmed = rows.filter { it.txid in settled && it.height > 0 },
                    ))
                }
            }
            val partial = failed.get().let { n -> if (n > 0) trPlural(R.plurals.wallet_check_failed, n, friendlyNetwork(lastFailure)) else null }
            _sync.value = _sync.value.copy(syncing = false, tipHeight = tip, lastSyncAt = now, error = partial, progress = null)
            partial == null
        } catch (e: CancellationException) {
            _sync.value = _sync.value.copy(syncing = false, progress = null)
            throw e
        } catch (e: Exception) {
            _sync.value = _sync.value.copy(syncing = false, error = friendlyNetwork(e.message ?: e.javaClass.simpleName), progress = null)
            false
        } finally {
            notifyChanged()
        }
    }

    private fun AddressRow.hasActivity() = used || txCount > 0 || balance != 0L || unconfirmed != 0L

    /**
     * Rows a full sweep checks. PQ twins are only handed out from Receive for the
     * current index and never used for change, so a twin with no activity above
     * the highest shown index can only be empty; the periodic sweep skips those.
     * A restore or explicit rescan ([everything]) checks every row.
     */
    private fun sweepRows(everything: Boolean): List<AddressRow> {
        val all = db.addresses()
        if (everything) return all
        val shownUpTo = db.lastUsedIndex(BRANCH_EXTERNAL) + 1
        return all.filter { it.variant == AddressVariant.PLAIN || it.hasActivity() || (it.branch == BRANCH_EXTERNAL && it.index <= shownUpTo) }
    }

    /** Quick sync: only the twin Receive is showing plus any with activity; the rest wait for the periodic sweep. */
    private fun quickRows(active: List<AddressRow>): List<AddressRow> {
        val current = db.firstUnused(BRANCH_EXTERNAL)?.index ?: -1
        return active.filter { it.variant == AddressVariant.PLAIN || it.hasActivity() || (it.branch == BRANCH_EXTERNAL && it.index == current) }
    }

    /** Maps socket-level error text to something a person can act on. */
    private fun friendlyNetwork(message: String?): String = when {
        message == null -> tr(R.string.sync_err_no_connection)
        message.contains("timeout", true) || message.contains("timed out", true) -> tr(R.string.sync_err_not_responding)
        message.contains("Unable to resolve host", true) || message.contains("UnknownHost", true) -> tr(R.string.sync_err_no_internet)
        message.contains("end of stream", true) || message.contains("connection abort", true) ||
            message.contains("Connection reset", true) || message.contains("ECONNREFUSED", true) -> tr(R.string.sync_err_lost_connection)
        else -> message
    }

    /** Retries transient indexer failures (timeouts, 429/5xx) with a short jittered backoff. */
    private suspend fun <T> withRetry(attempts: Int = 3, block: suspend () -> T): T {
        var n = 0
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                if (++n >= attempts) throw e
                delay(400L * (1 shl n) + (0..250).random())
            }
        }
    }

    /**
     * Page 1 carries every mempool transaction plus the newest confirmed ones. If
     * more confirmed transactions arrived than page 1 holds, the history is walked
     * again from page 1 with a larger page size. Blockbook paginates by
     * page × pageSize, so sizes must never be mixed within one walk.
     */
    private suspend fun syncAddress(row: AddressRow, own: Set<String>, seen: MutableSet<String>) {
        val first = api.address(row.address, page = 1, pageSize = PAGE_SIZE)
        for (t in first.transactions) {
            upsertFromBlockbook(t, own)
            seen += t.txid
        }
        val newConfirmed = first.txs - row.syncedTxs
        val confirmedOnFirst = first.transactions.count { it.isConfirmed }
        var backfilled = true
        if (newConfirmed > confirmedOnFirst) {
            var pageNo = 1
            var processed = 0
            while (true) {
                val page = api.address(row.address, page = pageNo, pageSize = BACKFILL_PAGE_SIZE)
                for (t in page.transactions) {
                    if (t.isConfirmed) processed++
                    if (pageNo > 1 || t.txid !in seen) upsertFromBlockbook(t, own)
                    seen += t.txid
                }
                if (processed >= newConfirmed) break
                // An empty page before the last one is the indexer cutting the walk short.
                if (pageNo >= page.totalPages) break
                if (page.transactions.isEmpty()) { backfilled = false; break }
                pageNo++
            }
        }
        db.updateAddressStats(
            address = row.address,
            used = row.used || first.txs > 0 || first.unconfirmedTxs > 0 || first.transactions.isNotEmpty(),
            txCount = first.txs,
            // Only mark the address caught up when the walk finished, or skipped transactions never come back.
            syncedTxs = if (backfilled) first.txs else row.syncedTxs,
            balance = first.balance.toLongOrNull() ?: 0,
            unconfirmed = first.unconfirmedBalance.toLongOrNull() ?: 0,
        )
    }

    private fun upsertFromBlockbook(t: BbTx, own: Set<String>) {
        var ownIn = 0L; var ownOut = 0L; var totalOut = 0L
        var ownAddress: String? = null
        var counterIn: String? = null
        var counterOut: String? = null
        for (v in t.vin) {
            val value = v.value.toLongOrNull() ?: 0
            val a = v.addresses.firstOrNull()
            if (v.isAddress && a != null && a in own) { ownIn += value; if (ownAddress == null) ownAddress = a }
            else if (v.isAddress && a != null && counterIn == null) counterIn = a
        }
        var firstOwnOut: String? = null
        for (o in t.vout) {
            val value = o.value.toLongOrNull() ?: 0
            totalOut += value
            val a = o.addresses.firstOrNull()
            if (o.isAddress && a != null && a in own) { ownOut += value; if (firstOwnOut == null) firstOwnOut = a }
            else if (o.isAddress && a != null && counterOut == null) counterOut = a
        }
        val coinbase = t.isCoinbase
        val kind = when {
            ownIn == 0L && coinbase -> TxKind.MINED
            ownIn == 0L -> TxKind.RECEIVED
            ownOut == totalOut -> TxKind.SELF
            else -> TxKind.SENT
        }
        val amount = when (kind) {
            TxKind.RECEIVED, TxKind.MINED -> ownOut
            TxKind.SENT -> totalOut - ownOut
            TxKind.SELF -> 0L
        }
        val existing = db.tx(t.txid)
        db.upsertTx(
            TxRow(
                txid = t.txid,
                height = if (t.blockHeight > 0) t.blockHeight else 0,
                blockTime = if (t.blockHeight > 0) t.blockTime else 0,
                firstSeen = existing?.firstSeen ?: (if (t.blockTime > 0) t.blockTime else System.currentTimeMillis() / 1000),
                fee = if (coinbase) 0 else (t.fees.toLongOrNull() ?: 0),
                kind = kind,
                amount = amount,
                ownIn = ownIn,
                ownOut = ownOut,
                ownAddress = if (kind == TxKind.RECEIVED || kind == TxKind.MINED) firstOwnOut else ownAddress,
                counterparty = when (kind) { TxKind.SENT -> counterOut; TxKind.RECEIVED -> counterIn; else -> null },
                nIn = t.vin.size,
                nOut = t.vout.size,
                vsize = t.vsize,
                coinbase = coinbase,
            ),
        )
    }

    // ---------------------------------------------------------------- fees

    suspend fun feeRates(force: Boolean = false): FeeRates {
        feeCache?.let { if (!force && System.currentTimeMillis() - it.fetchedAt < 60_000) return it }
        val (fast, medium) = coroutineScope {
            val f = async { api.estimateFeePrlPerKb(FAST_BLOCKS) }
            val m = async { api.estimateFeePrlPerKb(MEDIUM_BLOCKS) }
            f.await() to m.await()
        }
        fun conv(v: String?, fallback: Long) = maxOf(v?.let { Amount.prlPerKbToGrainPerKb(it) } ?: fallback, TxBuilder.MIN_RELAY_FEE_PER_KB)
        val mediumRate = conv(medium, 1_000)
        val fastRate = maxOf(conv(fast, 10_000), mediumRate)
        return FeeRates(fastRate, mediumRate, System.currentTimeMillis()).also { feeCache = it }
    }

    // ---------------------------------------------------------------- price

    suspend fun refreshPrice(force: Boolean = false): Double? {
        if (!settings.showFiat || !network.isMainnet) {
            if (_price.value.usdPerPrl != null) _price.value = PriceState()
            return null
        }
        val now = System.currentTimeMillis()
        if (!force && now - _price.value.fetchedAt < PRICE_TTL_MS) return _price.value.usdPerPrl
        val api = priceApi ?: return _price.value.usdPerPrl
        val q = runCatching { api.prlFiat() }.getOrNull()
        if (q != null) _price.value = PriceState(q.fiat, now, q.change24h)
        return _price.value.usdPerPrl
    }

    // ---------------------------------------------------------------- send

    fun spendableUtxos(): List<SpendableUtxo> {
        val tip = tipHeight
        val coinbase = db.coinbaseTxids()
        val rows = db.addresses().associateBy { it.address }
        return db.utxos().mapNotNull { u ->
            val conf = u.confirmations(tip)
            if (u.txid in coinbase && conf < COINBASE_MATURITY) return@mapNotNull null
            val row = rows[u.address] ?: return@mapNotNull null
            SpendableUtxo(u.txid, u.vout, u.value, row.script, u.branch, u.index, conf, u.address)
        }
    }

    fun maxSendable(feeRatePerKb: Long): Long = TxBuilder.maxSendable(spendableUtxos(), feeRatePerKb)

    suspend fun prepareSend(to: String, amount: Long, feeRatePerKb: Long, sendMax: Boolean): PreparedSend {
        val parsed = when (val r = Address.parse(to, network)) {
            is Address.Result.Valid -> r.parsed
            is Address.Result.Invalid -> throw IllegalArgumentException(r.reason)
        }
        val utxos = spendableUtxos()
        if (utxos.isEmpty()) throw IllegalStateException(tr(R.string.send_err_no_funds))
        var change = db.firstUnused(BRANCH_INTERNAL)
        if (change == null) { ensureLookahead(); change = db.firstUnused(BRANCH_INTERNAL) }
        val changeScript = change?.script ?: utxos.first().script
        val build = TxBuilder.build(utxos, parsed.scriptPubKey, amount, changeScript, feeRatePerKb, sendMax)
        return PreparedSend(build, to.trim(), if (build.change > 0) change else null)
    }

    /** Signs with freshly derived keys and broadcasts. Returns the txid. */
    suspend fun signAndBroadcast(p: PreparedSend): String = withContext(Dispatchers.Default) {
        val tx = p.build.tx
        // Both variants of an index share the private key; the row says which tweak applies.
        val rowsByAddr = db.addresses().associateBy { it.address }
        val prevOuts = p.build.selected.map { TxOut(it.value, it.script) }
        session.withKeys(network) { keys ->
            for ((i, u) in p.build.selected.withIndex()) {
                val row = rowsByAddr[u.address] ?: error("unknown input address")
                val priv = keys.privateKey(u.branch, u.index)
                try {
                    tx.inputs[i].witness = listOf(TaprootSigner.signKeyPath(tx, i, prevOuts, priv, row.tapscriptRoot))
                } finally {
                    priv.wipe()
                }
            }
        }
        val hex = tx.serialize().toHex()
        val txid = api.sendTx(hex)

        // Past this line the payment is on the network and nothing may throw: a broadcast
        // reported as failed gets sent twice. The next sync repairs any local bookkeeping.
        if (txid.equals(tx.txid(), ignoreCase = true)) {
            runCatching { recordOwnSend(p, txid, tx.inputs.size, tx.outputs.size, tx.vsize()) }
        }
        notifyChanged()
        scope.launch { runCatching { ensureLookahead(); sync() } }
        txid
    }

    /** Optimistic local update so the UI reflects the spend without waiting for a sync. */
    private fun recordOwnSend(p: PreparedSend, txid: String, nIn: Int, nOut: Int, vsize: Int) {
        val now = System.currentTimeMillis() / 1000
        db.transaction {
            for (u in p.build.selected) db.deleteUtxo(u.txid, u.vout)
            p.changeAddress?.let { ch ->
                db.insertUtxo(UtxoRow(txid, 1, p.build.change, ch.address, ch.branch, ch.index, 0))
                db.markUsed(ch.address)
            }
            db.upsertTx(
                TxRow(
                    txid = txid, height = 0, blockTime = 0, firstSeen = now, fee = p.build.fee, kind = TxKind.SENT,
                    amount = p.build.amount, ownIn = p.build.selected.sumOf { it.value }, ownOut = p.build.change,
                    ownAddress = p.build.selected.first().address,
                    counterparty = p.toAddress, nIn = nIn, nOut = nOut, vsize = vsize, coinbase = false,
                ),
            )
        }
    }

    companion object {
        const val GAP_EXTERNAL = 50
        const val GAP_INTERNAL = 20
        const val INITIAL_EXTERNAL = 12
        const val INITIAL_INTERNAL = 6
        const val COINBASE_MATURITY = Network.COINBASE_MATURITY
        const val FAST_BLOCKS = 1
        /** The node's estimator sits on the relay floor past about five blocks; a longer target only inflates the ETA. */
        const val MEDIUM_BLOCKS = 5
        private const val CONCURRENCY = 4
        private const val PAGE_SIZE = 50
        private const val BACKFILL_PAGE_SIZE = 500
        private const val MAX_DISCOVERY_ROUNDS = 12
        private const val PENDING_GRACE_SECONDS = 20 * 60L

        /** Unused indices past the last used one that a quick sync still checks. */
        private const val POLL_LOOKAHEAD = 6

        /**
         * How stale a full gap-window sweep may get before the next sync promotes
         * itself: the longest a payment to an address only another wallet on the
         * same seed knows about can stay invisible.
         */
        private const val FULL_SWEEP_INTERVAL_MS = 30 * 60_000L
        private const val META_TIP = "tip"
        private const val META_BLOCK_SAMPLES = "block_samples"
        private const val META_LAST_SYNC = "last_sync"
        private const val META_LAST_FULL_SYNC = "last_full_sync"
        private const val PRICE_TTL_MS = 300_000L
    }
}

/**
 * A short history of (height, block time) pairs. The interval is the span of the
 * whole window over the blocks in it, so one odd block barely moves it. Persisted
 * as one line in the meta table.
 */
class BlockClock private constructor(private val samples: List<Pair<Long, Long>>) {
    fun add(height: Long, epochSeconds: Long): BlockClock {
        val last = samples.lastOrNull()
        // Same tip again, or a reorg walking backwards.
        if (last != null && height <= last.first) return this
        return BlockClock((samples + (height to epochSeconds)).takeLast(WINDOW))
    }

    /** Null until the window spans enough blocks to mean anything. */
    val secondsPerBlock: Long?
        get() {
            val first = samples.firstOrNull() ?: return null
            val last = samples.last()
            val blocks = last.first - first.first
            if (blocks < MIN_SPAN) return null
            return ((last.second - first.second) / blocks).coerceIn(MIN_SECONDS, MAX_SECONDS)
        }

    fun save(): String = samples.joinToString(",") { "${it.first}:${it.second}" }

    companion object {
        private const val WINDOW = 30
        private const val MIN_SPAN = 3L
        private const val MIN_SECONDS = 20L
        private const val MAX_SECONDS = 1800L

        fun load(saved: String?): BlockClock = BlockClock(
            saved?.split(',')?.mapNotNull { s ->
                val i = s.indexOf(':')
                if (i <= 0) null else runCatching { s.substring(0, i).toLong() to s.substring(i + 1).toLong() }.getOrNull()
            }.orEmpty(),
        )

        /** Blockbook's lastBlockTime is RFC 3339, with or without fractional seconds or a zone offset. */
        fun parseTime(s: String): Long? =
            runCatching { java.time.OffsetDateTime.parse(s).toEpochSecond() }.getOrNull()
                ?: runCatching { java.time.Instant.parse(s).epochSecond }.getOrNull()
    }
}
