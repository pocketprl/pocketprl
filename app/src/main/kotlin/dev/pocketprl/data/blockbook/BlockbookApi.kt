package dev.pocketprl.data.blockbook

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class BlockbookException(message: String, val httpCode: Int = 0) : IOException(message)

/** A response was larger than the client is willing to buffer in memory. */
class ResponseTooLargeException(limit: Long, path: String = "") :
    IOException("response${if (path.isEmpty()) "" else " from $path"} exceeds $limit bytes")

/** Minimal Blockbook client. Only HTTPS endpoints are accepted. */
class BlockbookApi(baseUrlProvider: () -> String, private val userAgent: String) {
    private val baseUrl = baseUrlProvider
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Evicts pooled connections; sockets kept across device sleep are usually dead on the other end. */
    fun dropIdleConnections() {
        runCatching { client.connectionPool.evictAll() }
    }

    private fun url(path: String): String {
        val base = baseUrl().trimEnd('/')
        require(base.startsWith("https://")) { "Blockbook URL must use https" }
        return base + path
    }

    /**
     * Reads a response body with a hard size cap so a hostile or broken indexer
     * cannot make the app allocate an unbounded string and OOM. The caller can
     * catch [ResponseTooLargeException] and retry with a smaller page.
     */
    private fun readBody(resp: okhttp3.Response): String {
        val path = resp.request.url.encodedPath
        val source = resp.body.source()
        val buffer = okio.Buffer()
        while (true) {
            val remaining = (MAX_RESPONSE_BYTES + 1) - buffer.size
            if (remaining <= 0) throw ResponseTooLargeException(MAX_RESPONSE_BYTES, path)
            val read = source.read(buffer, remaining)
            if (read == -1L) break
        }
        if (buffer.size > MAX_RESPONSE_BYTES) throw ResponseTooLargeException(MAX_RESPONSE_BYTES, path)
        return buffer.readString(Charsets.UTF_8)
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url(path)).header("User-Agent", userAgent).header("Accept", "application/json").get().build()
        client.newCall(req).execute().use { resp ->
            val body = readBody(resp)
            if (!resp.isSuccessful) throw BlockbookException(extractError(body) ?: "HTTP ${resp.code}", resp.code)
            body
        }
    }

    private suspend fun post(path: String, payload: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url(path)).header("User-Agent", userAgent)
            .post(payload.toRequestBody("text/plain".toMediaType())).build()
        client.newCall(req).execute().use { resp ->
            val body = readBody(resp)
            if (!resp.isSuccessful) throw BlockbookException(extractError(body) ?: "HTTP ${resp.code}", resp.code)
            body
        }
    }

    private fun extractError(body: String): String? = runCatching {
        val el = json.parseToJsonElement(body).jsonObject["error"] ?: return null
        when (el) {
            is JsonPrimitive -> el.content
            is JsonObject -> el["message"]?.jsonPrimitive?.content ?: el.toString()
            else -> el.toString()
        }
    }.getOrNull()

    suspend fun status(): BbStatus = json.decodeFromString(BbStatus.serializer(), get("/api/"))

    suspend fun address(address: String, page: Int = 1, pageSize: Int = 50): BbAddress =
        json.decodeFromString(BbAddress.serializer(), get("/api/v2/address/$address?details=txslight&page=$page&pageSize=$pageSize"))

    suspend fun utxos(address: String): List<BbUtxo> =
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(BbUtxo.serializer()), get("/api/v2/utxo/$address?confirmed=false"))

    suspend fun tx(txid: String): BbTx = json.decodeFromString(BbTx.serializer(), get("/api/v2/tx/$txid"))

    /** Fee estimate in PRL per kB as a decimal string, or null when the backend has no estimate. */
    suspend fun estimateFeePrlPerKb(blocks: Int): String? {
        val r = json.decodeFromString(BbFeeResult.serializer(), get("/api/v2/estimatefee/$blocks")).result
        return r.takeIf { it.isNotBlank() && it != "-1" }
    }

    /** Broadcasts a raw transaction; returns the txid. */
    suspend fun sendTx(hex: String): String {
        val body = post("/api/v2/sendtx/", hex)
        // A non-JSON body (a proxy error page, say) must surface the node's rejection, not a parse error.
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        extractError(body)?.let { throw BlockbookException(it) }
        // A result that is not a string primitive must not throw an unrelated
        // parse exception after the node has already accepted the transaction.
        val result = obj?.get("result") as? JsonPrimitive
        return result?.contentOrNull ?: throw BlockbookException("broadcast returned no txid")
    }

    companion object {
        /**
         * Ceiling on a single response. Generous enough for a page of 500 busy
         * transactions or a large UTXO set, while still bounding memory; callers
         * that can page should catch [ResponseTooLargeException] and retry smaller.
         */
        private const val MAX_RESPONSE_BYTES = 32L * 1024 * 1024
    }
}
