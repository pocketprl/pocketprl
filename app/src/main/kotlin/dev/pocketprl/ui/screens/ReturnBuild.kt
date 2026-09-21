package dev.pocketprl.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketprl.BuildConfig
import dev.pocketprl.R
import dev.pocketprl.data.BuildInfo
import dev.pocketprl.data.VersionLane
import dev.pocketprl.data.update.AppUpdater
import dev.pocketprl.data.update.UpdateChecker
import dev.pocketprl.ui.PermissionOutcome
import dev.pocketprl.ui.Permissions
import dev.pocketprl.ui.components.BannerKind
import dev.pocketprl.ui.components.HeroIcon
import dev.pocketprl.ui.components.InfoBanner
import dev.pocketprl.ui.components.PrimaryButton
import dev.pocketprl.ui.components.ScreenScaffold
import dev.pocketprl.ui.components.SecondaryButton
import dev.pocketprl.ui.rememberPermissionRequest
import dev.pocketprl.ui.theme.AppIcons
import dev.pocketprl.ui.vm.appContainer
import kotlinx.coroutines.launch

/**
 * First-launch notice shown only when the installed build is a return build: it
 * sits above every downgrade target, so downgrading is off until the regular
 * build of the same version is installed.
 */
@Composable
fun ReturnBuildScreen(onDismiss: () -> Unit) {
    val (titleRes, bodyRes) = when (BuildInfo.lane) {
        VersionLane.ROLLBACK -> R.string.build_lane_rollback to R.string.alternate_notice_rollback
        VersionLane.BACK -> R.string.build_lane_back to R.string.alternate_notice_back
        VersionLane.PRIMARY -> R.string.build_lane_primary to R.string.alternate_notice_back
    }
    ScreenScaffold(title = stringResource(titleRes), onBack = onDismiss) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            HeroIcon(AppIcons.Downgrade)
            Text(
                stringResource(bodyRes),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.action_done), onClick = onDismiss)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Downloads and verifies the regular build of the running version, saves it to
 * Downloads, then points at the system uninstaller. Android won't install a lower
 * package version over this one, so the uninstall is unavoidable; the file in
 * Downloads is how the wallet (recovery phrase restores funds) comes back.
 */
@Composable
fun ResetBuildScreen(version: String, onBack: () -> Unit) {
    val container = appContainer()
    val updater = container.updater
    val state by updater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var release by remember { mutableStateOf<dev.pocketprl.data.update.ReleaseInfo?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var savedName by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var storageOk by remember {
        mutableStateOf(Build.VERSION.SDK_INT > Build.VERSION_CODES.P || Permissions.granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }
    val askStorage = rememberPermissionRequest(Manifest.permission.WRITE_EXTERNAL_STORAGE) { storageOk = it == PermissionOutcome.GRANTED }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !storageOk) askStorage() }

    LaunchedEffect(Unit) {
        val list = runCatching { UpdateChecker.releases() }.getOrDefault(emptyList())
        val r = list.firstOrNull { it.version == version }
        if (r == null) loadFailed = true else {
            release = r
            // Reset must restore a regular (primary) build, not an alternate one.
            updater.downloadPrimary(r)
        }
    }

    ScreenScaffold(title = stringResource(R.string.return_build_reset_title), onBack = onBack) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HeroIcon(AppIcons.Downgrade)
            InfoBanner(stringResource(R.string.return_build_reset_body), BannerKind.WARNING)

            if (loadFailed) {
                InfoBanner(stringResource(R.string.return_build_error), BannerKind.ERROR)
            } else {
                DownloadStatus(state, null)
            }

            val ready = state as? AppUpdater.State.Ready
            if (ready != null) {
                if (savedName == null) {
                    PrimaryButton(
                        stringResource(R.string.return_build_reset_prepare),
                        onClick = {
                            saveError = null
                            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !storageOk) {
                                askStorage()
                            } else {
                                scope.launch {
                                    val n = updater.exportToDownloads(ready.file, "PocketPRL-${BuildConfig.VERSION_NAME}.apk")
                                    if (n == null) saveError = context.getString(R.string.return_build_reset_save_failed) else savedName = n
                                }
                            }
                        },
                    )
                } else {
                    InfoBanner(stringResource(R.string.return_build_reset_saved, savedName!!), BannerKind.SUCCESS)
                    SecondaryButton(
                        stringResource(R.string.return_build_reset_uninstall),
                        danger = true,
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    )
                }
                saveError?.let { InfoBanner(it, BannerKind.ERROR) }
            }

            Spacer(Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.action_back), onClick = onBack)
        }
    }
}
