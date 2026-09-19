package dev.pocketprl.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Latest published GitHub release of PocketPRL, or null when it cannot be read. */
data class ReleaseInfo(
    /** Git tag, e.g. "v1.1.0". */
    val tagName: String,
    /** Bare numeric version, e.g. "1.1.0". */
    val version: String,
    /** Release page to open in a browser. */
    val htmlUrl: String,
)

/**
 * Reads the newest release from the public PocketPRL repo. Anonymous, read-only,
 * no address or wallet data is ever sent; this only ever sees the GitHub API.
 */
object UpdateChecker {
    const val REPO_URL = "https://github.com/pocketprl/pocketprl"

    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/pocketprl/pocketprl/releases/latest"
    private const val USER_AGENT = "PocketPRL-Android"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun latest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body.string()
                val obj = json.parseToJsonElement(body).jsonObject
                val tag = obj["tag_name"]?.jsonPrimitive?.content ?: return@use null
                val url = obj["html_url"]?.jsonPrimitive?.content ?: "$REPO_URL/releases/tag/$tag"
                ReleaseInfo(tagName = tag, version = tag.removePrefix("v").removePrefix("V"), htmlUrl = url)
            }
        }.getOrNull()
    }

    /**
     * Compares dotted numeric versions, tolerating a leading "v" and differing
     * segment counts ("1.1" == "1.1.0"). Returns > 0 when [a] is newer than [b].
     */
    fun compare(a: String, b: String): Int {
        val x = a.trim().removePrefix("v").removePrefix("V").split('.')
        val y = b.trim().removePrefix("v").removePrefix("V").split('.')
        val n = maxOf(x.size, y.size)
        for (i in 0 until n) {
            val xv = x.getOrNull(i)?.toIntOrNull() ?: 0
            val yv = y.getOrNull(i)?.toIntOrNull() ?: 0
            if (xv != yv) return xv - yv
        }
        return 0
    }
}
