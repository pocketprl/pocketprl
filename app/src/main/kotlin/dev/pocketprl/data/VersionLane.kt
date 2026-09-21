package dev.pocketprl.data

import dev.pocketprl.BuildConfig

/**
 * Every release can carry up to three builds, one per lane:
 *
 *  * [PRIMARY]  – the normal build. Fresh installs and normal updates land here.
 *  * [ROLLBACK] – a build of an older version with a code above the primary band.
 *                 Installing it downgrades in place, data preserved.
 *  * [BACK]     – a build above the rollback band so a downgraded install can
 *                 climb back up. Once on it, only newer versions install in place.
 *
 * Android only ever installs a *higher* package code, so the code axis is a
 * one-way street. The lanes make downgrades and returns possible, but anything
 * that would need to go *down* out of the back lane uses the reset flow instead.
 */
enum class VersionLane { PRIMARY, ROLLBACK, BACK }

object VersionCodes {
    const val PRIMARY_BASE = 100_000L
    const val ROLLBACK_BASE = 200_000L
    const val BACK_BASE = 300_000L

    /** Which lane a package version code belongs to. */
    fun laneOf(code: Long): VersionLane = when {
        code >= BACK_BASE -> VersionLane.BACK
        code >= ROLLBACK_BASE -> VersionLane.ROLLBACK
        else -> VersionLane.PRIMARY
    }
}

/**
 * What this installed app is: its lane, and the primary code of its release so a
 * shifted (rollback or back) build can be told apart from the regular one.
 */
object BuildInfo {
    /** This APK's package version code. */
    val code: Long get() = BuildConfig.VERSION_CODE.toLong()

    /** The code the regular build of this same release carries. */
    val baseCode: Long get() = BuildConfig.NORMAL_VERSION_CODE

    val lane: VersionLane get() = VersionCodes.laneOf(code)

    val isPrimary: Boolean get() = lane == VersionLane.PRIMARY

    /** True for a rollback or back build: a release rebuilt above the primary band. */
    val isShifted: Boolean get() = code > baseCode
}
