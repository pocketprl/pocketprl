package dev.pocketprl.ui.screens

import androidx.activity.compose.LocalActivity
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.fragment.app.FragmentActivity
import dev.pocketprl.R
import dev.pocketprl.core.crypto.Bip39
import dev.pocketprl.ui.Biometrics
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
import dev.pocketprl.ui.components.SlideToConfirm
import dev.pocketprl.ui.components.WordChip
import dev.pocketprl.ui.components.passwordScore
import dev.pocketprl.ui.theme.AppIcons
import dev.pocketprl.ui.vm.OnboardingViewModel
import dev.pocketprl.ui.vm.appContainer
import java.security.SecureRandom

private enum class CreateStep { SETUP, SEED, VERIFY, WORKING }

/*
 * Passwords and phrases below use `remember`, not `rememberSaveable`: saved instance
 * state is handed to the system process and must never carry secrets.
 */

/**
 * The last thing that runs during first-run creation/restore. When onboarding asked
 * for biometric unlock, the wallet was created *inactive* so the system prompt can be
 * shown here, while this screen still owns the foreground, instead of being deferred
 * to a cross-screen effect after the navigation tree rebuilds. It then activates the
 * wallet unconditionally, so a cancelled or failed prompt never strands the user.
 */
@Composable
private fun FinishOnboarding(vm: OnboardingViewModel, onDone: () -> Unit) {
    val container = appContainer()
    val activity = LocalActivity.current
    val state by vm.state.collectAsStateWithLifecycle()
    var handled by remember { mutableStateOf(false) }
    LaunchedEffect(state.done, activity) {
        if (!state.done || handled) return@LaunchedEffect
        handled = true
        val ctx = vm.createdContext()
        val act = activity as? FragmentActivity
        if (state.awaitingBiometric && act != null && ctx != null && !ctx.vault.biometricEnabled) {
            val cipher = runCatching { ctx.vault.biometricEncryptCipher() }.getOrNull()
            if (cipher != null) {
                when (val r = Biometrics.authenticate(
                    act,
                    act.getString(R.string.settings_biometric_prompt),
                    act.getString(R.string.app_name),
                    cipher,
                    negative = act.getString(R.string.action_cancel),
                )) {
                    is Biometrics.Outcome.Success -> runCatching { ctx.session.withDek { dek -> ctx.vault.enableBiometric(dek, r.cipher) } }
                    else -> Unit
                }
            }
        }
        container.settings.pendingBiometricSetup = false
        vm.activateCreated()
        onDone()
    }
}

@Composable
fun CreateWalletScreen(vm: OnboardingViewModel, onDone: () -> Unit, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val network by vm.network.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableStateOf(CreateStep.SETUP) }
    val defaultName = stringResource(R.string.onboard_default_wallet_name)
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { if (state.mnemonic == null) vm.generateMnemonic() }
    FinishOnboarding(vm, onDone)

    // `step` survives process death but the phrase and password do not; restart the flow rather
    // than quiz on an empty phrase or create a wallet with no password.
    val lostSecrets = step != CreateStep.SETUP && (state.mnemonic == null || password.isEmpty())
    LaunchedEffect(lostSecrets) { if (lostSecrets) step = CreateStep.SETUP }
    val shown = if (lostSecrets) CreateStep.SETUP else step

    ScreenScaffold(
        title = when (shown) {
            CreateStep.SETUP -> stringResource(R.string.onboard_title_create)
            CreateStep.SEED -> stringResource(R.string.onboard_title_seed)
            CreateStep.VERIFY -> stringResource(R.string.onboard_title_verify)
            CreateStep.WORKING -> stringResource(R.string.onboard_title_working)
        },
        subtitle = stringResource(R.string.onboard_step, (shown.ordinal + 1).coerceAtMost(3), network.displayName),
        onBack = when (shown) { CreateStep.SETUP -> onBack; CreateStep.SEED -> ({ step = CreateStep.SETUP }); CreateStep.VERIFY -> ({ step = CreateStep.SEED }); CreateStep.WORKING -> null },
    ) {
        StepProgress(shown.ordinal, 3)
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (shown) {
                CreateStep.SETUP -> {
                    Text(stringResource(R.string.onboard_name_protect), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.onboard_wallet_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = FieldShape)
                    PasswordField(password, { password = it }, stringResource(R.string.onboard_password))
                    if (password.isNotEmpty()) PasswordStrength(password)
                    PasswordField(confirm, { confirm = it }, stringResource(R.string.onboard_confirm_password), imeAction = ImeAction.Done, isError = confirm.isNotEmpty() && confirm != password)
                    InfoBanner(stringResource(R.string.onboard_password_warning), BannerKind.WARNING)
                    state.error?.let { InfoBanner(it, BannerKind.ERROR) }
                    PrimaryButton(
                        stringResource(R.string.action_continue),
                        onClick = { vm.clearError(); step = CreateStep.SEED },
                        enabled = name.isNotBlank() && password.length >= 8 && password == confirm && passwordScore(password) >= 2,
                    )
                    if (password.isNotEmpty() && passwordScore(password) < 2) Text(stringResource(R.string.onboard_password_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                CreateStep.SEED -> {
                    SecureWindow()
                    val words = state.mnemonic?.split(' ') ?: emptyList()
                    Text(stringResource(R.string.onboard_seed_write), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                    SectionCard { WordGrid(words) }
                    InfoBanner(stringResource(R.string.onboard_seed_secret_body), BannerKind.WARNING, title = stringResource(R.string.onboard_seed_secret_title))
                    PrimaryButton(stringResource(R.string.onboard_written), onClick = { step = CreateStep.VERIFY })
                }
                CreateStep.VERIFY -> {
                    SecureWindow()
                    val words = state.mnemonic?.split(' ') ?: emptyList()
                    SeedQuiz(words = words, onVerified = { step = CreateStep.WORKING; vm.create(name, password) })
                }
                CreateStep.WORKING -> {
                    LoadingBlock(state.progress ?: stringResource(R.string.onboard_creating))
                    state.error?.let { InfoBanner(it, BannerKind.ERROR); SecondaryButton(stringResource(R.string.action_back), onClick = { step = CreateStep.SETUP }) }
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
    Text(stringResource(R.string.onboard_quiz_instruction), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
    positions.forEachIndexed { qi, pos ->
        SectionCard {
            Text(stringResource(R.string.onboard_word_n, pos + 1), style = MaterialTheme.typography.titleMedium)
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
    if (wrong) InfoBanner(stringResource(R.string.onboard_quiz_error), BannerKind.ERROR)
    PrimaryButton(stringResource(R.string.onboard_verify_create), enabled = selected.value.all { it != null }, onClick = {
        if (positions.indices.all { selected.value[it] == words[positions[it]] }) onVerified() else wrong = true
    })
}

@Composable
fun RestoreWalletScreen(vm: OnboardingViewModel, onDone: () -> Unit, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val network by vm.network.collectAsStateWithLifecycle()
    val defaultName = stringResource(R.string.onboard_default_wallet_name)
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var phrase by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val check = remember(phrase) { vm.checkSeedInput(phrase) }
    FinishOnboarding(vm, onDone)

    ScreenScaffold(title = stringResource(R.string.onboard_restore_title), subtitle = network.displayName, onBack = if (state.busy) null else onBack) {
        SecureWindow()
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.busy) {
                LoadingBlock(state.progress ?: stringResource(R.string.onboard_restoring))
                InfoBanner(stringResource(R.string.onboard_restore_progress_body), BannerKind.INFO)
                return@Column
            }
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.onboard_wallet_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = FieldShape)
            OutlinedTextField(
                value = phrase, onValueChange = { phrase = it },
                label = { Text(stringResource(R.string.onboard_recovery_label)) },
                placeholder = { Text(stringResource(R.string.onboard_recovery_placeholder)) },
                minLines = 3, modifier = Modifier.fillMaxWidth(), shape = FieldShape,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                isError = phrase.isNotBlank() && check is OnboardingViewModel.SeedCheck.Bad,
                supportingText = {
                    when (check) {
                        is OnboardingViewModel.SeedCheck.Ok -> Text(stringResource(R.string.onboard_check_ok, check.description), color = MaterialTheme.colorScheme.primary)
                        is OnboardingViewModel.SeedCheck.Bad -> if (phrase.isNotBlank()) Text(check.reason, color = MaterialTheme.colorScheme.error)
                    }
                },
            )
            PasswordField(password, { password = it }, stringResource(R.string.onboard_new_password))
            if (password.isNotEmpty()) PasswordStrength(password)
            PasswordField(confirm, { confirm = it }, stringResource(R.string.onboard_confirm_password), imeAction = ImeAction.Done, isError = confirm.isNotEmpty() && confirm != password)
            state.error?.let { InfoBanner(it, BannerKind.ERROR) }
            InfoBanner(stringResource(R.string.onboard_restore_compatible), BannerKind.INFO)
            val canRestore = name.isNotBlank() && check is OnboardingViewModel.SeedCheck.Ok &&
                password.length >= 8 && password == confirm && passwordScore(password) >= 2
            SlideToConfirm(
                onComplete = { (check as? OnboardingViewModel.SeedCheck.Ok)?.let { vm.restore(name, it.material, password) } },
                label = stringResource(R.string.onboard_restore_button),
                icon = AppIcons.Key,
                enabled = canRestore,
                slideHint = stringResource(R.string.onboard_restore_slide_hint),
                notReady = stringResource(R.string.onboard_restore_not_ready),
            )
        }
    }
}
