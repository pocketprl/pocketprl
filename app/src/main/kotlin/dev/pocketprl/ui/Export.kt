package dev.pocketprl.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.pocketprl.core.chain.Amount
import dev.pocketprl.core.chain.Network
import dev.pocketprl.data.db.TxKind
import dev.pocketprl.data.db.TxRow
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Transaction history as CSV, shared through the system sheet (Drive, mail, files…). */
object Export {
    private val ISO = DateTimeFormatter.ISO_INSTANT

    fun transactionsCsv(txs: List<TxRow>, network: Network, tip: Long, contacts: Map<String, String>, notes: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("date_utc,type,amount_${network.ticker.lowercase()},fee_${network.ticker.lowercase()},net_${network.ticker.lowercase()},confirmations,block,txid,counterparty,contact,own_address,note\n")
        for (t in txs) {
            val date = if (t.time > 0) ISO.format(Instant.ofEpochSecond(t.time).atOffset(ZoneOffset.UTC)) else ""
            val type = when (t.kind) { TxKind.RECEIVED -> "received"; TxKind.SENT -> "sent"; TxKind.SELF -> "self"; TxKind.MINED -> "mined" }
            val row = listOf(
                date, type, Amount.format(t.amount), Amount.format(t.fee), Amount.format(t.netAmount),
                t.confirmations(tip).toString(), if (t.height > 0) t.height.toString() else "",
                t.txid, t.counterparty ?: "", t.counterparty?.let { contacts[it] } ?: "", t.ownAddress ?: "", notes[t.txid] ?: "",
            )
            sb.append(row.joinToString(",") { csv(it) }).append('\n')
        }
        return sb.toString()
    }

    private fun csv(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /** Writes [content] to the app cache and returns a chooser intent that shares it. */
    fun shareText(context: Context, fileName: String, content: String, mime: String = "text/csv"): Intent {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { if (it.name != fileName) it.delete() }
        val file = File(dir, fileName)
        file.writeText(content)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Export history")
    }
}
