package dev.pocketprl.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.pocketprl.ui.vm.appContainer

/**
 * Runtime-permission plumbing. androidx.fragment must be a modern version (see
 * libs.versions.toml) for the Activity Result API to work under androidx.biometric;
 * [rememberLeaveAppMarker] keeps screens the app opens itself from tripping the auto-lock.
 */
object Permissions {
    fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** POST_NOTIFICATIONS only exists from Android 13; before that notifications are granted at install. */
    fun notificationsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, Manifest.permission.POST_NOTIFICATIONS)

    /**
     * True when the system will no longer show a prompt for [permission], so the
     * only way forward is the app's page in system settings.
     */
    fun permanentlyDenied(activity: Activity?, permission: String): Boolean =
        activity != null && !granted(activity, permission) && !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)

    fun openAppSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** Marks the next trip out of the app as self-started so auto-lock does not fire on it. */
@Composable
fun rememberLeaveAppMarker(): () -> Unit {
    val container = appContainer()
    return remember(container) { { container.expectReturnFromOwnActivity() } }
}

/** Result of a permission request the user just answered. */
enum class PermissionOutcome { GRANTED, DENIED, DENIED_PERMANENTLY }

/**
 * Asks for [permission], reporting whether the user can still be prompted again.
 * Returns a function that starts the request; if the permission is already held
 * the callback fires immediately with [PermissionOutcome.GRANTED].
 */
@Composable
fun rememberPermissionRequest(permission: String, onResult: (PermissionOutcome) -> Unit): () -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val leaving = rememberLeaveAppMarker()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onResult(
            when {
                granted -> PermissionOutcome.GRANTED
                Permissions.permanentlyDenied(activity, permission) -> PermissionOutcome.DENIED_PERMANENTLY
                else -> PermissionOutcome.DENIED
            },
        )
    }
    return {
        if (Permissions.granted(context, permission)) {
            onResult(PermissionOutcome.GRANTED)
        } else {
            leaving()
            launcher.launch(permission)
        }
    }
}

/** QR scanning with the camera permission requested up front rather than by the scanner activity. */
@Composable
fun rememberQrScanner(
    prompt: String,
    onScanned: (String) -> Unit,
    onUnavailable: (PermissionOutcome) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val leaving = rememberLeaveAppMarker()
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(onScanned)
    }
    val openScanner: () -> Unit = {
        leaving()
        runCatching {
            scanner.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setBeepEnabled(false)
                    .setOrientationLocked(true)
                    .setPrompt(prompt),
            )
        }.onFailure { onUnavailable(PermissionOutcome.DENIED) }
    }
    val ask = rememberPermissionRequest(Manifest.permission.CAMERA) { outcome ->
        if (outcome == PermissionOutcome.GRANTED) openScanner() else onUnavailable(outcome)
    }
    return {
        if (Permissions.granted(context, Manifest.permission.CAMERA)) openScanner() else ask()
    }
}
