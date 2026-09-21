package dev.pocketprl.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketprl.BuildConfig
import dev.pocketprl.R
import dev.pocketprl.data.BuildInfo
import dev.pocketprl.data.SchemaCompat
import dev.pocketprl.data.VersionLane
import dev.pocketprl.data.update.AppUpdater
import dev.pocketprl.data.update.ReleaseInfo
import dev.pocketprl.data.update.UpdateChecker
import dev.pocketprl.data.update.assetFor
import dev.pocketprl.ui.components.BannerKind
import dev.pocketprl.ui.components.EmptyState
import dev.pocketprl.ui.components.HeroIcon
import dev.pocketprl.ui.components.InfoBanner
import dev.pocketprl.ui.components.LoadingBlock
import dev.pocketprl.ui.components.MarkdownText
import dev.pocketprl.ui.components.MonoText
import dev.pocketprl.ui.components.PrimaryButton
import dev.pocketprl.ui.components.ScreenScaffold
import dev.pocketprl.ui.components.SecondaryButton
import dev.pocketprl.ui.components.SectionCard
import dev.pocketprl.ui.components.SlideToConfirm
import dev.pocketprl.ui.rememberLeaveAppMarker
import dev.pocketprl.ui.theme.AppIcons
import dev.pocketprl.ui.vm.appContainer
import kotlinx.coroutines.launch

/**
 * Full-screen version history and in-place downgrade. It lists every published
 * release, shows the selected release's notes, and reuses the updater's verified
 * download + slide-to-confirm install. The hero is a down arrow beside an up
 * arrow: this is the mirror image of the update screen.
 *
 * An in-place downgrade needs the target's APK to carry a version code above the
 * installed one. The reissued downgrade assets do; a release that only has its
 * original asset reports [AppUpdater.State.Ready.installableInPlace] = false and
 * the screen explains that instead of handing it to an installer that will
 * refuse it.
 */
@Composable
fun VersionHistoryScreen(onBack: () -> Unit, onOpenReset: (String) -> Unit) {
    val container = appContainer()
    val updater = container.updater
    val state by updater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val leaving = rememberLeaveAppMarker()
    val scope = rememberCoroutineScope()

    var releases by remember { mutableStateOf<List<ReleaseInfo>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ReleaseInfo?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        failed = false
        releases = null
        val list = runCatching { UpdateChecker.releases() }.getOrDefault(emptyList())
            .sortedWith { a, b -> UpdateChecker.compare(b.version, a.version) }
        releases = list
        failed = list.isEmpty()
    }

    LaunchedEffect(Unit) { load() }

    val current = BuildConfig.VERSION_NAME
    val installing = state !is AppUpdater.State.Idle

    when {
        // Download / verify / install, once a version has been picked.
        installing -> {
            val target = selected
            ScreenScaffold(
                title = stringResource(R.string.downgrade_install_title, target?.version ?: current),
                onBack = { updater.reset(); installError = null },
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Spacer(Modifier.height(8.dp))
                    HeroIcon(AppIcons.Downgrade)
                    Text(
                        stringResource(R.string.downgrade_hero_title),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground,
                    )

                    DownloadStatus(state, installError)

                    val ready = state as? AppUpdater.State.Ready
                    if (ready != null && !ready.installableInPlace) {
                        InfoBanner(stringResource(R.string.downgrade_not_installable), BannerKind.WARNING)
                    }

                    Spacer(Modifier.weight(1f))

                    if (ready != null && !ready.installableInPlace) {
                        // Android cannot install this target over the running build in
                        // place; the reset flow (uninstall, reinstall, restore phrase) is
                        // the only way, and it works for every version.
                        InfoBanner(stringResource(R.string.downgrade_switch_body), BannerKind.WARNING)
                        PrimaryButton(stringResource(R.string.downgrade_switch_button), onClick = { onOpenReset(target?.version ?: current) })
                        SecondaryButton(
                            stringResource(R.string.downgrade_back_to_list),
                            onClick = { updater.reset(); installError = null },
                        )
                    } else {
                        DownloadControls(
                            state = state,
                            icon = AppIcons.Downgrade,
                            installLabel = stringResource(R.string.downgrade_install_slide, target?.version ?: current),
                            slideHint = stringResource(R.string.downgrade_install_slide_hint),
                            notReady = stringResource(R.string.update_install_not_ready),
                            onInstall = {
                                installError = null
                                (state as? AppUpdater.State.Ready)?.let { st ->
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
                            onCancel = { updater.reset(); installError = null },
                            onClose = { updater.reset(); installError = null },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // One release's details and notes, before committing to the install.
        selected != null -> {
            val release = selected!!
            val isCurrent = UpdateChecker.compare(release.version, current) == 0
            val isDowngrade = UpdateChecker.compare(release.version, current) < 0
            // Decided up front, on the release page: if nothing of this version can
            // install over the running build, say so and gray the control instead of
            // letting the user download and then fail.
            val blocked = !isCurrent && release.assetFor(BuildInfo.code, BuildConfig.VERSION_NAME, BuildInfo.lane) == null
            val blockedReason = when (BuildInfo.lane) {
                VersionLane.BACK -> stringResource(R.string.switch_blocked_back)
                else -> stringResource(R.string.switch_blocked)
            }
            ScreenScaffold(title = stringResource(R.string.downgrade_detail_title, release.version), onBack = { selected = null }) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SectionCard(padding = 12.dp) {
                        ReleaseMeta(release, isCurrent)
                    }

                    if (!SchemaCompat.canReadCurrentData(release.version)) {
                        InfoBanner(stringResource(R.string.downgrade_incompatible_data), BannerKind.WARNING)
                    } else {
                        InfoBanner(stringResource(R.string.downgrade_data_ok), BannerKind.SUCCESS)
                    }

                    if (!SchemaCompat.canSelfUpdate(release.version)) {
                        // 1.0.0: no update checker at all.
                        InfoBanner(stringResource(R.string.downgrade_no_updater_warning), BannerKind.WARNING)
                    } else if (!SchemaCompat.canSelfInstall(release.version)) {
                        // 1.1.0 … 2.1.x: can check for updates, but cannot install them.
                        InfoBanner(stringResource(R.string.downgrade_manual_update_warning), BannerKind.WARNING)
                    }

                    if (!SchemaCompat.hasSwitcher(release.version)) {
                        // Older than 2.4.0: no build awareness and no in-app switching.
                        InfoBanner(stringResource(R.string.downgrade_no_switcher_warning), BannerKind.WARNING)
                    }

                    if (release.apkUrl == null) {
                        InfoBanner(stringResource(R.string.downgrade_no_apk), BannerKind.ERROR)
                    }

                    Text(
                        stringResource(R.string.downgrade_notes_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    SectionCard {
                        val notes = release.body
                        if (notes.isNullOrBlank()) {
                            Text(stringResource(R.string.downgrade_notes_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        } else {
                            MarkdownText(notes)
                        }
                    }

                    Spacer(Modifier.height(2.dp))
                    if (blocked) {
                        // Red reason, gray slider: this version cannot install over the
                        // running build at all.
                        InfoBanner(blockedReason, BannerKind.ERROR)
                        SlideToConfirm(
                            onComplete = {},
                            label = stringResource(R.string.downgrade_switch_button),
                            icon = AppIcons.Downgrade,
                            enabled = false,
                            danger = true,
                            slideHint = stringResource(R.string.downgrade_slide_hint),
                            notReady = stringResource(R.string.switch_cant_here),
                        )
                    } else if (isDowngrade) {
                        // Going off the regular line is consequential: warn before, and
                        // arm a danger slide instead of a plain button.
                        InfoBanner(stringResource(R.string.downgrade_warning), BannerKind.ERROR)
                        SlideToConfirm(
                            onComplete = {
                                installError = null
                                updater.download(release)
                            },
                            label = stringResource(R.string.downgrade_slide, release.version),
                            icon = AppIcons.Downgrade,
                            enabled = release.apkUrl != null,
                            danger = true,
                            slideHint = stringResource(R.string.downgrade_slide_hint),
                            notReady = stringResource(R.string.update_install_not_ready),
                        )
                    } else {
                        PrimaryButton(
                            text = stringResource(R.string.downgrade_install_version, release.version),
                            enabled = release.apkUrl != null && !isCurrent,
                            onClick = {
                                installError = null
                                updater.download(release)
                            },
                        )
                    }
                    SecondaryButton(stringResource(R.string.downgrade_back_to_list), onClick = { selected = null })
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // The list of every release.
        else -> {
            ScreenScaffold(title = stringResource(R.string.downgrade_title), onBack = onBack) {
                when {
                    releases == null -> LoadingBlock(stringResource(R.string.downgrade_loading), modifier = Modifier.fillMaxWidth())
                    failed -> EmptyState(
                        AppIcons.Downgrade,
                        stringResource(R.string.downgrade_failed),
                        modifier = Modifier.fillMaxWidth(),
                        action = { SecondaryButton(stringResource(R.string.action_retry), onClick = { scope.launch { load() } }) },
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item { CurrentBuildCard(current) }
                        item {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                                HeroIcon(AppIcons.Downgrade)
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    stringResource(R.string.downgrade_hero_title),
                                    style = MaterialTheme.typography.headlineSmall,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.downgrade_hero_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(releases!!, key = { it.tagName }) { release ->
                            ReleaseRow(
                                release = release,
                                isCurrent = UpdateChecker.compare(release.version, current) == 0,
                                onClick = { selected = release },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseRow(release: ReleaseInfo, isCurrent: Boolean, onClick: () -> Unit) {
    SectionCard(onClick = if (isCurrent) null else onClick) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "v${release.version}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isCurrent) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.downgrade_current),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                release.publishedAt?.let {
                    Text(
                        stringResource(R.string.downgrade_published, it.take(10)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!isCurrent) {
                Text(
                    stringResource(R.string.downgrade_view),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * "You're on …": which lane this build is and what it can install. Once off the
 * primary lane it is shown as a warning, because normal updates stop installing.
 */
@Composable
private fun CurrentBuildCard(version: String) {
    val (titleRes, bodyRes) = when (BuildInfo.lane) {
        VersionLane.PRIMARY -> R.string.build_lane_primary to R.string.build_lane_primary_body
        VersionLane.ROLLBACK -> R.string.build_lane_rollback to R.string.build_lane_rollback_body
        VersionLane.BACK -> R.string.build_lane_back to R.string.build_lane_back_body
    }
    val heading = stringResource(R.string.build_lane_you_are_on) + ": " + stringResource(titleRes) + " v$version"
    if (BuildInfo.isPrimary) {
        SectionCard(padding = 14.dp) {
            Text(stringResource(R.string.build_lane_you_are_on), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(8.dp))
                MonoText("v$version", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(bodyRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        // Yellow, not red: being off the regular line is a state, not an error.
        // Red is reserved for the actual downgrade warnings and sliders.
        InfoBanner(stringResource(bodyRes), BannerKind.WARNING, title = heading)
    }
}

@Composable
private fun ReleaseMeta(release: ReleaseInfo, isCurrent: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoText("v${release.version}", style = MaterialTheme.typography.titleMedium)
            if (isCurrent) {
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.downgrade_current),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        release.publishedAt?.let {
            Text(
                stringResource(R.string.downgrade_published, it.take(10)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
