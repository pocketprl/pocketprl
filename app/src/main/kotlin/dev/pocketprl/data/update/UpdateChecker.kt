package dev.pocketprl.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
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
    /** File name of the attached APK, e.g. "PocketPRL-2.2.0.apk"; null when the release has none. */
    val apkName: String? = null,
    /** Direct download URL of the APK. */
    val apkUrl: String? = null,
    /** APK size in bytes as reported by GitHub (0 when unknown). */
    val apkSize: Long = 0,
    /** Lower-case SHA-256 of the APK from GitHub's asset digest, when present. */
    val sha256: String? = null,
    /** Release notes (the GitHub release body), shown as "what's new" after an update. */
    val body: String? = null,
    /** ISO-8601 publish timestamp, e.g. "2026-09-19T12:00:00Z". */
    val publishedAt: String? = null,
    /** True for a GitHub pre-release; shown differently in the version history. */
    val prerelease: Boolean = false,
)

/**
 * Reads the newest release from the public PocketPRL repo. Anonymous, read-only,
 * no address or wallet data is ever sent; this only ever sees the GitHub API.
 */
object UpdateChecker {
    const val REPO_URL = "https://github.com/pocketprl/pocketprl"

    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/pocketprl/pocketprl/releases/latest"
    private const val RELEASES_URL = "https://api.github.com/repos/pocketprl/pocketprl/releases"
    private const val USER_AGENT = "PocketPRL-Android"

    /** Releases per page (GitHub's max) and the page cap for [releases]. */
    private const val PAGE_SIZE = 100
    private const val MAX_PAGES = 5

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun latest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val req = request(LATEST_RELEASE_URL)
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                parseRelease(json.parseToJsonElement(resp.body.string()).jsonObject)
            }
        }.getOrNull()
    }

    /**
     * Every published release, newest first, for the version-history / downgrade
     * screen. Read-only and anonymous; it only ever sees the public GitHub API.
     * Paginates until a short page or [MAX_PAGES], so a large back catalogue does
     * not turn one screen-open into unbounded traffic.
     */
    suspend fun releases(): List<ReleaseInfo> = withContext(Dispatchers.IO) {
        val out = ArrayList<ReleaseInfo>()
        var page = 1
        while (page <= MAX_PAGES) {
            val body = runCatching {
                client.newCall(request("$RELEASES_URL?per_page=$PAGE_SIZE&page=$page")).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body.string()
                }
            }.getOrNull() ?: break
            val parsed = parseReleasesJson(body)
            out += parsed.filterNot { it.prerelease }
            // A short page means the last page; a page full of pre-releases still counts as full.
            if (parsed.size < PAGE_SIZE) break
            page++
        }
        out
    }

    /** Parses a GitHub release-list JSON body; exposed so parsing is unit-testable without network. */
    fun parseReleasesJson(body: String): List<ReleaseInfo> =
        runCatching { json.parseToJsonElement(body).jsonArray.mapNotNull { parseRelease(it.jsonObject) } }.getOrDefault(emptyList())

    private fun request(url: String) = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", USER_AGENT)
        .get()
        .build()

    private fun parseRelease(obj: kotlinx.serialization.json.JsonObject): ReleaseInfo? {
        val tag = obj["tag_name"]?.jsonPrimitive?.content ?: return null
        val url = obj["html_url"]?.jsonPrimitive?.content ?: "$REPO_URL/releases/tag/$tag"
        // Pick the release's APK asset, preferring one named after the wallet.
        var apkName: String? = null
        var apkUrl: String? = null
        var apkSize = 0L
        var sha256: String? = null
        val assets = obj["assets"]?.jsonArray
        if (assets != null) {
            val apks = assets.mapNotNull { it.jsonObject }.filter {
                it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", ignoreCase = true) == true
            }
            val asset = apks.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.contains("PocketPRL", ignoreCase = true) == true } ?: apks.firstOrNull()
            if (asset != null) {
                apkName = asset["name"]?.jsonPrimitive?.contentOrNull
                apkUrl = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
                apkSize = asset["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                sha256 = asset["digest"]?.jsonPrimitive?.contentOrNull
                    ?.removePrefix("sha256:")
                    ?.lowercase()
                    ?.takeIf { it.length == 64 }
            }
        }
        val notes = obj["body"]?.jsonPrimitive?.contentOrNull
        val prerelease = obj["prerelease"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        return ReleaseInfo(
            tagName = tag,
            version = tag.removePrefix("v").removePrefix("V"),
            htmlUrl = url,
            apkName = apkName,
            apkUrl = apkUrl,
            apkSize = apkSize,
            sha256 = sha256,
            body = notes?.ifBlank { null },
            publishedAt = obj["published_at"]?.jsonPrimitive?.contentOrNull,
            prerelease = prerelease,
        )
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
