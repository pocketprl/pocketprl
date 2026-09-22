package dev.pocketprl.data.price

import dev.pocketprl.core.format.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer
import java.util.concurrent.TimeUnit

@Serializable private data class CoinexTicker(val last: String = "", val open: String = "")

@Serializable private data class CoinexResp(val code: Int = -1, val data: List<CoinexTicker> = emptyList())

/** One PRL in the configured fiat, plus the move over the last 24 hours in percent when the provider reports it. */
data class PriceQuote(val fiat: Double, val change24h: Double?)

private fun Double?.finiteOrNull(): Double? = this?.takeIf { it.isFinite() }

/** Hard cap on a provider response body so a hostile host cannot OOM the app. */
private const val MAX_BODY_BYTES = 4L * 1024 * 1024

/** Reads the body into memory only up to [MAX_BODY_BYTES]; null when it would exceed the cap. */
private fun ResponseBody.readBounded(): String? {
    val buffer = Buffer()
    val source = source()
    while (true) {
        val n = source.read(buffer, 64 * 1024L)
        if (n == -1L) break
        if (buffer.size > MAX_BODY_BYTES) return null
    }
    return buffer.readUtf8()
}

/**
 * Live PRL price, no API key, in the user's selected fiat. Order: CoinPaprika,
 * CoinGecko, CoinEx. Null when offline or unsupported.
 * CoinEx only quotes PRL/USDT, so it is used as USD only.
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
            val body = resp.body.readBounded() ?: error("response too large")
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            body
        }
    }

    /** `runCatching` swallows `CancellationException`, so each provider is preceded by a cancellation check. */
    suspend fun prlFiat(): PriceQuote? = withContext(Dispatchers.IO) {
        val code = Format.config.fiat.code.lowercase()
        val upper = code.uppercase()

        ensureActive()
        runCatching {
            withTimeout(15_000) {
                val body = get("https://api.coinpaprika.com/v1/tickers/prl-pearl-1?quotes=$upper")
                val q = json.parseToJsonElement(body).jsonObject["quotes"]?.jsonObject?.get(upper)?.jsonObject
                val price = q?.get("price")?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it > 0 }
                val change = q?.get("percent_change_24h")?.jsonPrimitive?.doubleOrNull
                price?.let { PriceQuote(it, change.finiteOrNull()) }
            }
        }.getOrNull()?.let { return@withContext it }

        ensureActive()
        runCatching {
            withTimeout(15_000) {
                val body = get("https://api.coingecko.com/api/v3/simple/price?ids=pearl-2&vs_currencies=$code&include_24hr_change=true")
                // {"pearl-2":{"eur":0.55,"eur_24h_change":-1.2}}
                val price = Regex("\"$code\"\\s*:\\s*([0-9.eE+-]+)").find(body)?.groupValues?.getOrNull(1)
                    ?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
                val change = Regex("\"${code}_24h_change\"\\s*:\\s*([0-9.eE+-]+)").find(body)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                price?.let { PriceQuote(it, change.finiteOrNull()) }
            }
        }.getOrNull()?.let { return@withContext it }

        if (code != "usd") return@withContext null

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

    /**
     * Daily price history in the user's fiat, oldest first, for the "time machine"
     * stats. CoinGecko can mint a full series in one call, but the public API caps
     * history at 365 days (days=max is 401); CoinPaprika's daily endpoint is the
     * fallback and its free plan likewise refuses anything older than a year.
     * Best-effort: null when offline or unavailable.
     */
    suspend fun priceHistory(fiatCode: String): List<PricePoint>? = withContext(Dispatchers.IO) {
        val code = fiatCode.lowercase()
        val upper = fiatCode.uppercase()

        ensureActive()
        runCatching {
            withTimeout(20_000) {
                val prices = json.parseToJsonElement(
                    get("https://api.coingecko.com/api/v3/coins/pearl-2/market_chart?vs_currency=$code&days=365"),
                ).jsonObject["prices"]?.jsonArray
                prices?.mapNotNull { el ->
                    val p = el.jsonArray
                    val ms = p.getOrNull(0)?.jsonPrimitive?.longOrNull
                    val price = p.getOrNull(1)?.jsonPrimitive?.doubleOrNull
                    if (ms != null && price != null && price.isFinite() && price > 0) PricePoint(ms / 1000, price) else null
                }?.sortedBy { it.time }?.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()?.let { return@withContext it }

        ensureActive()
        runCatching {
            withTimeout(20_000) {
                val start = java.time.LocalDate.now().minusDays(365)
                val end = java.time.LocalDate.now()
                val body = get("https://api.coinpaprika.com/v1/tickers/prl-pearl-1/historical?start=$start&end=$end&interval=1d&quote=$upper")
                json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
                    val o = el.jsonObject
                    val time = o["timestamp"]?.jsonPrimitive?.contentOrNull
                        ?.let { runCatching { java.time.Instant.parse(it).epochSecond }.getOrNull() }
                    val price = o["price"]?.jsonPrimitive?.doubleOrNull
                    if (time != null && price != null && price.isFinite() && price > 0) PricePoint(time, price) else null
                }.sortedBy { it.time }.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }
}
