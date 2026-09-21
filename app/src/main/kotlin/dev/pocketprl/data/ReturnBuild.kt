package dev.pocketprl.data

import dev.pocketprl.BuildConfig

/**
 * A return build is a release rebuilt with a version code above the rollback band
 * so it can be installed over an older rollback. Sitting above every downgrade
 * target, it cannot install anything older over itself, so the app offers to swap
 * it for the regular build of the same version.
 *
 * [BuildConfig.VERSION_CODE] is this APK's actual code; [BuildConfig.NORMAL_VERSION_CODE]
 * is what a regular build of the same release carries. The two differ only for a
 * return build.
 */
object ReturnBuild {
    val isReturn: Boolean get() = BuildConfig.VERSION_CODE.toLong() > BuildConfig.NORMAL_VERSION_CODE
}
