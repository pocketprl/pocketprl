# Architecture

## Why a light client over Blockbook

The official desktop wallet is an Electron shell around `oyster`, a Go daemon that
syncs the chain over SPV (neutrino-style compact block filters) and exposes JSON-RPC.
Embedding that on Android would mean shipping the Go runtime, the neutrino stack and
the C XMSS library via gomobile: tens of megabytes, minutes of header sync, and a
battery-hungry P2P connection. The desktop wallet itself already relies on Pearl
Research Labs' public Blockbook indexer for fee estimates.

PocketPRL therefore uses Blockbook (`https://blockbook.pearlresearch.ai`,
`https://blockbook.testnet.pearlresearch.ai`) for everything network-related:

| Need | Endpoint |
|------|----------|
| tip height / health | `GET /api/` |
| per-address history + balance | `GET /api/v2/address/{addr}?details=txslight&page=N` |
| spendable outputs | `GET /api/v2/utxo/{addr}?confirmed=false` |
| fee estimate (PRL/kB) | `GET /api/v2/estimatefee/{blocks}` |
| broadcast | `POST /api/v2/sendtx/` (raw hex body) |

Everything cryptographic stays on the phone.

## Key derivation (must match oyster)

```
mnemonic ──PBKDF2──► seed ──HMAC-SHA512("Bitcoin seed")──► master
master/86'/coin'/0'/b/i  ──► internal Schnorr key P

standard (variant 0, desktop default):
Q0     = lift_x(P) + TaggedHash("TapTweak", x(P))·G
addr0  = bech32m(hrp, 1, x(Q0))

post-quantum (variant 1):
master/222'/coin'/0'/b/i ──► pq private key k
HKDF-SHA256(ikm=k, salt=∅, info="XMSS-SEED-EXPANSION", 96) = privSeed(64) || pubSeed(32)
XMSS-SHAKE256_5_256.keygen(privSeed, pubSeed) = root(32) || pubSeed(32) = xmss_pk
leaf   = 0xc0 || compact(66) || (OP_DATA_64 xmss_pk OP_CHECKXMSSSIG)
root   = TaggedHash("TapLeaf", leaf)
Q1     = lift_x(P) + TaggedHash("TapTweak", x(P) || root)·G
addr1  = bech32m(hrp, 1, x(Q1))
```

Both variants live on the same index and share the private key; only the
tweak differs. oyster decides per call: `getnewaddress [account] [type] [pq]`
and `getrawchangeaddress` default `pq` to false, the desktop app never passes
it, and the recovery scan (`ExtendExternalAddresses(…, false)`) only derives
variant 0. PocketPRL therefore shows and spends from variant 0 by default and
keeps variant 1 in the watch set (two rows per index in `addresses`). Sending
to variant 1 is possible from the Receive screen behind an explicit warning.

Two non-obvious details:

1. **`DeriveNonStandard`.** btcd's hdkeychain stores derived private keys as
   minimal big-endian integers. For hardened children it copies those bytes after
   the `0x00` prefix and leaves the remainder of the 33-byte field zero. Whenever
   a parent key starts with a zero byte (about 1 in 256 per level) this differs
   from BIP-32. `ExtKey.deriveNonStandard` reproduces it; the vector file contains
   three seeds engineered to hit the quirk at the purpose', coin' and account' levels.
2. **XMSS is keygen-only.** The XMSS leaf is a post-quantum *fallback* spend path.
   oyster spends via the Schnorr key path, tweaking the private key with the
   tapscript root (`txscript.TweakTaprootPrivKey`). PocketPRL does the same, so it
   never needs XMSS signing state. Keygen runs 32 WOTS+ leaves in parallel; on a
   desktop JVM it takes ~60 ms per address.

## Multiple wallets

`WalletRegistry` (`filesDir/wallets.json`) lists the wallets on the device and
which one is active. Each entry owns its own file names: `vault-<id>.json`,
`wallet-<id>.db` and a Keystore alias `pocketprl.bio.dek.<id>`, so wallets never
share key material, biometric wraps or chain state. An install with the
single-wallet layout (`vault.json` + `wallet.db`) is registered in place as the
`default` wallet on first launch; nothing is moved or re-encrypted.

`AppContainer` opens one `WalletContext` (vault, session, database, indexer
client, repository, coroutine scope) per wallet on demand and keeps it open, so
the background payment check can sync every wallet while the UI only ever shows
the active one. Switching, adding or deleting a wallet changes the active id;
`AppNav` keys the whole navigation tree and every view model on that id, which
tears the UI down and rebuilds it for the new wallet with a fresh back stack.
The wallet being switched away from is locked first: exactly one session holds
a DEK at any time, and auto-lock locks all open sessions.

## Wallet state

`WalletDb` (plain SQLite in WAL mode, no ORM) stores only public data:

* `addresses` – branch/index/variant, address, scriptPubKey, internal key,
  tapscript root (empty for variant 0), usage stats. Derived ahead of time
  (lookahead) so the app can show a receive address or scan for funds without
  touching the seed. An index counts as used when either variant is.
* `txs` – one row per transaction touching the wallet, classified as
  RECEIVED / SENT / SELF / MINED with net amounts and a counterparty address.
* `utxos` – spendable outputs, refreshed from Blockbook per used address.
* `meta` – tip height, last sync time.
* `contacts` – user-named addresses.
* `tx_notes` – per-txid memo, kept separate from `txs` so sync upserts never
  overwrite it.

`WalletRepository.sync()` is the engine:

1. Fetch page 1 (50 items) of every address in scope concurrently (4 in flight),
   retrying transient indexer errors with backoff. An address that still fails
   is skipped for this round and reported in the sync line; the sweep goes on.
   A quick sync covers the addresses the wallet uses plus a short lookahead;
   the first sync, an explicit rescan, any sync that derives new addresses and
   one sync every 30 minutes sweep the whole gap window instead.
   Page 1 always carries every mempool transaction plus the newest confirmed
   ones, so it catches new payments and confirmations. If Blockbook reports
   more new confirmed transactions than page 1 held, the history is walked
   again from page 1 with 500-item pages. Page sizes are never mixed within a
   walk because Blockbook paginates by `page × pageSize`.
2. An address counts as used as soon as it has confirmed *or* unconfirmed
   activity, so the receive address rotates the moment a payment is seen.
3. Extend the lookahead when a used address gets close to the end of the derived
   window (gap 50 external / 20 internal, above what a Receive-page-happy desktop
   user burns) and repeat until nothing new is found.
4. Locally-pending transactions that no address reports any more (dropped or
   replaced in the mempool) are removed after a 20-minute grace period.
5. Refresh UTXOs for addresses with a non-zero balance.

Every local change bumps `WalletSnapshot.revision`; screens that hold derived
lists (activity, addresses, contacts) re-query on that key.

### Address lookahead: why 18 addresses, then 51

`ensureLookahead` keeps a window of *unused* addresses derived past the last
used index on each branch. The window has two sizes:

* Fresh wallet, nothing used yet: 12 receive + 6 change = 18 addresses. Enough
  to show a receive address instantly and to catch the first few payments.
* As soon as any address on a branch is used: the full discovery gap, 50
  receive / 20 change *beyond the last used index*. After the first payment to
  receive address #1 that means 51 receive addresses; the change branch stays
  at 6 until a change output is created.

This is PocketPRL's choice, not a Pearl consensus rule, and it is purely local:
derivation is public-key work (XMSS keygen, a few hundred milliseconds per
address on a phone, run in the background and shown in the sync line) and the
chain never sees an address until it receives coins. The reason for the big
window is the desktop wallet: oyster (btcwallet-derived) hands out a fresh
address every time the Receive page is opened and, on recovery, extends its
own derived set up to the highest index it finds on-chain. Someone who uses
the same phrase on both wallets can easily burn 20+ receive indices on the
desktop; a smaller window here would silently miss those payments. Once the
first address is used the wallet is "in use" and the discovery window is
kept full so that scenario keeps working without a restore.

Polling: while the app is on screen the dashboard checks `/api/` every 15 s and
only runs a sync when the tip changed or 90 s passed. Pull-to-refresh and a
successful send trigger one too. Polling stops when the app leaves the screen
and a fresh sync runs the moment it comes back.

### Background payment check

`PaymentCheckJob` is a `JobService` with a network constraint (opt-in).
JobScheduler refuses periodic jobs under 15 minutes, so it is a one-shot job
that re-arms itself about a minute after each run; Doze still defers it. It runs
the same `sync()` (which needs no unlock: address derivation is skipped while
locked) for every wallet except the one currently unlocked on screen, which the
dashboard already polls. Whichever sync discovers an incoming RECEIVED / MINED
transaction reports it through the repository, and `AppContainer` raises one
notification per transaction: once when it is first seen, usually in the
mempool, and again when it confirms, the second replacing the first.

### Payment links

`pearl:<address>?amount=…&label=…` (also `prl:`) is registered as a
`BROWSABLE` intent filter. `MainActivity` parks the URI in
`AppContainer.pendingPaymentUri`; `AppNav` opens Send once the wallet is
unlocked and `SendScreen` consumes it. The Receive screen produces the same
URIs, so two PocketPRL users can settle by sharing a link.

### Mining summary

`WalletSnapshot.mining` aggregates MINED rows: block count, total, last 7 days,
immature amount and the number of blocks until the oldest immature reward
matures (`CoinbaseMaturity = 100`, `TargetTimePerBlock = 3 m 14 s` from
`chaincfg`). It drives the rewards card on the dashboard.

## Sending

`TxBuilder` mirrors oyster's `txrules`: fee = ⌈vsize × rate / 1000⌉ with a
1000 grain/kB floor, key-path P2TR input weight 41×4+66, change dropped when
≤ 330 grain. Inputs: confirmed first, largest first. Signing derives the internal
private key for each input on demand, tweaks it with the stored tapscript root,
signs BIP-341 `SIGHASH_DEFAULT`, self-verifies against the on-chain output key
and wipes the key. After broadcast the local DB is updated optimistically and a
sync is scheduled.

## Secrets

See SECURITY.md. In short: `KeyVault` keeps the seed material in
`filesDir/vault.json` encrypted with a random DEK; the DEK is wrapped by a
password-derived key and optionally by a biometric-gated Keystore key. `Session`
holds the DEK in memory only while unlocked; seeds and account keys are
materialised per operation and wiped.

## Conformance vectors

`tools/vectors/pocketprl_vectors_test.go` is copied into the Pearl monorepo's
`wallet/wallet` package and executed with `go test -tags xmss`. It creates real
wallets with `Loader.CreateNewWallet`, one with `usePQ=true` and one with
`usePQ=false` from the same seed, derives addresses through `waddrmgr`
(including the PQ scope), signs spends from both variants with
`txscript.RawTxInTaprootSignature`, runs them through the script engine, and
dumps everything to JSON. The Kotlin tests then reproduce every value.
