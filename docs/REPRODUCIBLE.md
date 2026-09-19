# Reproducible builds

> **Status: byte-for-byte reproducible on one machine.**
> Two clean builds produce identical APKs (matching SHA-256). This was achieved
> by identifying and disabling a release-only, randomized APK signing-block
> payload that AGP injects (ID `SDKP`, the encrypted "SDK dependency info"
> block). What remains is cross-machine reproducibility (see below).

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
| Play dependency-info block | disabled | `android.includeDependencyInfoInApks=false` in `gradle.properties` |

Notably there is **no NDK compilation**: `secp256k1-kmp-jni-android` ships
prebuilt `.so` files, so the native toolchain is not an input.

## Building

```bash
# JDK 17 required for a build that matches CI.
export JAVA_HOME=/path/to/jdk-17

./gradlew :app:assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

## Checking reproducibility

`tools/check-reproducibility.sh` builds the release APK twice from clean with the
Gradle build cache disabled, compares them, and — when they differ — isolates the
difference to the signing block and names the blocks that vary:

```bash
tools/check-reproducibility.sh
```

This only tests the current machine. The real target is two independent machines
(or CI) producing the same hash; a green run here is necessary, not sufficient.

## Reproducibility findings

Measured on one machine, Gradle build cache disabled, two clean
`assembleRelease` runs: **byte-for-byte identical APKs** (matching SHA-256).

Previously the two APKs differed in exactly one APK signing-block pair. The
signing block now contains two pairs, both deterministic:

* `0x7109871a` — APK Signature Scheme v2 — **identical**
* `0x42726577` — verity padding — **identical**

### What the differing block was

The varying pair had ID `0x504b4453` (its bytes read as `SDKP`), and it was not a
signature at all: it is the **SDK dependency-info block** that Android Gradle
Plugin injects into the APK signing block. It is written by
`com.android.build.gradle.internal.tasks.SdkDependencyDataGeneratorTask`, carries
the app's dependency list serialized as
`com.android.tools.build.libraries.metadata.AppDependencies`, and is encrypted
with `com.google.crypto.tink.HybridEncrypt`. Tink hybrid encryption derives a
random ephemeral key and nonce per encryption, so the ciphertext — and with it
the whole APK — changed on every build while every other byte stayed identical.
That matches every observation: high entropy, release-only (the packaging task
gates it on `isDebuggable`), and independent of the signing key and R8. See
https://d.android.com/r/tools/dependency-metadata.

### Disabling it

Set in `gradle.properties`:

```properties
android.includeDependencyInfoInApks=false
```

This removes the block, leaving the APK fully reproducible. Trade-off: Google
Play reads this block for dependency/SBOM reporting, so disabling it drops that
metadata. Reproducibility is preferred here, and the dependency set is already
cryptographically pinned in `gradle/verification-metadata.xml`.

## Verifying a release APK

The release key is held offline and is **not** in CI, so no one else can
reproduce the *signature*. What can be verified is (a) the signing certificate,
and (b) that the APK's contents match a build you made yourself.

`apksigcopier` (used by F-Droid and CalyxOS) copies the signature block from the
released APK onto your locally built one so the two are comparable:

```bash
./gradlew :app:assembleRelease   # your own build

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

## Signing policy

- The release keystore lives offline. It is never committed and never placed in
  GitHub Actions secrets.
- CI (`.github/workflows/ci.yml`) generates a **throwaway** key just to exercise
  the release signing path. CI artifacts are for verification and must not be
  distributed.
