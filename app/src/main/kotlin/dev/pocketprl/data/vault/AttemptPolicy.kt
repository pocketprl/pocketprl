package dev.pocketprl.data.vault

/**
 * Shared password-guess policy for every screen that checks the wallet
 * password. The counters are persisted in the vault file ([KeyVault]), so
 * killing and reopening the app does not reset them, and the pacing is
 * exponential: a few free tries, then a delay that doubles per further failure.
 *
 * Kept free of Android types so the arithmetic is unit-testable.
 */
object AttemptPolicy {
    /** Wrong guesses allowed before any pacing starts. */
    const val FREE_ATTEMPTS = 3

    /** First lockout, doubled on each further failure. */
    const val BASE_LOCKOUT_MS = 5_000L

    /** Ceiling on a single lockout, so a legitimate user is never locked out forever. */
    const val MAX_LOCKOUT_MS = 30 * 60_000L

    /** Wrong guesses that erase the wallet, only when the user opted in. */
    const val WIPE_AFTER_ATTEMPTS = 10

    /** Show the "N attempts left" warning once this many (or fewer) remain. */
    const val WARN_WHEN_REMAINING = 3

    /** Lockout imposed after [attempts] consecutive failures; zero while within the free allowance. */
    fun lockoutMs(attempts: Int): Long {
        if (attempts <= FREE_ATTEMPTS) return 0
        val step = (attempts - FREE_ATTEMPTS - 1).coerceIn(0, 30)
        val ms = BASE_LOCKOUT_MS shl step
        // Shifting past Long's range would wrap; the clamp catches that too.
        return if (ms <= 0) MAX_LOCKOUT_MS else ms.coerceAtMost(MAX_LOCKOUT_MS)
    }

    /** True when the failure that just happened reached the erase threshold. */
    fun isWipeThreshold(beforeFailureAttempts: Int, wipeAfter: Int?): Boolean =
        wipeAfter != null && beforeFailureAttempts + 1 >= wipeAfter

    /** Attempts left before the erase threshold, or null when the wipe is off. */
    fun remainingBeforeWipe(afterFailureAttempts: Int, wipeAfter: Int?): Int? =
        wipeAfter?.let { (it - afterFailureAttempts).coerceAtLeast(0) }

    /** True when the remaining count is worth warning about. */
    fun shouldWarn(remainingBeforeWipe: Int?): Boolean =
        remainingBeforeWipe != null && remainingBeforeWipe <= WARN_WHEN_REMAINING
}
