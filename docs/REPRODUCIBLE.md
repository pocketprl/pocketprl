# Reproducible builds

> **Status: in progress.** The toolchain and inputs are pinned (Tier 1 below),
> but the APK has **not** yet been proven byte-for-byte reproducible across two
> independent machines. Do not advertise reproducibility until that check
> passes. This file records what is pinned, what is verified, and the exact
> procedure to check a release.

Building the same source twice should ideally produce the same bytes. For a
self-custody wallet that matters: it lets anyone confirm that the APK they
download corresponds to the source in this repository, rather than trusting the
maintainer's build machine.

## Pinned inputs

| Input | Pinned value | Where |
|---|---|---|
| Gradle | 9.6.0 | `gradle/wrapper/gradle-wrapper.properties` |
| JDK | 17 | `.java-version`, CI (`actions/setup-java`), `jvmTarget` in `app/build.gradle.kts` |
| Kotlin bytecode target | JVM 17 | `kotlin { compilerOptions { jvmTarget } }` |
| Android platform | `platforms;android-37.0` | CI install step |
| Build tools | `build-tools;37.0.0` | CI install step |
| Dependencies | SHA-256 per artifact | `gradle/verification-metadata.xml` |
| Archive entry order/timestamps | fixed | `AbstractArchiveTask` config in root `build.gradle.kts` |

Notably there is **no NDK compilation**: `secp256k1-kmp-jni-android` ships
prebuilt `.so` files, so the native toolchain is not an input. That removes one
of the usual reproducibility headaches.

## Building

```bash
# JDK 17 required for a build that matches CI.
export JAVA_HOME=/path/to/jdk-17

./gradlew :app:assembleRelease
```

The release APK lands at `app/build/outputs/apk/release/app-release.apk`.

## Verifying a release APK

The release key is held offline and is **not** in CI, so no one else can
reproduce the *signature*. What can be verified is (a) the signing certificate,
and (b) that the APK's contents match a build you made yourself.

`apksigcopier` (used by F-Droid and CalyxOS) copies the signature block from the
released APK onto your locally built one so the two are comparable:

```bash
# 1. Your own unsigned-ish build (CI uses an ephemeral key; see ci.yml).
./gradlew :app:assembleRelease

# 2. Compare: the released APK must equal your build plus the signature.
pip install apksigcopier
apksigcopier compare \
  app/build/outputs/apk/release/app-release.apk \
  /path/to/downloaded/PocketPRL-<version>.apk
```

`tools/verify-release.sh` wraps the certificate check and the hash printout.

Expected signing certificate (SHA-256):

```
5D:FF:D4:A7:05:13:5B:0E:85:A9:D3:C6:03:70:E8:27:BB:A4:AA:5B:5A:2E:C2:0F:A9:07:44:EC:67:2C:C8:55
```

## Known gaps (to close before claiming reproducibility)

1. **APK packaging is done by AGP, not `AbstractArchiveTask`.** ZIP entry
   timestamps/order in the APK are not yet proven stable. This is the most
   likely source of a mismatch.
2. **`SOURCE_DATE_EPOCH` is not wired into the Android packaging pipeline.**
   Gradle consumes it for some tasks, but not for the APK.
3. **No second-machine check yet.** The procedure above has not been run from a
   clean environment and compared.
4. **Dependency verification is new.** CI has not yet exercised
   `gradle/verification-metadata.xml` on a cold cache; a missing entry would
   fail the build. Add entries with
   `./gradlew --write-verification-metadata sha256 <tasks>` as needed.

## Signing policy

- The release keystore lives offline. It is never committed and never placed in
  GitHub Actions secrets.
- CI (`ci.yml`) generates a **throwaway** key just to exercise the release
  signing path. CI artifacts are for verification and must not be distributed.
