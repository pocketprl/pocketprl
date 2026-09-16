# PocketPRL

A secure, fast and lightweight Android wallet for the **Pearl (PRL)** network,
the PoUW L1 by Pearl Research Labs.

PocketPRL is the official desktop wallet's feature set shrunk to what makes
sense on a phone, plus the things a phone is actually *good* at: payment links,
contacts, notes, and "you got paid" notifications. It derives **exactly the
same addresses as the official desktop wallet (oyster)** from the same recovery
phrase, so you can move between the two freely.

* Release APK around 9 MB, no Go runtime, no chain download, no telemetry.
* Min. Android 9 (API 28), target Android 16 (API 37).

**No iOS port is planned as of now.** Best of luck to anyone who may attempt it.

No actual audits yet, but what I can say from experience is it's worked perfectly 
for me so far. Even so, test with amounts you're willing to lose first.

As we're in early development, any and all feedback is appreciated! Go around and
try to break and poke things. Find something? Open an issue, and write a PR if you're
brave.

## Building

Requirements: JDK 17+, Android SDK with platform 37 and build-tools 37, Gradle wrapper.

```bash
./gradlew :app:testDebugUnitTest       # tests
./gradlew :app:assembleDebug           # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease         # signed with the debug key unless below vars are set:
```

Release signing is read from the environment. Without it Gradle prints a
warning and signs with the debug key; such a build is fine for local testing
and must not be distributed.

```
POCKETPRL_KEYSTORE=/path/to/your/release.jks
POCKETPRL_KEYSTORE_PASSWORD=...
POCKETPRL_KEY_ALIAS=...
POCKETPRL_KEY_PASSWORD=...
```

## Compatibility with the official wallet

The wallet reproduces oyster's key derivation byte for byte:

* BIP-39 seed (empty passphrase) → BIP-32 master (`"Bitcoin seed"`), the same `bip39.NewSeed(mnemonic, "")` oyster's `--createfromfile` uses.
* Internal key: `m/86'/808276'/0'/branch/index` (testnet coin type `1`), with btcd's `DeriveNonStandard` quirk (leading zero bytes stripped from private keys before hardened derivation).
* **Standard address** (default): plain BIP-86 key path, output key = internal key tweaked with no script tree. This is what oyster's `getnewaddress` / `getrawchangeaddress` return unless the PQ flag is passed, so it is what the desktop wallet shows, uses for change, and finds on recovery. PocketPRL hands out and spends from these by default.
* **Post-quantum variant** of the same index: `m/222'/808276'/0'/branch/index` → HKDF-SHA256(`XMSS-SEED-EXPANSION`) → XMSS-SHAKE256_5_256 keygen → tapleaf `<xmss_pk> OP_CHECKXMSSSIG` committed into the tweak. Derived and watched for every index (and shown on request in Receive) so coins sent to it stay visible and spendable. The desktop wallet does not derive this variant.
* Spends use the BIP-341 key path (Schnorr) for both variants, exactly like oyster's `ComputeInputScript`.

`tools/vectors/gen-vectors.sh` regenerates `app/src/test/resources/pearl_vectors.json`
by running a test inside the Pearl monorepo (real `waddrmgr`, `txscript`, and the
C XMSS library). `ConformanceTest` asserts both address variants, tapscript roots, sighashes,
tweaked keys and txids against those vectors (the generator opens one oyster wallet
with `usePQ=true` and one with `usePQ=false` from the same seed), and verifies
oyster's own signatures with the app's sighash implementation. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Security model

Read [docs/SECURITY.md](docs/SECURITY.md) before trusting the app with real funds.
Short version: keys never leave the device; the indexer only ever sees addresses
and signed transactions; the password is the only thing standing between a stolen
phone and your coins, so pick a real one.

## File structure

```
app/src/main/kotlin/dev/pocketprl/
  core/crypto/   SHAKE256, BIP-39, BIP-32 (non-standard), XMSS keygen, Taproot, bech32m, libsecp256k1 wrapper
  core/chain/    network params, addresses, amounts, tx model, BIP-341 sighash/signing, coin selection
  core/wallet/   WalletKeys: seed → addresses exactly like oyster
  data/          Blockbook client, SQLite cache, encrypted KeyVault, Session, Settings, WalletRepository (sync engine),
                 WalletRegistry + WalletContext (one vault/db/session per wallet), price feed,
                 background payment check (JobScheduler + notifications)
  ui/            Compose screens, view models, biometrics, QR, CSV export
app/src/test/    conformance + unit tests (pearl_vectors.json)
tools/vectors/   Go generator that produces the vectors from the Pearl source tree
```

## Status

Version 1.0.0. Key derivation and signing are verified byte-for-byte against the
Pearl reference code; sync and sending are exercised against the public Blockbook
indexer. Not independently audited (yet). Not affiliated with Pearl Research Labs.

License: ISC. Bundled fonts, icons and libraries are listed in [THIRD_PARTY.md](THIRD_PARTY.md).
