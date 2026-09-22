package dev.pocketprl.data

import dev.pocketprl.data.update.UpdateChecker

/**
 * On-disk schema versions and the compatibility floor for switching the app to a
 * different release in place.
 *
 * No release so far has ever changed a persisted format: v1.0.0 through v2.5.4
 * all shipped database version [DB_VERSION], vault version [VAULT_VERSION] and
 * wallet-registry version [REGISTRY_VERSION]. That is exactly why an in-place
 * downgrade can hand the same files to an older build and its wallets, names,
 * contacts, notes and history carry over untouched — the data is already
 * round-trip compatible, so there is nothing for a migration to rewrite.
 *
 * These constants are the guard for that claim. Changing any persisted format
 * must bump the matching version here *and* add a migration; DataCompatTest
 * fails until both happen.
 */
object SchemaCompat {
    /** `WalletDb` SQLite `user_version`. */
    const val DB_VERSION = 3

    /** `vault-*.json` envelope `version` field. */
    const val VAULT_VERSION = 1

    /** `wallets.json` registry `version` field. */
    const val REGISTRY_VERSION = 1

    /** Earliest release whose readers accept data written by the current app. */
    const val OLDEST_COMPATIBLE = "1.0.0"

    /** First release that shipped the in-app update checker; older builds must be updated by hand. */
    const val FIRST_SELF_UPDATING = "1.1.0"

    /** First release whose updater can download and install the APK itself (2.2.0). */
    const val FIRST_SELF_INSTALLING = "2.2.0"

    /** First release with the version switcher; older builds cannot show their build or switch (2.4.0). */
    const val FIRST_SWITCHER = "2.4.0"

    /** True when [targetVersion] can read the on-disk data the current app writes. */
    fun canReadCurrentData(targetVersion: String): Boolean =
        UpdateChecker.compare(targetVersion, OLDEST_COMPATIBLE) >= 0

    /** True when [targetVersion] has "Check for updates" built in. */
    fun canSelfUpdate(targetVersion: String): Boolean =
        UpdateChecker.compare(targetVersion, FIRST_SELF_UPDATING) >= 0

    /** True when [targetVersion]'s updater can download and install in place, not just open a page. */
    fun canSelfInstall(targetVersion: String): Boolean =
        UpdateChecker.compare(targetVersion, FIRST_SELF_INSTALLING) >= 0

    /** True when [targetVersion] has the version switcher and knows which build it is. */
    fun hasSwitcher(targetVersion: String): Boolean =
        UpdateChecker.compare(targetVersion, FIRST_SWITCHER) >= 0
}
