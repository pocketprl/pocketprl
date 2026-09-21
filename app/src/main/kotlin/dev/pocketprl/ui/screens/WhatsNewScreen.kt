package dev.pocketprl.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pocketprl.R
import dev.pocketprl.ui.components.HeroIcon
import dev.pocketprl.ui.components.PrimaryButton
import dev.pocketprl.ui.components.ScreenScaffold
import dev.pocketprl.ui.components.SectionCard
import dev.pocketprl.ui.theme.AppIcons

/**
 * Full-screen "what's new", shown once after the app has been updated to a
 * version the updater staged, and only after the wallet is unlocked. One
 * acknowledgement sends the user to the dashboard.
 */
@Composable
fun WhatsNewScreen(version: String, notes: String, onDone: () -> Unit) {
    // Back means the same as Okay; otherwise the popup would just reappear next unlock.
    BackHandler { onDone() }
    ScreenScaffold(title = stringResource(R.string.whatsnew_title), onBack = null) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            HeroIcon(AppIcons.Update)
            Text(
                stringResource(R.string.whatsnew_heading, version),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                stringResource(R.string.whatsnew_intro),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionCard {
                Text(
                    formatNotes(notes).ifEmpty { stringResource(R.string.whatsnew_empty) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(4.dp))
            PrimaryButton(stringResource(R.string.whatsnew_done), onClick = onDone)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Turns the release body's light markdown into readable plain text for the block. */
private fun formatNotes(raw: String): String = raw.lines().mapNotNull { line ->
    val t = line.trim()
    when {
        t.isEmpty() -> null
        t.startsWith("#") -> t.trimStart('#').trim()
        t.startsWith("- ") || t.startsWith("* ") -> "• " + t.drop(2)
        t == "-" || t == "*" -> null
        else -> t
    }
}.joinToString("\n").trim()
