package dev.pocketprl.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketprl.R
import dev.pocketprl.core.chain.Amount
import dev.pocketprl.data.update.AppUpdater
import dev.pocketprl.ui.components.BannerKind
import dev.pocketprl.ui.components.HeroIcon
import dev.pocketprl.ui.components.InfoBanner
import dev.pocketprl.ui.components.MonoText
import dev.pocketprl.ui.components.PrimaryButton
import dev.pocketprl.ui.components.ScreenScaffold
import dev.pocketprl.ui.components.SecondaryButton
import dev.pocketprl.ui.components.SlideToConfirm
import dev.pocketprl.ui.rememberLeaveAppMarker
import dev.pocketprl.ui.theme.AppIcons
import dev.pocketprl.ui.vm.appContainer

/**
 * Full-screen self-update, styled like the "what's new" screen: the update
 * arrows as the hero, the state of the download below, and a slide-to-confirm
 * once the package is downloaded and verified.
 */
@Composable
fun UpdateScreen(onBack: () -> Unit) {
    val container = appContainer()
    val updater = container.updater
    val state by updater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val leaving = rememberLeaveAppMarker()
    var installError by remember { mutableStateOf<String?>(null) }

    ScreenScaffold(title = stringResource(R.string.update_screen_title), onBack = onBack) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            HeroIcon(AppIcons.Update)
            Text(
                stringResource(R.string.update_hero_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
            )

            when (val st = state) {
                is AppUpdater.State.Downloading -> {
                    MonoText(st.fileName, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { if (st.total > 0) (st.bytes.toFloat() / st.total).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (st.total > 0) stringResource(R.string.update_progress_kb, Amount.group(st.bytes / 1024), Amount.group(st.total / 1024))
                        else stringResource(R.string.update_downloaded_kb, Amount.group(st.bytes / 1024)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                AppUpdater.State.Verifying -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.update_verifying_body))
                }

                is AppUpdater.State.Ready -> {
                    MonoText(st.file.name, style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(R.string.update_downloaded_kb, Amount.group(st.file.length() / 1024)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (st.expected != null) stringResource(R.string.update_sha_verified) else stringResource(R.string.update_sha_unpublished),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = if (st.expected != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.update_signature_verified),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    installError?.let { InfoBanner(it, BannerKind.ERROR) }
                }

                is AppUpdater.State.Failed -> InfoBanner(failureMessage(st.reason), BannerKind.ERROR)

                AppUpdater.State.Idle -> Text(
                    stringResource(R.string.update_install_not_ready),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.weight(1f))

            when (val st = state) {
                is AppUpdater.State.Downloading -> SecondaryButton(stringResource(R.string.action_cancel), onClick = { updater.reset(); onBack() })
                is AppUpdater.State.Ready -> SlideToConfirm(
                    onComplete = {
                        installError = null
                        // Stage the "what's new" popup before handing off to the installer, so
                        // the next launch on the new version shows it after unlocking.
                        container.settings.stageWhatsNew(st.release.version, st.release.body)
                        when (updater.install(st.file)) {
                            AppUpdater.Install.LAUNCHED -> leaving()
                            AppUpdater.Install.NEED_PERMISSION -> {
                                installError = context.getString(R.string.update_install_permission)
                                leaving()
                                updater.openInstallPermissionSettings()
                            }
                            AppUpdater.Install.FAILED -> installError = context.getString(R.string.update_install_failed)
                        }
                    },
                    label = stringResource(R.string.update_install),
                    icon = AppIcons.Update,
                    slideHint = stringResource(R.string.update_install_slide_hint),
                    notReady = stringResource(R.string.update_install_not_ready),
                )
                is AppUpdater.State.Failed -> PrimaryButton(stringResource(R.string.action_close), onClick = { updater.reset(); onBack() })
                AppUpdater.State.Verifying -> Unit
                AppUpdater.State.Idle -> PrimaryButton(stringResource(R.string.action_close), onClick = onBack)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Localized explanation for a failed download/verification. */
@Composable
fun failureMessage(reason: AppUpdater.Failure): String = stringResource(
    when (reason) {
        AppUpdater.Failure.CHECKSUM -> R.string.update_err_checksum
        AppUpdater.Failure.SIGNATURE -> R.string.update_err_signature
        AppUpdater.Failure.NETWORK -> R.string.update_err_network
        AppUpdater.Failure.NO_APK -> R.string.update_err_no_apk
        AppUpdater.Failure.TOO_LARGE -> R.string.update_err_too_large
        AppUpdater.Failure.INCOMPLETE -> R.string.update_err_incomplete
        AppUpdater.Failure.GENERIC -> R.string.update_err_generic
    },
)
