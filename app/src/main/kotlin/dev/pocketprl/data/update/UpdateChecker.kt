package dev.pocketprl.data.update

import dev.pocketprl.data.VersionCodes
import dev.pocketprl.data.VersionLane
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
    /** Every APK attached to the release; a release may carry more than one build. */
    val apkAssets: List<ApkAsset> = emptyList(),
)

/**
 * One APK asset of a release. A build that must be installed over a newer one
 * carries its version code as a numeric suffix in the file name, e.g.
 * `PocketPRL-2.4.0-return-100026.apk`; a plain asset has a null [versionCode].
 */
data class ApkAsset(
    val name: String?,
    val url: String?,
    val size: Long,
    val sha256: String?,
    val versionCode: Long? = null,
) {
    /** The lane a build belongs to, derived from its encoded code (null when unnamed). */
    val lane: VersionLane? get() = versionCode?.let { VersionCodes.laneOf(it) }
}

/**
 * The asset that will install over [installedCode]: the lowest code that is still
 * higher. Null means no asset of this release can be installed in place, so the
 * caller must offer the reset flow.
 */
fun ReleaseInfo.bestAssetFor(installedCode: Long): ApkAsset? =
    apkAssets.filter { it.versionCode != null && it.versionCode > installedCode }.minByOrNull { it.versionCode!! }

/**
 * The build of this release to install from the current state, or null when nothing
 * of this release can install in place and the caller must offer the reset.
 *
 * On the regular line a newer release installs its primary build; an older one
 * installs its rollback. Off the regular line only a higher-coded build installs:
 * the back lane to move forward, the rollback lane to go further down.
 */
fun ReleaseInfo.assetFor(installedCode: Long, installedVersion: String, lane: VersionLane): ApkAsset? {
    val primary = apkAssets.firstOrNull { it.versionCode == null }
    val higher = bestAssetFor(installedCode)
    return when (lane) {
        VersionLane.PRIMARY -> if (UpdateChecker.compare(version, installedVersion) >= 0) (primary ?: higher) else higher
        VersionLane.ROLLBACK, VersionLane.BACK -> higher
    }
}

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
        // Every APK asset, each remembering the version code encoded in its name.
        val assets = obj["assets"]?.jsonArray
        val apkAssets = assets?.mapNotNull { it.jsonObject }
            ?.filter { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", ignoreCase = true) == true }
            ?.map { a ->
                val name = a["name"]?.jsonPrimitive?.contentOrNull
                ApkAsset(
                    name = name,
                    url = a["browser_download_url"]?.jsonPrimitive?.contentOrNull,
                    size = a["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                    sha256 = a["digest"]?.jsonPrimitive?.contentOrNull?.removePrefix("sha256:")?.lowercase()?.takeIf { it.length == 64 },
                    versionCode = parseAssetCode(name),
                )
            } ?: emptyList()

        // The primary asset is the plain one (no code suffix), preferring the
        // wallet-named file, so the update path never accidentally picks a
        // higher-coded return build.
        val primary = apkAssets.firstOrNull { it.versionCode == null && it.name?.contains("PocketPRL", ignoreCase = true) == true }
            ?: apkAssets.firstOrNull { it.name?.contains("PocketPRL", ignoreCase = true) == true }
            ?: apkAssets.firstOrNull()

        val notes = obj["body"]?.jsonPrimitive?.contentOrNull
        val prerelease = obj["prerelease"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        return ReleaseInfo(
            tagName = tag,
            version = tag.removePrefix("v").removePrefix("V"),
            htmlUrl = url,
            apkName = primary?.name,
            apkUrl = primary?.url,
            apkSize = primary?.size ?: 0L,
            sha256 = primary?.sha256,
            body = notes?.ifBlank { null },
            publishedAt = obj["published_at"]?.jsonPrimitive?.contentOrNull,
            prerelease = prerelease,
            apkAssets = apkAssets,
        )
    }

    /** Version code encoded as a numeric suffix in an asset name, e.g. `...-return-100026.apk`. */
    private val ASSET_CODE = Regex("""-(\d{5,})\.apk$""", RegexOption.IGNORE_CASE)

    private fun parseAssetCode(name: String?): Long? = name?.let { ASSET_CODE.find(it)?.groupValues?.get(1)?.toLongOrNull() }

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
