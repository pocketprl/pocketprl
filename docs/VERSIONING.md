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
| 2.4.0 | 100013 | Fresh / normal release. |
| 2.3.2 | 100014 | Rollback |
| 2.3.1 | 100015 | Rollback |
| 2.3.0 | 100016 | Rollback |
| 2.2.1 | 100017 | Rollback |
| 2.2.0 | 100018 | Rollback |
| 2.1.2 | 100019 | Rollback |
| 2.1.1 | 100020 | Rollback |
| 2.1.0 | 100021 | Rollback |
| 2.0.0 | 100022 | Rollback |
| 1.1.1 | 100023 | Rollback |
| 1.1.0 | 100024 | Rollback |
| 1.0.0 | 100025 | Rollback |

Every rollback is above 2.4.0, so from 2.4.0 each one installs. The rollbacks are
also ordered so that, from any rollback, the *older* ones (higher codes) still
install.

## What works

* From **2.4.0 (100013)**: install **any** rollback (100014 … 100025) in place,
  data preserved.
* From a legacy install (1 … 15): normal upgrade to 2.4.0 or to any rollback.
* From a rollback: install any *older* rollback (a higher code), but not a newer
  one.

## What is not supported

* **Returning to 2.4.0 from a rollback.** The 2.4.0 asset (100013) is below the
  rollback codes, so Android refuses it. To get back: install a later release
  (assign it above 100025), or uninstall/reinstall. This is the deliberate
  one-directional trade-off.

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
