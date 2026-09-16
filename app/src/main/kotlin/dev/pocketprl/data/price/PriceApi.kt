package dev.pocketprl.data.price

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable private data class PaprikaQuote(val price: Double = 0.0, val percent_change_24h: Double? = null)
@Serializable private data class PaprikaQuotes(val USD: PaprikaQuote? = null)
@Serializable private data class PaprikaTicker(val id: String = "", val quotes: PaprikaQuotes? = null)

@Serializable private data class CoinexTicker(val last: String = "", val open: String = "")
@Serializable private data class CoinexResp(val code: Int = -1, val data: List<CoinexTicker> = emptyList())

/** One PRL in USD, plus the move over the last 24 hours in percent when the provider reports it. */
data class PriceQuote(val usd: Double, val change24h: Double?)

private fun Double?.finiteOrNull(): Double? = this?.takeIf { it.isFinite() }

/**
 * Live PRL to USD, no API key. Order: CoinPaprika, CoinGecko, CoinEx. Null when offline.
 * MEXC PRL/USDT and CoinEx PRLUSDT are Perle, not Pearl. Do not use them.
 */
class PriceApi(private val userAgent: String) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("User-Agent", userAgent).header("Accept", "application/json").get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            body
        }
    }

    /** `runCatching` swallows `CancellationException`, so each provider is preceded by a cancellation check. */
    suspend fun prlUsd(): PriceQuote? = withContext(Dispatchers.IO) {
        ensureActive()
        runCatching {
            withTimeout(15_000) {
                val q = json.decodeFromString(
                    PaprikaTicker.serializer(),
                    get("https://api.coinpaprika.com/v1/tickers/prl-pearl-1?quotes=USD"),
                ).quotes?.USD
                q?.price?.takeIf { it.isFinite() && it > 0 }?.let { PriceQuote(it, q.percent_change_24h.finiteOrNull()) }
            }
        }.getOrNull()?.let { return@withContext it }

        ensureActive()
        runCatching {
            withTimeout(15_000) {
                val body = get("https://api.coingecko.com/api/v3/simple/price?ids=pearl-2&vs_currencies=usd&include_24hr_change=true")
                // {"pearl-2":{"usd":0.55,"usd_24h_change":-1.2}}: parsed leniently without a strict model.
                val usd = Regex("\"usd\"\\s*:\\s*([0-9.eE+-]+)").find(body)?.groupValues?.getOrNull(1)
                    ?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
                val change = Regex("\"usd_24h_change\"\\s*:\\s*([0-9.eE+-]+)").find(body)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                usd?.let { PriceQuote(it, change.finiteOrNull()) }
            }
        }.getOrNull()?.let { return@withContext it }

        ensureActive()
        runCatching {
            withTimeout(15_000) {
                val t = json.decodeFromString(
                    CoinexResp.serializer(),
                    get("https://api.coinex.com/v2/spot/ticker?market=PEARLUSDT"),
                ).data.firstOrNull()
                val last = t?.last?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
                // CoinEx reports no percentage, only the price 24 h ago.
                val open = t?.open?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
                last?.let { PriceQuote(it, open?.let { o -> (it - o) / o * 100 }.finiteOrNull()) }
            }
        }.getOrNull()
    }
}
