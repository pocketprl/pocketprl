package dev.pocketprl.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.pocketprl.AppContainer
import dev.pocketprl.BuildConfig
import dev.pocketprl.Shortcut
import dev.pocketprl.data.WalletContext
import dev.pocketprl.ui.screens.AboutScreen
import dev.pocketprl.ui.screens.ActivityScreen
import dev.pocketprl.ui.screens.AddressesScreen
import dev.pocketprl.ui.screens.ChangePasswordScreen
import dev.pocketprl.ui.screens.ContactsScreen
import dev.pocketprl.ui.screens.CreateWalletScreen
import dev.pocketprl.ui.screens.DashboardScreen
import dev.pocketprl.ui.screens.EraseWalletScreen
import dev.pocketprl.ui.screens.NetworkSettingsScreen
import dev.pocketprl.ui.screens.PersonalizeScreen
import dev.pocketprl.ui.screens.ReceiveScreen
import dev.pocketprl.ui.screens.RestoreWalletScreen
import dev.pocketprl.ui.screens.RevealSeedScreen
import dev.pocketprl.ui.screens.SendScreen
import dev.pocketprl.ui.screens.SettingsScreen
import dev.pocketprl.ui.screens.StatsScreen
import dev.pocketprl.ui.screens.TxDetailScreen
import dev.pocketprl.ui.screens.UnlockScreen
import dev.pocketprl.ui.screens.UpdateScreen
import dev.pocketprl.ui.screens.WelcomeScreen
import dev.pocketprl.ui.screens.WhatsNewScreen
import dev.pocketprl.ui.vm.OnboardingViewModel
import dev.pocketprl.ui.vm.SendViewModel
import dev.pocketprl.ui.vm.SettingsViewModel
import dev.pocketprl.ui.vm.UnlockViewModel
import dev.pocketprl.ui.vm.WalletViewModel
import dev.pocketprl.ui.vm.appContainer
import dev.pocketprl.ui.vm.appViewModel

object Routes {
    const val WELCOME = "welcome"
    const val PERSONALIZE = "personalize/{next}"
    const val CREATE = "create"
    const val RESTORE = "restore"
    const val UNLOCK = "unlock"
    const val HOME = "home"
    const val ACTIVITY = "activity"
    const val TX = "tx/{txid}"
    const val RECEIVE = "receive"
    const val SEND = "send"
    const val SETTINGS = "settings"
    const val PASSWORD = "settings/password"
    const val SEED = "settings/seed"
    const val NETWORK = "settings/network"
    const val ADDRESSES = "settings/addresses"
    const val CONTACTS = "settings/contacts"
    const val STATS = "settings/stats"
    const val ABOUT = "settings/about"
    const val UPDATE = "update"
    const val ERASE = "erase"
    const val WHATS_NEW = "whatsnew"

    fun personalize(next: String) = "personalize/$next"

    val PUBLIC = setOf(WELCOME, PERSONALIZE, CREATE, RESTORE, UNLOCK)

    /** Screens reachable without an unlocked wallet: the onboarding set plus the locked-screen erase flow. */
    val REACHABLE_LOCKED = PUBLIC + ERASE
}

private fun NavHostController.resetTo(route: String) = navigate(route) { popUpTo(0) { inclusive = true }; launchSingleTop = true }

// ---------------------------------------------------------------- transitions
//
// One horizontal push for the whole app. Pop and predictive-back use the mirror of
// the push transitions, so the back gesture tracks the finger.

private const val NAV_MS = 320

/** Parallax divisor for the screen underneath. */
private const val PARALLAX = 3

/** Alpha of the screen underneath while it is covered. */
private const val UNDER_ALPHA = 0.85f

private fun slide() = tween<IntOffset>(NAV_MS, easing = FastOutSlowInEasing)

private fun dim() = tween<Float>(NAV_MS, easing = FastOutSlowInEasing)

// The top screen only translates, never fades, so a half-finished back gesture leaves it
// fully opaque. Every spec runs for the same duration so the gesture seeks them together.

private val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(slide()) { it }
}

private val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(slide()) { -it / PARALLAX } + fadeOut(dim(), targetAlpha = UNDER_ALPHA)
}

private val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(slide()) { -it / PARALLAX } + fadeIn(dim(), initialAlpha = UNDER_ALPHA)
}

private val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(slide()) { it }
}

// Predictive back has its own pair of transitions (default: Material scale-out); point them at
// the slides. The Int parameter is the swipe edge, which the slide ignores.
private val predictivePopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> EnterTransition = { popEnter() }
private val predictivePopExit: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> ExitTransition = { popExit() }

/**
 * The whole navigation tree is keyed by the active wallet id: switching,
 * adding or deleting a wallet tears the UI down and rebuilds it for the new
 * wallet with a fresh back stack and fresh view models.
 */
@Composable
fun AppNav() {
    val container = appContainer()
    val activeId by container.activeId.collectAsStateWithLifecycle()
    key(activeId) {
        val ctx = activeId?.let { container.context(it) }
        if (ctx == null) OnboardingNav() else WalletNav(container, ctx)
    }
}

/** No wallet on the device yet: only the create / restore flow exists. */
@Composable
private fun OnboardingNav() {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = Routes.WELCOME,
        enterTransition = enter,
        exitTransition = exit,
        popEnterTransition = popEnter,
        popExitTransition = popExit,
        predictivePopEnterTransition = predictivePopEnter,
        predictivePopExitTransition = predictivePopExit,
    ) {
        onboardingRoutes(nav, addMode = false)
    }
}

private fun NavGraphBuilder.onboardingRoutes(nav: NavHostController, addMode: Boolean) {
    composable(Routes.WELCOME) {
        val vm: OnboardingViewModel = appViewModel()
        val network by vm.network.collectAsStateWithLifecycle()
        WelcomeScreen(
            network = network,
            onNetworkChange = { vm.setNetwork(it) },
            onCreate = { nav.navigate(if (addMode) Routes.CREATE else Routes.personalize("create")) },
            onRestore = { nav.navigate(if (addMode) Routes.RESTORE else Routes.personalize("restore")) },
            onBack = if (addMode) ({ nav.popBackStack() }) else null,
        )
    }
    // First-run only: pick theme, accent, number format and currency before creating a wallet.
    composable(Routes.PERSONALIZE) { entry ->
        PersonalizeScreen(
            next = entry.arguments?.getString("next") ?: "create",
            onCreate = { nav.navigate(Routes.CREATE) },
            onRestore = { nav.navigate(Routes.RESTORE) },
            onBack = { nav.popBackStack() },
        )
    }
    // onDone is a no-op: the new wallet becomes active, which rebuilds the tree on its home screen.
    composable(Routes.CREATE) {
        val vm: OnboardingViewModel = appViewModel()
        CreateWalletScreen(vm, onDone = {}, onBack = { nav.popBackStack() })
    }
    composable(Routes.RESTORE) {
        val vm: OnboardingViewModel = appViewModel()
        RestoreWalletScreen(vm, onDone = {}, onBack = { nav.popBackStack() })
    }
}

@Composable
private fun WalletNav(container: AppContainer, ctx: WalletContext) {
    val nav = rememberNavController()
    val walletVm: WalletViewModel = appViewModel()
    val unlocked by walletVm.unlocked.collectAsStateWithLifecycle()
    val pendingPayment by container.pendingPaymentUri.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val pendingTxid by container.pendingTxid.collectAsStateWithLifecycle()
    val pendingShortcut by container.pendingShortcut.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    val start = if (ctx.session.isUnlocked) Routes.HOME else Routes.UNLOCK

    // Poll only while this wallet is unlocked and the app is resumed; every resume starts a fresh sync.
    LifecycleResumeEffect(unlocked) {
        if (unlocked) walletVm.startPolling()
        onPauseOrDispose { walletVm.stopPolling() }
    }
    DisposableEffect(walletVm) { onDispose { walletVm.stopPolling() } }

    // Locking anywhere in the private area returns to the unlock screen.
    LaunchedEffect(unlocked, route) {
        if (!unlocked && route != null && route !in Routes.REACHABLE_LOCKED) nav.resetTo(Routes.UNLOCK)
    }

    // A pending pearl: link opens Send once unlocked; SendScreen consumes it.
    LaunchedEffect(pendingPayment, unlocked, route) {
        if (pendingPayment != null && unlocked && route != null && route !in Routes.PUBLIC && route != Routes.SEND) {
            nav.navigate(Routes.SEND) { launchSingleTop = true }
        }
    }

    // A tapped payment notification opens its transaction.
    LaunchedEffect(pendingTxid, unlocked, route) {
        val txid = pendingTxid ?: return@LaunchedEffect
        if (unlocked && route != null && route !in Routes.PUBLIC) {
            nav.navigate("tx/$txid") { launchSingleTop = true }
            container.pendingTxid.value = null
        }
    }

    // A home-screen shortcut.
    LaunchedEffect(pendingShortcut, unlocked, route) {
        val shortcut = pendingShortcut ?: return@LaunchedEffect
        if (unlocked && route != null && route !in Routes.PUBLIC) {
            when (shortcut) {
                Shortcut.SEND -> nav.navigate(Routes.SEND) { launchSingleTop = true }
                Shortcut.RECEIVE -> nav.navigate(Routes.RECEIVE) { launchSingleTop = true }
                Shortcut.SCAN -> { container.requestScan.value = true; nav.navigate(Routes.SEND) { launchSingleTop = true } }
            }
            container.pendingShortcut.value = null
        }
    }

    // The post-update "what's new", once, after unlock, on the version that was installed.
    LaunchedEffect(unlocked, route, settings.whatsNewVersion) {
        if (unlocked && route != null && route !in Routes.PUBLIC && route != Routes.WHATS_NEW &&
            settings.whatsNewVersion.isNotEmpty() && settings.whatsNewVersion == BuildConfig.VERSION_NAME
        ) {
            nav.navigate(Routes.WHATS_NEW) { launchSingleTop = true }
        }
    }

    val addWallet: () -> Unit = { nav.navigate(Routes.WELCOME) }

    NavHost(
        navController = nav,
        startDestination = start,
        enterTransition = enter,
        exitTransition = exit,
        popEnterTransition = popEnter,
        popExitTransition = popExit,
        predictivePopEnterTransition = predictivePopEnter,
        predictivePopExitTransition = predictivePopExit,
    ) {
        onboardingRoutes(nav, addMode = true)
        composable(Routes.UNLOCK) {
            val vm: UnlockViewModel = appViewModel()
            UnlockScreen(vm, onUnlocked = { nav.resetTo(Routes.HOME) }, onAddWallet = addWallet, onErase = { nav.navigate(Routes.ERASE) })
        }
        composable(Routes.HOME) {
            DashboardScreen(
                walletVm,
                onSend = { nav.navigate(Routes.SEND) },
                onReceive = { nav.navigate(Routes.RECEIVE) },
                onActivity = { nav.navigate(Routes.ACTIVITY) },
                onTx = { nav.navigate("tx/$it") },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onLock = { walletVm.lock() },
                onAddWallet = addWallet,
            )
        }
        composable(Routes.ACTIVITY) { ActivityScreen(walletVm, onTx = { nav.navigate("tx/$it") }, onBack = { nav.popBackStack() }) }
        composable(Routes.TX) { entry ->
            TxDetailScreen(walletVm, txid = entry.arguments?.getString("txid") ?: "", onBack = { nav.popBackStack() })
        }
        composable(Routes.RECEIVE) { ReceiveScreen(walletVm, onBack = { nav.popBackStack() }) }
        composable(Routes.SEND) {
            val vm: SendViewModel = appViewModel()
            SendScreen(vm, walletVm, onBack = { nav.popBackStack() }, explorerUrl = { walletVm.explorerTxUrl(it) })
        }
        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = appViewModel()
            SettingsScreen(
                vm,
                onBack = { nav.popBackStack() },
                onChangePassword = { nav.navigate(Routes.PASSWORD) },
                onRevealSeed = { nav.navigate(Routes.SEED) },
                onNetwork = { nav.navigate(Routes.NETWORK) },
                onAddresses = { nav.navigate(Routes.ADDRESSES) },
                onContacts = { nav.navigate(Routes.CONTACTS) },
                onStats = { nav.navigate(Routes.STATS) },
                onAbout = { nav.navigate(Routes.ABOUT) },
                onAddWallet = addWallet,
                onErase = { nav.navigate(Routes.ERASE) },
            )
        }
        composable(Routes.ERASE) {
            EraseWalletScreen(
                walletName = ctx.vault.walletName ?: ctx.entry.name,
                onBack = { nav.popBackStack() },
                onErased = { container.deleteWallet(ctx.id) },
            )
        }
        composable(Routes.UPDATE) { UpdateScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.WHATS_NEW) {
            WhatsNewScreen(
                version = settings.whatsNewVersion,
                notes = settings.whatsNewNotes,
                onDone = { container.settings.clearWhatsNew(); nav.resetTo(Routes.HOME) },
            )
        }
        composable(Routes.PASSWORD) { val vm: SettingsViewModel = appViewModel(); ChangePasswordScreen(vm, onBack = { nav.popBackStack() }) }
        composable(Routes.SEED) { val vm: SettingsViewModel = appViewModel(); RevealSeedScreen(vm, onBack = { nav.popBackStack() }) }
        composable(Routes.NETWORK) { val vm: SettingsViewModel = appViewModel(); NetworkSettingsScreen(vm, onBack = { nav.popBackStack() }) }
        composable(Routes.ADDRESSES) { val vm: SettingsViewModel = appViewModel(); AddressesScreen(vm, onBack = { nav.popBackStack() }) }
        composable(Routes.CONTACTS) { val vm: SettingsViewModel = appViewModel(); ContactsScreen(vm, onBack = { nav.popBackStack() }) }
        composable(Routes.STATS) { val vm: SettingsViewModel = appViewModel(); StatsScreen(vm, onBack = { nav.popBackStack() }) }
        composable(Routes.ABOUT) { val vm: SettingsViewModel = appViewModel(); AboutScreen(vm, onBack = { nav.popBackStack() }, onOpenUpdate = { nav.navigate(Routes.UPDATE) }) }
    }
}
