package dev.pocketprl.core.chain

/** A spendable output owned by the wallet. [address] identifies which variant of (branch, index) it sits on. */
data class SpendableUtxo(
    val txid: String,
    val vout: Int,
    val value: Long,
    val script: ByteArray,
    val branch: Int,
    val index: Int,
    val confirmations: Int,
    val address: String = "",
)

class BuildResult(
    val tx: Transaction,
    val selected: List<SpendableUtxo>,
    val amount: Long,
    val fee: Long,
    val change: Long,
    val vsize: Int,
    val feeRatePerKb: Long,
)

class InsufficientFundsException(val needed: Long, val available: Long) :
    Exception("insufficient funds: need $needed, have $available")

/**
 * Deterministic coin selection + fee computation mirroring oyster's rules:
 * fee = vsize * feeRate/1000 (floored at the 1000 grain/kB relay minimum),
 * change below dust is folded into the fee.
 */
object TxBuilder {
    const val MIN_RELAY_FEE_PER_KB = 1000L
    const val DUST_LIMIT = 330L

    fun feeFor(vsize: Int, feeRatePerKb: Long): Long {
        val rate = maxOf(feeRatePerKb, MIN_RELAY_FEE_PER_KB)
        return (vsize.toLong() * rate + 999) / 1000
    }

    fun build(
        utxos: List<SpendableUtxo>,
        recipientScript: ByteArray,
        amount: Long,
        changeScript: ByteArray,
        feeRatePerKb: Long,
        sendMax: Boolean = false,
    ): BuildResult {
        require(utxos.isNotEmpty()) { "no spendable outputs" }
        // Confirmed first, then largest first.
        val ordered = utxos.sortedWith(compareBy<SpendableUtxo> { it.confirmations <= 0 }.thenByDescending { it.value })
        val total = ordered.sumOf { it.value }

        if (sendMax) {
            val vsize = Transaction.estimateVsize(ordered.size, 1)
            val fee = feeFor(vsize, feeRatePerKb)
            val sendAmount = total - fee
            if (sendAmount <= DUST_LIMIT) throw InsufficientFundsException(fee + DUST_LIMIT, total)
            val tx = Transaction(inputs = ordered.map { TxIn(OutPoint.fromHex(it.txid, it.vout.toLong())) },
                outputs = listOf(TxOut(sendAmount, recipientScript)))
            return BuildResult(tx, ordered, sendAmount, fee, 0, vsize, feeRatePerKb)
        }

        require(amount > 0) { "amount must be positive" }
        if (amount <= DUST_LIMIT) throw IllegalArgumentException("amount is below the dust limit")

        val selected = ArrayList<SpendableUtxo>()
        var sum = 0L
        for (u in ordered) {
            selected.add(u)
            sum += u.value
            val feeWithChange = feeFor(Transaction.estimateVsize(selected.size, 2), feeRatePerKb)
            if (sum >= amount + feeWithChange) break
            val feeNoChange = feeFor(Transaction.estimateVsize(selected.size, 1), feeRatePerKb)
            if (sum >= amount + feeNoChange && sum - amount - feeNoChange <= DUST_LIMIT) break
        }
        val feeWithChange = feeFor(Transaction.estimateVsize(selected.size, 2), feeRatePerKb)
        val feeNoChange = feeFor(Transaction.estimateVsize(selected.size, 1), feeRatePerKb)

        val outputs = ArrayList<TxOut>(2)
        outputs.add(TxOut(amount, recipientScript))
        val fee: Long
        val change: Long
        val vsize: Int
        if (sum >= amount + feeWithChange && sum - amount - feeWithChange > DUST_LIMIT) {
            change = sum - amount - feeWithChange
            fee = feeWithChange
            vsize = Transaction.estimateVsize(selected.size, 2)
            outputs.add(TxOut(change, changeScript))
        } else if (sum >= amount + feeNoChange) {
            change = 0
            fee = sum - amount // dust remainder goes to miners
            vsize = Transaction.estimateVsize(selected.size, 1)
        } else {
            throw InsufficientFundsException(amount + feeWithChange, total)
        }
        val tx = Transaction(inputs = selected.map { TxIn(OutPoint.fromHex(it.txid, it.vout.toLong())) }, outputs = outputs)
        return BuildResult(tx, selected, amount, fee, change, vsize, feeRatePerKb)
    }

    /** Largest amount that can be sent with all UTXOs at the given rate (0 if nothing). */
    fun maxSendable(utxos: List<SpendableUtxo>, feeRatePerKb: Long): Long {
        if (utxos.isEmpty()) return 0
        val fee = feeFor(Transaction.estimateVsize(utxos.size, 1), feeRatePerKb)
        return maxOf(0L, utxos.sumOf { it.value } - fee)
    }
}
