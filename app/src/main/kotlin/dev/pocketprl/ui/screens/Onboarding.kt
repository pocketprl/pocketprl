package dev.pocketprl.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketprl.core.crypto.Bip39
import dev.pocketprl.ui.components.BannerKind
import dev.pocketprl.ui.components.FieldShape
import dev.pocketprl.ui.components.InfoBanner
import dev.pocketprl.ui.components.rememberHaptics
import dev.pocketprl.ui.components.LoadingBlock
import dev.pocketprl.ui.components.PasswordField
import dev.pocketprl.ui.components.PasswordStrength
import dev.pocketprl.ui.components.PrimaryButton
import dev.pocketprl.ui.components.ScreenScaffold
import dev.pocketprl.ui.components.SecondaryButton
import dev.pocketprl.ui.components.SectionCard
import dev.pocketprl.ui.components.SecureWindow
import dev.pocketprl.ui.components.WordChip
import dev.pocketprl.ui.components.passwordScore
import dev.pocketprl.ui.vm.OnboardingViewModel
import java.security.SecureRandom

private enum class CreateStep { SETUP, SEED, VERIFY, WORKING }

/*
 * Passwords and phrases below use `remember`, not `rememberSaveable`: saved instance
 * state is handed to the system process and must never carry secrets.
 */

@Composable
fun CreateWalletScreen(vm: OnboardingViewModel, onDone: () -> Unit, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val network by vm.network.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableStateOf(CreateStep.SETUP) }
    var name by rememberSaveable { mutableStateOf("My Pearl Wallet") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { if (state.mnemonic == null) vm.generateMnemonic() }
    LaunchedEffect(state.done) { if (state.done) onDone() }

    // `step` survives process death but the phrase and password do not; restart the flow rather
    // than quiz on an empty phrase or create a wallet with no password.
    val lostSecrets = step != CreateStep.SETUP && (state.mnemonic == null || password.isEmpty())
    LaunchedEffect(lostSecrets) { if (lostSecrets) step = CreateStep.SETUP }
    val shown = if (lostSecrets) CreateStep.SETUP else step

    ScreenScaffold(
        title = when (shown) { CreateStep.SETUP -> "Create wallet"; CreateStep.SEED -> "Your recovery phrase"; CreateStep.VERIFY -> "Verify phrase"; CreateStep.WORKING -> "Setting up" },
        subtitle = "Step ${(shown.ordinal + 1).coerceAtMost(3)} of 3 • ${network.displayName}",
        onBack = when (shown) { CreateStep.SETUP -> onBack; CreateStep.SEED -> ({ step = CreateStep.SETUP }); CreateStep.VERIFY -> ({ step = CreateStep.SEED }); CreateStep.WORKING -> null },
    ) {
        StepProgress(shown.ordinal, 3)
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (shown) {
                CreateStep.SETUP -> {
                    Text("Name and protect your wallet", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Wallet name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = FieldShape)
                    PasswordField(password, { password = it }, "Password")
                    if (password.isNotEmpty()) PasswordStrength(password)
                    PasswordField(confirm, { confirm = it }, "Confirm password", imeAction = ImeAction.Done, isError = confirm.isNotEmpty() && confirm != password)
                    InfoBanner("The password encrypts your keys on this device. There is no reset: if you forget it, restore from your recovery phrase.", BannerKind.WARNING)
                    state.error?.let { InfoBanner(it, BannerKind.ERROR) }
                    PrimaryButton(
                        "Continue",
                        onClick = { vm.clearError(); step = CreateStep.SEED },
                        enabled = name.isNotBlank() && password.length >= 8 && password == confirm && passwordScore(password) >= 2,
                    )
                    if (password.isNotEmpty() && passwordScore(password) < 2) Text("Use at least 8 characters with a mix of letters, numbers or symbols.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                CreateStep.SEED -> {
                    SecureWindow()
                    val words = state.mnemonic?.split(' ') ?: emptyList()
                    Text("Write these 12 words down, in order, and keep them offline.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                    SectionCard { WordGrid(words) }
                    InfoBanner("Anyone with these words controls your funds. Screenshots are blocked on this screen; do not store the phrase digitally.", BannerKind.WARNING, title = "Keep it secret")
                    PrimaryButton("I've written it down", onClick = { step = CreateStep.VERIFY })
                }
                CreateStep.VERIFY -> {
                    SecureWindow()
                    val words = state.mnemonic?.split(' ') ?: emptyList()
                    SeedQuiz(words = words, onVerified = { step = CreateStep.WORKING; vm.create(name, password) })
                }
                CreateStep.WORKING -> {
                    LoadingBlock(state.progress ?: "Creating wallet…")
                    state.error?.let { InfoBanner(it, BannerKind.ERROR); SecondaryButton("Back", onClick = { step = CreateStep.SETUP }) }
                }
            }
        }
    }
}

@Composable
private fun StepProgress(current: Int, total: Int) {
    val target = ((current + 1).coerceAtMost(total)) / total.toFloat()
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "step",
    )
    LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
}

@Composable
fun WordGrid(words: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in words.indices step 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WordChip(row, words[row], Modifier.weight(1f))
                if (row + 1 < words.size) WordChip(row + 1, words[row + 1], Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Same quiz the desktop wallet uses: words 3, 7 and 12 with three decoys each. */
@Composable
private fun SeedQuiz(words: List<String>, onVerified: () -> Unit) {
    if (words.size < 3) return
    val positions = remember(words) { if (words.size >= 12) listOf(2, 6, 11) else listOf(0, words.size / 2, words.size - 1) }
    val options = remember(words) {
        val rng = SecureRandom()
        positions.map { p ->
            val correct = words[p]
            val decoys = HashSet<String>()
            while (decoys.size < 3) { val w = Bip39.words[rng.nextInt(Bip39.words.size)]; if (w != correct && w !in words) decoys.add(w) }
            (decoys + correct).shuffled(rng)
        }
    }
    val selected = remember(words) { mutableStateOf(List<String?>(positions.size) { null }) }
    var wrong by remember { mutableStateOf(false) }
    Text("Pick the right word for each position to confirm your backup.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
    positions.forEachIndexed { qi, pos ->
        SectionCard {
            Text("Word #${pos + 1}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            val haptics = rememberHaptics()
            for (r in 0 until 2) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (c in 0 until 2) {
                    val w = options[qi][r * 2 + c]
                    FilterChip(selected = selected.value[qi] == w, onClick = { haptics.tick(); selected.value = selected.value.toMutableList().also { it[qi] = w }; wrong = false }, label = { Text(w, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    if (wrong) InfoBanner("Those words don't match your phrase. Go back and check it carefully.", BannerKind.ERROR)
    PrimaryButton("Verify & create wallet", enabled = selected.value.all { it != null }, onClick = {
        if (positions.indices.all { selected.value[it] == words[positions[it]] }) onVerified() else wrong = true
    })
}

@Composable
fun RestoreWalletScreen(vm: OnboardingViewModel, onDone: () -> Unit, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val network by vm.network.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("My Pearl Wallet") }
    var phrase by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val check = remember(phrase) { vm.checkSeedInput(phrase) }
    LaunchedEffect(state.done) { if (state.done) onDone() }

    ScreenScaffold(title = "Restore wallet", subtitle = network.displayName, onBack = if (state.busy) null else onBack) {
        SecureWindow()
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.busy) {
                LoadingBlock(state.progress ?: "Restoring…")
                InfoBanner("Each address needs a post-quantum key, so deriving the discovery window takes a little while. The wallet then scans the Pearl indexer for your history.", BannerKind.INFO)
                return@Column
            }
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Wallet name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = FieldShape)
            OutlinedTextField(
                value = phrase, onValueChange = { phrase = it },
                label = { Text("Recovery phrase or hex seed") },
                placeholder = { Text("12–24 words separated by spaces") },
                minLines = 3, modifier = Modifier.fillMaxWidth(), shape = FieldShape,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                isError = phrase.isNotBlank() && check is OnboardingViewModel.SeedCheck.Bad,
                supportingText = {
                    when (check) {
                        is OnboardingViewModel.SeedCheck.Ok -> Text("✓ ${check.description}", color = MaterialTheme.colorScheme.primary)
                        is OnboardingViewModel.SeedCheck.Bad -> if (phrase.isNotBlank()) Text(check.reason, color = MaterialTheme.colorScheme.error)
                    }
                },
            )
            PasswordField(password, { password = it }, "New password")
            if (password.isNotEmpty()) PasswordStrength(password)
            PasswordField(confirm, { confirm = it }, "Confirm password", imeAction = ImeAction.Done, isError = confirm.isNotEmpty() && confirm != password)
            state.error?.let { InfoBanner(it, BannerKind.ERROR) }
            InfoBanner("Works with phrases from the official Pearl desktop wallet (oyster) and any BIP-39 wallet using Pearl's derivation.", BannerKind.INFO)
            PrimaryButton(
                "Restore wallet",
                enabled = name.isNotBlank() && check is OnboardingViewModel.SeedCheck.Ok && password.length >= 8 && password == confirm && passwordScore(password) >= 2,
                onClick = { (check as? OnboardingViewModel.SeedCheck.Ok)?.let { vm.restore(name, it.material, password) } },
            )
        }
    }
}
