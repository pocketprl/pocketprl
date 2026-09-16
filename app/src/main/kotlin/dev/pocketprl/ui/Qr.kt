package dev.pocketprl.ui

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.pocketprl.core.chain.Address
import dev.pocketprl.core.chain.Amount
import dev.pocketprl.core.chain.Network

object Qr {
    fun encode(text: String, sizePx: Int, dark: Int = 0xFF0B3D3A.toInt(), light: Int = 0xFFFFFFFF.toInt()): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) for (x in 0 until sizePx) pixels[y * sizePx + x] = if (matrix[x, y]) dark else light
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }

    class PaymentRequest(val address: String, val amountGrain: Long?, val label: String?)

    private val SCHEMES = setOf("pearl", "prl", "pearlcoin")

    /**
     * Accepts a bare address or a BIP-21 style URI: `pearl:<addr>?amount=1.5&label=x`
     * (also `prl:` and the `pearl://` form some apps produce). Never throws on junk input.
     */
    fun parsePayment(raw: String, network: Network): PaymentRequest? {
        val s = raw.trim()
        val colon = s.indexOf(':')
        var body = if (colon > 0 && s.substring(0, colon).lowercase() in SCHEMES) s.substring(colon + 1) else s
        body = body.removePrefix("//")
        val q = body.indexOf('?')
        val addr = (if (q >= 0) body.substring(0, q) else body).trim()
        if (!Address.isValid(addr, network)) return null
        var amount: Long? = null
        var label: String? = null
        if (q >= 0) {
            for (kv in body.substring(q + 1).split('&')) {
                val eq = kv.indexOf('=')
                if (eq <= 0) continue
                val k = kv.substring(0, eq).lowercase()
                val v = runCatching { java.net.URLDecoder.decode(kv.substring(eq + 1), "UTF-8") }.getOrNull() ?: continue
                when (k) {
                    "amount" -> amount = Amount.parse(v)
                    "label", "message" -> if (label == null) label = v.take(80)
                }
            }
        }
        return PaymentRequest(addr, amount, label)
    }

    /** True when [raw] looks like a payment URI this app should claim (used for deep links). */
    fun isPaymentUri(raw: String): Boolean {
        val colon = raw.indexOf(':')
        return colon > 0 && raw.substring(0, colon).lowercase() in SCHEMES
    }

    fun paymentUri(address: String, amountGrain: Long?, label: String? = null): String {
        val params = buildList {
            if (amountGrain != null && amountGrain > 0) add("amount=${Amount.format(amountGrain)}")
            if (!label.isNullOrBlank()) add("label=" + java.net.URLEncoder.encode(label.trim(), "UTF-8").replace("+", "%20"))
        }
        return if (params.isEmpty()) address else "pearl:$address?" + params.joinToString("&")
    }
}
