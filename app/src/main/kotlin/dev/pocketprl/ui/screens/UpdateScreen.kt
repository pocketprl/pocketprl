package dev.pocketprl.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import dev.pocketprl.data.update.AppUpdater
import dev.pocketprl.ui.components.HeroIcon
import dev.pocketprl.ui.components.ScreenScaffold
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

            DownloadStatus(state, installError)

            Spacer(Modifier.weight(1f))

            DownloadControls(
                state = state,
                icon = AppIcons.Update,
                installLabel = stringResource(R.string.update_install),
                slideHint = stringResource(R.string.update_install_slide_hint),
                notReady = stringResource(R.string.update_install_not_ready),
                onInstall = {
                    installError = null
                    (state as? AppUpdater.State.Ready)?.let { st ->
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
                    }
                },
                onCancel = { updater.reset(); onBack() },
                onClose = { updater.reset(); onBack() },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
