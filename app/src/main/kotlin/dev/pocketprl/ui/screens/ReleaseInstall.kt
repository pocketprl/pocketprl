package dev.pocketprl.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pocketprl.R
import dev.pocketprl.core.chain.Amount
import dev.pocketprl.data.update.AppUpdater
import dev.pocketprl.ui.components.BannerKind
import dev.pocketprl.ui.components.InfoBanner
import dev.pocketprl.ui.components.MonoText
import dev.pocketprl.ui.components.PrimaryButton
import dev.pocketprl.ui.components.SecondaryButton
import dev.pocketprl.ui.components.SlideToConfirm

/**
 * The download/verify/ready status block shared by the self-updater and the
 * version-history downgrade flow, so both look and behave identically.
 */
@Composable
fun DownloadStatus(state: AppUpdater.State, installError: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (val st = state) {
            is AppUpdater.State.Downloading -> {
                MonoText(st.fileName, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(
                    progress = { if (st.total > 0) (st.bytes.toFloat() / st.total).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth(),
                    drawStopIndicator = {},
                )
                val sizeText = if (st.total > 0) {
                    stringResource(R.string.update_progress_kb, Amount.group(st.bytes / 1024), Amount.group(st.total / 1024))
                } else {
                    stringResource(R.string.update_downloaded_kb, Amount.group(st.bytes / 1024))
                }
                val speedText = if (st.bytesPerSecond > 0) {
                    "  ·  " + stringResource(R.string.update_speed_kbps, Amount.group(st.bytesPerSecond / 1024))
                } else {
                    ""
                }
                Text(
                    sizeText + speedText,
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
    }
}

/**
 * The bottom action for the shared download flow: cancel while downloading, the
 * slide-to-confirm once verified, close on failure or idle.
 */
@Composable
fun DownloadControls(
    state: AppUpdater.State,
    icon: ImageVector,
    installLabel: String,
    slideHint: String,
    notReady: String,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    resetKey: Any? = Unit,
) {
    when (state) {
        is AppUpdater.State.Downloading -> SecondaryButton(stringResource(R.string.action_cancel), onClick = onCancel, modifier = modifier)
        is AppUpdater.State.Ready -> SlideToConfirm(
            onComplete = onInstall,
            label = installLabel,
            icon = icon,
            slideHint = slideHint,
            notReady = notReady,
            modifier = modifier,
            resetKey = resetKey,
        )
        is AppUpdater.State.Failed -> PrimaryButton(stringResource(R.string.action_close), onClick = onClose, modifier = modifier)
        AppUpdater.State.Verifying -> Unit
        AppUpdater.State.Idle -> PrimaryButton(stringResource(R.string.action_close), onClick = onClose, modifier = modifier)
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
