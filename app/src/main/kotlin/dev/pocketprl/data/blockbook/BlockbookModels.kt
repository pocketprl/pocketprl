package dev.pocketprl.data.blockbook

import kotlinx.serialization.Serializable

/** Subset of Trezor Blockbook API v2 responses used by PocketPRL. Unknown keys are ignored. */

@Serializable
data class BbStatus(val blockbook: BbStatusBlockbook = BbStatusBlockbook(), val backend: BbStatusBackend? = null)

@Serializable
data class BbStatusBlockbook(
    val coin: String = "",
    val bestHeight: Long = 0,
    val inSync: Boolean = false,
    val inSyncMempool: Boolean = true,
    val lastBlockTime: String = "",
    val mempoolSize: Int = 0,
)

@Serializable
data class BbStatusBackend(val chain: String = "", val blocks: Long = 0, val headers: Long = 0, val subversion: String = "")

@Serializable
data class BbAddress(
    val page: Int = 1,
    val totalPages: Int = 1,
    val itemsOnPage: Int = 0,
    val address: String = "",
    val balance: String = "0",
    val totalReceived: String = "0",
    val totalSent: String = "0",
    val unconfirmedBalance: String = "0",
    val unconfirmedTxs: Int = 0,
    val txs: Int = 0,
    val transactions: List<BbTx> = emptyList(),
)

@Serializable
data class BbTx(
    val txid: String,
    val version: Int = 0,
    val vin: List<BbVin> = emptyList(),
    val vout: List<BbVout> = emptyList(),
    val blockHash: String? = null,
    val blockHeight: Long = -1,
    val confirmations: Int = 0,
    val blockTime: Long = 0,
    val size: Int = 0,
    val vsize: Int = 0,
    val value: String = "0",
    val valueIn: String = "0",
    val fees: String = "0",
) {
    val isCoinbase: Boolean get() = vin.isNotEmpty() && vin.all { it.coinbase != null || (it.txid == null && !it.isAddress) }
    val isConfirmed: Boolean get() = blockHeight > 0
}

@Serializable
data class BbVin(
    val txid: String? = null,
    val vout: Int = 0,
    val sequence: Long = 0,
    val n: Int = 0,
    val addresses: List<String> = emptyList(),
    val isAddress: Boolean = false,
    val value: String = "0",
    val coinbase: String? = null,
)

@Serializable
data class BbVout(
    val value: String = "0",
    val n: Int = 0,
    val hex: String? = null,
    val addresses: List<String> = emptyList(),
    val isAddress: Boolean = false,
    val spent: Boolean = false,
)

@Serializable
data class BbUtxo(
    val txid: String,
    val vout: Int,
    val value: String,
    val height: Long = 0,
    val confirmations: Int = 0,
    val coinbase: Boolean = false,
)

@Serializable
data class BbFeeResult(val result: String = "")
