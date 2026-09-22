package dev.pocketprl.data.update

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.pocketprl.core.crypto.toHex
import dev.pocketprl.data.VersionCodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads the published APK, checks it against the SHA-256 GitHub reports for
 * the asset and against the installed app's signing certificate, then hands it
 * to the system package installer. State is exposed as a flow so the UI can show
 * live progress and survives leaving the screen (the work runs on the app scope).
 *
 * Installing needs the user to allow "install unknown apps" for PocketPRL
 * (REQUEST_INSTALL_PACKAGES + the system toggle); [install] reports when that
 * grant is missing.
 */
class AppUpdater(private val context: Context, private val scope: CoroutineScope) {
    sealed interface State {
        object Idle : State
        /** [total] is 0 when GitHub did not report a size. */
        data class Downloading(val fileName: String, val bytes: Long, val total: Long, val bytesPerSecond: Long = 0) : State
        object Verifying : State
        /**
         * [expected] is null when the release published no checksum. [versionCode]
         * is read from the downloaded APK, and [installableInPlace] is true when
         * Android will accept it over the installed build (an equal or higher
         * version code). Reissued downgrade assets carry a code above the current
         * one, so a genuine downgrade reports true; the original pre-reissue
         * assets report false and the UI explains why.
         */
        data class Ready(
            val file: File,
            val actualSha256: String,
            val expected: String?,
            val release: ReleaseInfo,
            val versionCode: Long? = null,
            val installableInPlace: Boolean = true,
        ) : State
        data class Failed(val reason: Failure) : State
    }

    /** Why the download could not be prepared, so the UI can say something specific. */
    enum class Failure { CHECKSUM, SIGNATURE, NETWORK, NO_APK, TOO_LARGE, INCOMPLETE, GENERIC }

    enum class Install { LAUNCHED, NEED_PERMISSION, FAILED }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // An APK over a slow link can take a while; bound it by size, not by wall clock.
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    /** Cancels any download and clears the state (e.g. the dialog was dismissed). */
    fun reset() {
        job?.cancel()
        _state.value = State.Idle
    }

    /** Downloads the build of [info] appropriate for the current lane. */
    fun download(info: ReleaseInfo) = start(info, preferPrimary = false)

    /**
     * Downloads the plain primary build of [info], for the reset flow: after a
     * reinstall the user must land on the regular line, not an alternate build.
     */
    fun downloadPrimary(info: ReleaseInfo) = start(info, preferPrimary = true)

    private fun start(info: ReleaseInfo, preferPrimary: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            // Never let a previous, possibly stale, APK be installed.
            dir.listFiles()?.forEach { runCatching { it.delete() } }

            val installed = installedVersionCode()
            val installedName = installedVersionName()
            val assets = info.apkAssets.ifEmpty {
                if (info.apkUrl != null) listOf(ApkAsset(info.apkName, info.apkUrl, info.apkSize, info.sha256)) else emptyList()
            }
            val chosen = if (preferPrimary) {
                assets.firstOrNull { it.versionCode == null } ?: assets.firstOrNull()
            } else {
                info.assetFor(installed, installedName, VersionCodes.laneOf(installed))
                    ?: assets.firstOrNull { it.versionCode == null }
                    ?: assets.firstOrNull()
            }
            val file = File(dir, chosen?.name ?: DEFAULT_APK_NAME)
            try {
                val url = chosen?.url ?: throw DownloadException(Failure.NO_APK)
                _state.value = State.Downloading(file.name, 0, chosen.size)
                val digest = MessageDigest.getInstance("SHA-256")
                val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw DownloadException(Failure.NETWORK)
                    val body = response.body
                    val total = if (chosen.size > 0) chosen.size else body.contentLength()
                    if (total > MAX_BYTES) throw DownloadException(Failure.TOO_LARGE)
                    var read = 0L
                    val buffer = ByteArray(64 * 1024)
                    // Smoothed download rate, sampled a few times a second so the
                    // shown speed does not flicker with every 64 KB chunk.
                    var rate = 0.0
                    var sampleAt = System.currentTimeMillis()
                    var sampleBytes = 0L
                    body.byteStream().use { input ->
                        file.outputStream().use { out ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                read += n
                                if (read > MAX_BYTES) throw DownloadException(Failure.TOO_LARGE)
                                val now = System.currentTimeMillis()
                                val dt = now - sampleAt
                                if (dt >= 250) {
                                    val instant = (read - sampleBytes) * 1000.0 / dt
                                    rate = if (rate == 0.0) instant else rate * 0.6 + instant * 0.4
                                    sampleAt = now
                                    sampleBytes = read
                                }
                                _state.value = State.Downloading(file.name, read, total, rate.toLong())
                            }
                            out.flush()
                        }
                    }
                    if (total > 0 && read != total) throw DownloadException(Failure.INCOMPLETE)
                }
                _state.value = State.Verifying
                val actual = digest.digest().toHex()
                if (chosen.sha256 != null && !actual.equals(chosen.sha256, ignoreCase = true)) {
                    throw DownloadException(Failure.CHECKSUM)
                }
                if (!signerMatches(file)) throw DownloadException(Failure.SIGNATURE)
                val code = archiveVersionCode(file)
                _state.value = State.Ready(file, actual, chosen.sha256, info, code, code == null || code > installed)
            } catch (e: CancellationException) {
                file.delete()
                _state.value = State.Idle
                throw e
            } catch (e: DownloadException) {
                file.delete()
                _state.value = State.Failed(e.reason)
            } catch (e: Exception) {
                file.delete()
                _state.value = State.Failed(Failure.GENERIC)
            }
        }
    }

    /** Hands [file] to the system installer. [Install.NEED_PERMISSION] means "install unknown apps" is off. */
    fun install(file: File): Install {
        if (!canInstall()) return Install.NEED_PERMISSION
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Install.LAUNCHED
        } catch (_: Exception) {
            Install.FAILED
        }
    }

    /** True when the OS will let this app open the package installer (Android 8+ needs an explicit grant). */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the per-app "install unknown apps" system page. */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Whether the downloaded APK is signed by the same certificate as the
     * installed app. Android enforces this on install anyway; checking here means
     * a tampered download is caught (and deleted) before the installer is opened.
     */
    fun signerMatches(file: File): Boolean = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val current = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: return@runCatching false
        val installed = current.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() ?: return@runCatching false
        val downloaded = archive.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() ?: return@runCatching false
        val md = MessageDigest.getInstance("SHA-256")
        md.digest(installed).contentEquals(md.digest(downloaded))
    }.getOrDefault(false)

    /**
     * Copies a verified APK into the public Downloads folder so it survives
     * uninstalling this app. Returns the saved display name, or null on failure.
     */
    suspend fun exportToDownloads(file: File, name: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, APK_MIME)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                name
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
                file.copyTo(File(dir, name), overwrite = true)
                name
            }
        }.getOrNull()
    }

    /** Version code of the installed app; 0 when it cannot be read. */
    fun installedVersionCode(): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }.getOrDefault(0L)

    /** Version name of the installed app; empty when it cannot be read. */
    fun installedVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    /** Version code stored in an APK on disk, or null when it cannot be read. */
    fun archiveVersionCode(file: File): Long? = runCatching {
        context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.longVersionCode
    }.getOrNull()

    private class DownloadException(val reason: Failure) : IOException(reason.name)

    companion object {
        /** Cache subdirectory; also the FileProvider path name (see file_paths.xml). */
        const val DIR = "updates"
        private const val DEFAULT_APK_NAME = "PocketPRL-update.apk"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val MAX_BYTES = 200L * 1024 * 1024
        private const val USER_AGENT = "PocketPRL-Android"
    }
}
