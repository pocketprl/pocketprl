package dev.pocketprl.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.pocketprl.core.wallet.DerivedAddress
import dev.pocketprl.data.SchemaCompat

/**
 * Public wallet data only (addresses, transactions, UTXOs, contacts, notes).
 * No key material is ever stored here.
 */
class WalletDb(context: Context, name: String = "wallet.db") : SQLiteOpenHelper(context, name, null, VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(false)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createChainTables(db)
        createV2Tables(db)
    }

    private fun createChainTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE addresses (
                branch INTEGER NOT NULL, idx INTEGER NOT NULL, variant INTEGER NOT NULL DEFAULT 0, address TEXT NOT NULL UNIQUE,
                script BLOB NOT NULL, internal_pub BLOB NOT NULL, tapscript_root BLOB NOT NULL, output_key BLOB NOT NULL,
                used INTEGER NOT NULL DEFAULT 0, tx_count INTEGER NOT NULL DEFAULT 0, synced_txs INTEGER NOT NULL DEFAULT 0,
                balance INTEGER NOT NULL DEFAULT 0, unconfirmed INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (branch, idx, variant))""",
        )
        db.execSQL(
            """CREATE TABLE txs (
                txid TEXT PRIMARY KEY, height INTEGER NOT NULL, block_time INTEGER NOT NULL, first_seen INTEGER NOT NULL,
                fee INTEGER NOT NULL, kind TEXT NOT NULL, amount INTEGER NOT NULL, own_in INTEGER NOT NULL, own_out INTEGER NOT NULL,
                own_address TEXT, counterparty TEXT, n_in INTEGER NOT NULL, n_out INTEGER NOT NULL, vsize INTEGER NOT NULL,
                coinbase INTEGER NOT NULL DEFAULT 0)""",
        )
        db.execSQL("CREATE INDEX txs_time ON txs(block_time DESC, first_seen DESC)")
        db.execSQL(
            """CREATE TABLE utxos (
                txid TEXT NOT NULL, vout INTEGER NOT NULL, value INTEGER NOT NULL, address TEXT NOT NULL,
                branch INTEGER NOT NULL, idx INTEGER NOT NULL, height INTEGER NOT NULL, locked INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (txid, vout))""",
        )
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
    }

    private fun createV2Tables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS contacts (address TEXT PRIMARY KEY, name TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS tx_notes (txid TEXT PRIMARY KEY, note TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createV2Tables(db)
        if (oldVersion < 3) {
            // Addresses are keyed by (branch, idx, variant). Chain state is re-derived from the seed and
            // re-fetched from the indexer on the next unlock; contacts and notes are kept.
            for (t in listOf("addresses", "txs", "utxos", "meta")) db.execSQL("DROP TABLE IF EXISTS $t")
            createChainTables(db)
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Chain state is re-derivable from the seed and the indexer, so it is safe to reset.
        // contacts and tx_notes are user data that cannot be recovered from anywhere else, so
        // they must survive a schema downgrade intact.
        for (t in listOf("addresses", "txs", "utxos", "meta")) db.execSQL("DROP TABLE IF EXISTS $t")
        createChainTables(db)
    }

    // ---- meta ----

    fun getMeta(key: String): String? = readableDatabase.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    fun setMeta(key: String, value: String?) {
        if (value == null) writableDatabase.delete("meta", "key=?", arrayOf(key))
        else writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---- addresses ----

    fun insertAddress(d: DerivedAddress) {
        val cv = ContentValues().apply {
            put("branch", d.branch); put("idx", d.index); put("variant", d.variant); put("address", d.address)
            put("script", d.scriptPubKey); put("internal_pub", d.internalPubKey)
            put("tapscript_root", d.tapscriptRoot); put("output_key", d.outputKey)
        }
        writableDatabase.insertWithOnConflict("addresses", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun addresses(branch: Int? = null): List<AddressRow> {
        val sql = "SELECT * FROM addresses" + (if (branch != null) " WHERE branch=$branch" else "") + " ORDER BY branch, idx, variant"
        return readableDatabase.rawQuery(sql, null).use { c -> generateSequence { if (c.moveToNext()) c.toAddressRow() else null }.toList() }
    }

    /**
     * Addresses a quick sync checks: anything with history or coins, plus every
     * index up to [lookahead] past the last used one on its branch (both variants).
     * The rest of the gap window is covered by the periodic full sweep.
     */
    fun activeAddresses(lookahead: Int, branch: Int? = null): List<AddressRow> =
        readableDatabase.rawQuery(
            """SELECT * FROM addresses a
               WHERE ${if (branch != null) "a.branch=$branch AND " else ""}(
                     a.used=1 OR a.tx_count>0 OR a.balance!=0 OR a.unconfirmed!=0
                  OR a.idx <= IFNULL((SELECT MAX(b.idx) FROM addresses b WHERE b.branch=a.branch AND b.used=1), -1) + ?)
               ORDER BY a.branch, a.idx, a.variant""",
            arrayOf(lookahead.toString()),
        ).use { c -> generateSequence { if (c.moveToNext()) c.toAddressRow() else null }.toList() }

    /** Addresses that currently hold at least one UTXO row. */
    fun utxoAddresses(): Set<String> =
        readableDatabase.rawQuery("SELECT DISTINCT address FROM utxos", null)
            .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet() }

    /** Number of derived *indices* on a branch (each index has one row per variant). */
    fun addressCount(branch: Int): Int =
        readableDatabase.rawQuery("SELECT COUNT(DISTINCT idx) FROM addresses WHERE branch=?", arrayOf(branch.toString())).use { it.moveToFirst(); it.getInt(0) }

    fun lastUsedIndex(branch: Int): Int =
        readableDatabase.rawQuery("SELECT MAX(idx) FROM addresses WHERE branch=? AND used=1", arrayOf(branch.toString())).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getInt(0) else -1
        }

    /** The plain (desktop-compatible) address of the lowest index on which *no* variant has been used. */
    fun firstUnused(branch: Int): AddressRow? =
        readableDatabase.rawQuery(
            """SELECT * FROM addresses a WHERE branch=? AND variant=0
               AND NOT EXISTS (SELECT 1 FROM addresses b WHERE b.branch=a.branch AND b.idx=a.idx AND b.used=1)
               ORDER BY idx LIMIT 1""",
            arrayOf(branch.toString()),
        ).use { if (it.moveToFirst()) it.toAddressRow() else null }

    fun addressAt(branch: Int, index: Int, variant: Int): AddressRow? =
        readableDatabase.rawQuery("SELECT * FROM addresses WHERE branch=? AND idx=? AND variant=?", arrayOf(branch.toString(), index.toString(), variant.toString())).use {
            if (it.moveToFirst()) it.toAddressRow() else null
        }

    fun updateAddressStats(address: String, used: Boolean, txCount: Int, syncedTxs: Int, balance: Long, unconfirmed: Long) {
        val cv = ContentValues().apply {
            put("used", if (used) 1 else 0); put("tx_count", txCount); put("synced_txs", syncedTxs)
            put("balance", balance); put("unconfirmed", unconfirmed)
        }
        writableDatabase.update("addresses", cv, "address=?", arrayOf(address))
    }

    fun markUsed(address: String) {
        writableDatabase.execSQL("UPDATE addresses SET used=1 WHERE address=?", arrayOf(address))
    }

    // ---- txs ----

    fun upsertTx(t: TxRow) {
        val cv = ContentValues().apply {
            put("txid", t.txid); put("height", t.height); put("block_time", t.blockTime); put("first_seen", t.firstSeen)
            put("fee", t.fee); put("kind", t.kind.name); put("amount", t.amount); put("own_in", t.ownIn); put("own_out", t.ownOut)
            put("own_address", t.ownAddress); put("counterparty", t.counterparty); put("n_in", t.nIn); put("n_out", t.nOut)
            put("vsize", t.vsize); put("coinbase", if (t.coinbase) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("txs", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun tx(txid: String): TxRow? = readableDatabase.rawQuery("SELECT * FROM txs WHERE txid=?", arrayOf(txid)).use { if (it.moveToFirst()) it.toTxRow() else null }

    fun txs(limit: Int = Int.MAX_VALUE, offset: Int = 0): List<TxRow> =
        readableDatabase.rawQuery(
            "SELECT * FROM txs ORDER BY CASE WHEN height>0 THEN height ELSE 2147483647 END DESC, block_time DESC, first_seen DESC LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString()),
        ).use { c -> generateSequence { if (c.moveToNext()) c.toTxRow() else null }.toList() }

    fun txCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM txs", null).use { it.moveToFirst(); it.getInt(0) }

    /** Unconfirmed transactions with when we first saw them, oldest first. */
    fun pendingTxs(): List<Pair<String, Long>> =
        readableDatabase.rawQuery("SELECT txid, first_seen FROM txs WHERE height<=0 ORDER BY first_seen", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) to c.getLong(1) else null }.toList()
        }

    /** Full rows for everything still in the mempool, newest first. */
    fun pendingTxRows(): List<TxRow> =
        readableDatabase.rawQuery("SELECT * FROM txs WHERE height<=0 ORDER BY first_seen DESC", null)
            .use { c -> generateSequence { if (c.moveToNext()) c.toTxRow() else null }.toList() }

    /** Removes a transaction that vanished from the mempool, plus any outputs it would have created. */
    fun deleteTx(txid: String) {
        val db = writableDatabase
        db.delete("txs", "txid=?", arrayOf(txid))
        db.delete("utxos", "txid=?", arrayOf(txid))
        db.delete("tx_notes", "txid=?", arrayOf(txid))
    }

    fun coinbaseTxids(): Set<String> =
        readableDatabase.rawQuery("SELECT txid FROM txs WHERE coinbase=1", null).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet() }

    /** Txids of everything that brought coins in (received or mined). */
    fun incomingTxids(): Set<String> =
        readableDatabase.rawQuery("SELECT txid FROM txs WHERE kind IN ('RECEIVED','MINED')", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet()
        }

    /** Incoming transactions still waiting for their first confirmation. */
    fun pendingIncomingTxids(): Set<String> =
        readableDatabase.rawQuery("SELECT txid FROM txs WHERE kind IN ('RECEIVED','MINED') AND height<=0", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet()
        }

    /** (height, block_time, amount) for every coinbase reward, newest first. */
    fun minedRewards(): List<Triple<Long, Long, Long>> =
        readableDatabase.rawQuery("SELECT height, block_time, amount FROM txs WHERE kind='MINED' ORDER BY height DESC", null).use { c ->
            generateSequence { if (c.moveToNext()) Triple(c.getLong(0), c.getLong(1), c.getLong(2)) else null }.toList()
        }

    // ---- utxos ----

    fun replaceUtxos(address: String, rows: List<UtxoRow>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("utxos", "address=?", arrayOf(address))
            for (u in rows) {
                val cv = ContentValues().apply {
                    put("txid", u.txid); put("vout", u.vout); put("value", u.value); put("address", u.address)
                    put("branch", u.branch); put("idx", u.index); put("height", u.height); put("locked", 0)
                }
                db.insertWithOnConflict("utxos", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun utxos(): List<UtxoRow> =
        readableDatabase.rawQuery("SELECT * FROM utxos ORDER BY value DESC", null).use { c -> generateSequence { if (c.moveToNext()) c.toUtxoRow() else null }.toList() }

    fun deleteUtxo(txid: String, vout: Int) {
        writableDatabase.delete("utxos", "txid=? AND vout=?", arrayOf(txid, vout.toString()))
    }

    fun insertUtxo(u: UtxoRow) {
        val cv = ContentValues().apply {
            put("txid", u.txid); put("vout", u.vout); put("value", u.value); put("address", u.address)
            put("branch", u.branch); put("idx", u.index); put("height", u.height); put("locked", 0)
        }
        writableDatabase.insertWithOnConflict("utxos", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---- contacts ----

    fun contacts(): List<Contact> =
        readableDatabase.rawQuery("SELECT address, name, created_at FROM contacts ORDER BY name COLLATE NOCASE", null).use { c ->
            generateSequence { if (c.moveToNext()) Contact(c.getString(0), c.getString(1), c.getLong(2)) else null }.toList()
        }

    fun contactNames(): Map<String, String> =
        readableDatabase.rawQuery("SELECT address, name FROM contacts", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) to c.getString(1) else null }.toMap()
        }

    fun upsertContact(address: String, name: String) {
        val cv = ContentValues().apply { put("address", address); put("name", name); put("created_at", System.currentTimeMillis()) }
        writableDatabase.insertWithOnConflict("contacts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteContact(address: String) {
        writableDatabase.delete("contacts", "address=?", arrayOf(address))
    }

    // ---- notes ----

    fun notes(): Map<String, String> =
        readableDatabase.rawQuery("SELECT txid, note FROM tx_notes", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) to c.getString(1) else null }.toMap()
        }

    fun setNote(txid: String, note: String?) {
        if (note.isNullOrBlank()) writableDatabase.delete("tx_notes", "txid=?", arrayOf(txid))
        else writableDatabase.insertWithOnConflict("tx_notes", null, ContentValues().apply { put("txid", txid); put("note", note.trim()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---- misc ----

    fun <T> transaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    /** Clears chain state but keeps contacts and notes (they survive a resync). */
    fun wipeChainState() {
        val db = writableDatabase
        db.execSQL("DELETE FROM addresses"); db.execSQL("DELETE FROM txs"); db.execSQL("DELETE FROM utxos"); db.execSQL("DELETE FROM meta")
    }

    fun wipeAll() {
        wipeChainState()
        writableDatabase.execSQL("DELETE FROM contacts")
        writableDatabase.execSQL("DELETE FROM tx_notes")
    }

    private fun Cursor.toAddressRow() = AddressRow(
        branch = getInt(getColumnIndexOrThrow("branch")),
        index = getInt(getColumnIndexOrThrow("idx")),
        variant = getInt(getColumnIndexOrThrow("variant")),
        address = getString(getColumnIndexOrThrow("address")),
        script = getBlob(getColumnIndexOrThrow("script")),
        internalPub = getBlob(getColumnIndexOrThrow("internal_pub")),
        tapscriptRoot = getBlob(getColumnIndexOrThrow("tapscript_root")),
        outputKey = getBlob(getColumnIndexOrThrow("output_key")),
        used = getInt(getColumnIndexOrThrow("used")) == 1,
        txCount = getInt(getColumnIndexOrThrow("tx_count")),
        syncedTxs = getInt(getColumnIndexOrThrow("synced_txs")),
        balance = getLong(getColumnIndexOrThrow("balance")),
        unconfirmed = getLong(getColumnIndexOrThrow("unconfirmed")),
    )

    private fun Cursor.toTxRow() = TxRow(
        txid = getString(getColumnIndexOrThrow("txid")),
        height = getLong(getColumnIndexOrThrow("height")),
        blockTime = getLong(getColumnIndexOrThrow("block_time")),
        firstSeen = getLong(getColumnIndexOrThrow("first_seen")),
        fee = getLong(getColumnIndexOrThrow("fee")),
        kind = TxKind.valueOf(getString(getColumnIndexOrThrow("kind"))),
        amount = getLong(getColumnIndexOrThrow("amount")),
        ownIn = getLong(getColumnIndexOrThrow("own_in")),
        ownOut = getLong(getColumnIndexOrThrow("own_out")),
        ownAddress = getString(getColumnIndexOrThrow("own_address")),
        counterparty = getString(getColumnIndexOrThrow("counterparty")),
        nIn = getInt(getColumnIndexOrThrow("n_in")),
        nOut = getInt(getColumnIndexOrThrow("n_out")),
        vsize = getInt(getColumnIndexOrThrow("vsize")),
        coinbase = getInt(getColumnIndexOrThrow("coinbase")) == 1,
    )

    private fun Cursor.toUtxoRow() = UtxoRow(
        txid = getString(getColumnIndexOrThrow("txid")),
        vout = getInt(getColumnIndexOrThrow("vout")),
        value = getLong(getColumnIndexOrThrow("value")),
        address = getString(getColumnIndexOrThrow("address")),
        branch = getInt(getColumnIndexOrThrow("branch")),
        index = getInt(getColumnIndexOrThrow("idx")),
        height = getLong(getColumnIndexOrThrow("height")),
    )

    companion object {
        const val VERSION = SchemaCompat.DB_VERSION
    }
}

class AddressRow(
    val branch: Int,
    val index: Int,
    /** 0 = plain BIP-86 (desktop default), 1 = XMSS-committed. See AddressVariant. */
    val variant: Int,
    val address: String,
    val script: ByteArray,
    val internalPub: ByteArray,
    val tapscriptRoot: ByteArray,
    val outputKey: ByteArray,
    val used: Boolean,
    val txCount: Int,
    val syncedTxs: Int,
    val balance: Long,
    val unconfirmed: Long,
)

enum class TxKind { RECEIVED, SENT, SELF, MINED }

class TxRow(
    val txid: String,
    val height: Long,
    val blockTime: Long,
    val firstSeen: Long,
    val fee: Long,
    val kind: TxKind,
    /** Display amount in grain: received/mined -> to us; sent -> to others; self -> 0. */
    val amount: Long,
    val ownIn: Long,
    val ownOut: Long,
    val ownAddress: String?,
    val counterparty: String?,
    val nIn: Int,
    val nOut: Int,
    val vsize: Int,
    val coinbase: Boolean,
) {
    fun confirmations(tip: Long): Int = if (height > 0 && tip >= height) (tip - height + 1).toInt() else 0
    val time: Long get() = if (blockTime > 0) blockTime else firstSeen

    /** Net effect on the wallet balance, in grain (fees included). */
    val netAmount: Long
        get() = when (kind) {
            TxKind.RECEIVED, TxKind.MINED -> amount
            TxKind.SENT -> -(amount + fee)
            TxKind.SELF -> -fee
        }
}

class UtxoRow(
    val txid: String,
    val vout: Int,
    val value: Long,
    val address: String,
    val branch: Int,
    val index: Int,
    val height: Long,
) {
    fun confirmations(tip: Long): Int = if (height > 0 && tip >= height) (tip - height + 1).toInt() else 0
}

class Contact(val address: String, val name: String, val createdAt: Long)
