# Version codes and in-place downgrades

Android refuses to install an APK whose `versionCode` is **lower** than the
installed one (`INSTALL_FAILED_VERSION_DOWNGRADE`); the check is in the platform
and an app cannot bypass it. So to install an *older* release over a newer one,
that older release's APK must carry a version code **above** the installed build.
The version name stays truthful; only the number moves.

This wallet uses that for one direction only: **from 2.4.0 you can install any
older release.** Returning to 2.4.0 from a rollback is not handled here (see
"What is not supported").

## The band

| Range | Use |
|---|---|
| `1` … `15` | The original 1.0.0 … 2.3.2 assets. **Burned.** Fresh install only; never installable in place again. |
| `16` … `99999` | Reserved / unused. No reason to touch it. |
| `100000` … `2147483647` | In-place band. 2.4.0 and every rollback live here. |

`versionCode` is a 32-bit int, so the band holds ~2.147 billion builds. If that
were ever exhausted, `versionCodeMajor` extends it to 64-bit. The next free code
is **100026**.

## Current assignment

| Release | Code | Role |
|---|---|---|
| 2.4.1 | 100027 | Current regular release |
| 1.0.0 … 2.3.2 | 100028 … 100039 | Rollbacks (above 2.4.1) |
| 2.4.1 return | 100040 | Return build for 2.4.1 |
| 2.4.0 | 100013 | Previous regular release |
| 2.4.0 return | 100026 | Return build for 2.4.0 |

Every rollback is above the current regular release, so from 2.4.1 each one
installs; they are ordered so older versions have higher codes, letting a rollback
go further down. **Rollbacks must be reissued above every new regular release**,
or that release loses its downgrade targets — that is the recurring cost of
in-place downgrade.

## What works

* From **2.4.1 (100027)**: install **any** rollback (100028 … 100039) in place,
  data preserved.
* From a legacy install (1 … 15): normal upgrade to 2.4.1 or to any rollback.
* From a rollback: install any *older* rollback (a higher code), but not a newer
  one.

## Return builds

A return build is the regular release rebuilt with a code above the rollbacks
(`100040` for 2.4.1) so it can be installed over one. Because it then sits above
every downgrade target, it cannot downgrade in place. The app detects this
(`BuildConfig.VERSION_CODE > BuildConfig.NORMAL_VERSION_CODE`), explains it once
on first launch, and offers to switch to the regular build. The switch saves the
regular build to Downloads and uninstalls, since Android refuses a lower package
version in place.

## What is not supported

* **Returning to the regular build in place from a return build.** The regular
  code is lower, so Android refuses it; the app offers the save-then-uninstall
  reset flow instead. A later regular release above the rollbacks also works.

## Getting back to 2.4.0 (escape hatch)

The v2.4.0 release also carries **`PocketPRL-2.4.0-return-100026.apk`**, a build
of 2.4.0 above every rollback. Install it to leave a rollback:

```
adb install -r PocketPRL-2.4.0-return-100026.apk
```

or open the v2.4.0 release page and install that asset by hand. The in-app
updater prefers the plain `PocketPRL-2.4.0.apk` for normal installs, so a fresh
install stays at 100013 and keeps the ability to downgrade.

For the future, a normal release published with a code above 100025 (e.g. the
next version at 100026+) also lets rollback users update forward through the
in-app updater, no manual step.

## Invariants

1. Never reuse a code, and never publish below the highest code already shipped
   for the in-place channel.
2. A build installs only over lower codes. A future release that must install over
   the rollbacks needs a code above 100025; the latest normal release must stay
   above all current rollbacks for a normal update path.
3. The release signing key never changes, or cross-version installs stop working.
4. Replacing a published asset changes its SHA-256; update `SHA256SUMS.txt` on the
   releases that carry one.

## Reissuing a release

`tools/reissue-downgrade.sh <tag> <versionCode> [outdir]` builds a tag in a
throwaway worktree with the given version code, signs it with the release key and
verifies the result. It does not touch the tag. Publish the result by replacing
the release's `PocketPRL-<version>.apk` asset.
