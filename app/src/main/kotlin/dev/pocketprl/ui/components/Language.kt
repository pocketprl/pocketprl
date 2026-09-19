package dev.pocketprl.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.pocketprl.Locales
import dev.pocketprl.R

/** Display name for a language tag, or the localized "System default". */
@Composable
fun languageLabel(tag: String): String =
    if (tag.isBlank()) stringResource(R.string.settings_language_system)
    else Locales.supported.firstOrNull { it.tag == tag }?.label ?: tag.uppercase()

/** Picker for the per-app language. An empty tag follows the system. */
@Composable
fun LanguageDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val tags = listOf("") + Locales.supported.map { it.tag }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                tags.forEach { tag ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onPick(tag) }.heightIn(min = 48.dp).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = tag == current, onClick = { onPick(tag) })
                        Spacer(Modifier.width(8.dp))
                        Text(languageLabel(tag))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
