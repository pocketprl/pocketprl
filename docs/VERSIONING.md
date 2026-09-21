# Version codes, lanes and switching versions

Android only installs a package whose `versionCode` is **higher** than the installed
one (`INSTALL_FAILED_VERSION_DOWNGRADE` otherwise), and no normal app can bypass
that. The code axis is a one-way street. PocketPRL makes switching versions work by
giving each release up to three builds in three lanes, plus a reset path for moves
the lanes cannot express.

## The lanes

| Lane | Code | Role |
|---|---|---|
| **P** primary | `100000 + e` | Fresh installs and normal updates land here. |
| **R** rollback | `250000 - e` | Older versions get a higher code, so they install *down* over a primary in place. |
| **B** back | `300000 + e` | Above the rollback lane, so a downgraded install can climb back up. |

`e` is a monotonic per-version index assigned at release (`1.0.0 = 1`, …, `2.4.1 = 14`,
`2.5.0 = 15`, `2.5.1 = 16`). Every code is fixed when its build is published and never
rebuilt.

Examples: `2.5.1` is `P 100042`, `R 249984`, `B 300016`; `2.5.0` is `P 100041`,
`R 249985`, `B 300015`; `2.3.2` is `R 249988`, `B 300012`.

## What the lanes buy

From an installed code, the reachable builds are exactly those with a higher code:

* **From a P build** (fresh install): every version's R is higher, so any older
  version installs in place, data preserved. B is higher too, so any version is
  reachable.
* **From an R build**: any B is higher (any version), and older R builds are higher
  (keep going down).
* **From a B build**: only newer B builds (newer versions have higher B codes), so
  forward progress continues across releases while off the regular line — but a B
  build can never go down, to a primary, or back to a rollback in place.

**Leaving the primary lane is permanent.** Every P code is below every R and B code,
so once an R or B build is installed, no primary build can ever install over it
again — not even a newer release. Normal updates stop working at that point. From an
alternate build you can move around the alternate lanes all you like, but the only
way back to the primary line is the reset (uninstall, reinstall, restore phrase).
The app warns before any downgrade for exactly this reason, and the update check
says so instead of offering an install that would fail.

## What the lanes cannot do, and the reset edge

Anything that needs to go *down* out of the B lane (or down into a version that has
no higher-coded build) is impossible in place — that is the theorem above, not a
missing asset. For those, the app offers the **reset**: save the target build to
Downloads, uninstall, reinstall, and restore from the recovery phrase. It works for
every version, including ones published before this system existed, because every
version can be restored from its phrase.

There is deliberately **no bundled seed backup**: the recovery phrase already is the
portable backup, and a second copy of the seed in shared storage would widen the
attack surface against this project's threat model.

## Building the lanes

`tools/reissue-downgrade.sh <tag> <versionCode> [outdir]` builds a tag in a throwaway
worktree with the given code and signs it. The lane codes come from the `e` table
above; `tools/publish-lanes.sh` drives a whole batch. Asset names:

* `PocketPRL-<v>.apk` — primary
* `PocketPRL-<v>-roll-<code>.apk` — rollback
* `PocketPRL-<v>-back-<code>.apk` — back

`UpdateChecker` reads the code from the name suffix and `bestAssetFor(installed)`
picks the lowest code above the installed one, or nothing (reset).

## Invariants

1. Every published code is unique and never reused.
2. `P < R ≤ 249999 < 300001 ≤ B < 400000`.
3. The newest primary release always has a code above every previously published
   code, or users on an older shifted build cannot update to it.
4. Signing key never changes, or cross-version installs break.
5. Replacing an asset changes its SHA-256; update `SHA256SUMS.txt` where one exists.
