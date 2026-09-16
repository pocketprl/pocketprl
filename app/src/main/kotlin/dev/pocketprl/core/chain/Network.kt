package dev.pocketprl.core.chain

/** Pearl network parameters (see node/chaincfg/params.go). */
enum class Network(
    val id: String,
    val displayName: String,
    val hrp: String,
    val coinType: Int,
    val defaultBlockbookUrl: String,
    val explorerUrl: String,
) {
    MAINNET(
        id = "mainnet",
        displayName = "Mainnet",
        hrp = "prl",
        coinType = 808276, // ASCII "PRL"
        defaultBlockbookUrl = "https://blockbook.pearlresearch.ai",
        explorerUrl = "https://blockbook.pearlresearch.ai",
    ),
    TESTNET2(
        id = "testnet2",
        displayName = "Testnet2",
        hrp = "tprl",
        coinType = 1,
        defaultBlockbookUrl = "https://blockbook.testnet.pearlresearch.ai",
        explorerUrl = "https://blockbook.testnet.pearlresearch.ai",
    );

    val isMainnet: Boolean get() = this == MAINNET
    val ticker: String get() = if (isMainnet) "PRL" else "tPRL"

    companion object {
        /** chaincfg: TargetTimePerBlock = 3 min 14 s on both networks. */
        const val TARGET_BLOCK_SECONDS = 194L

        /** chaincfg: CoinbaseMaturity = 100 on both networks. */
        const val COINBASE_MATURITY = 100

        fun fromId(id: String?): Network = entries.firstOrNull { it.id == id } ?: MAINNET

        /**
         * Rough human ETA for a confirmation target, e.g. "~3 min" or "~1.5 h".
         * [secondsPerBlock] defaults to the chain's target; callers with a measured
         * interval from recent blocks (see WalletRepository.blockSeconds) pass that.
         */
        fun etaForBlocks(blocks: Int, secondsPerBlock: Long = TARGET_BLOCK_SECONDS): String {
            val minutes = (blocks * secondsPerBlock + 30) / 60
            return when {
                minutes < 60 -> "~$minutes min"
                minutes % 60 < 15 -> "~${minutes / 60} h"
                else -> "~${minutes / 60}.5 h"
            }
        }
    }
}
