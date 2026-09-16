# Security model

## Threats considered

| Threat | Mitigation |
|--------|------------|
| Phone stolen, locked | Seed encrypted under a random DEK; DEK wrapped by PBKDF2-HMAC-SHA512 (200,000 iterations, 16-byte salt) of the wallet password, AES-256-GCM with AAD. Optional second wrap by an Android Keystore AES key that requires BIOMETRIC_STRONG per use (`setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`), invalidated on biometric enrolment changes, StrongBox-backed when the device has it. |
| Phone stolen, unlocked app | Auto-lock after a configurable idle time, in the background or on screen (default 5 min). Payments re-authenticate (biometric or password) by default. |
| OS / cloud backup leakage | `allowBackup=false`, full data-extraction rules exclude everything. |
| Saved-instance-state leakage | Passwords and recovery phrases are held in plain `remember` state, never `rememberSaveable`, so they are never written into the Bundle the system keeps for the activity. |
| Shoulder surfing / screenshots | `FLAG_SECURE` (reference-counted, so overlapping screens never clear it early) on seed display, seed verification, restore, reveal-seed and change-password screens. Balance hiding toggle that also masks amounts in notifications. |
| Clipboard sniffing | Recovery phrase is never put on the clipboard. Addresses are copied with the sensitive flag where supported. |
| Malicious or compromised indexer | HTTPS enforced (plain `http://` URLs are rejected). The indexer cannot spend funds: it only sees addresses and already-signed transactions. It *can* lie about balances/history or refuse to broadcast; a custom Blockbook URL lets you run your own. Broadcast responses are cross-checked against the locally computed txid. BIP-341 signatures commit to input amounts, so a wrong amount from the indexer makes the transaction invalid rather than overpaying. |
| Losing coins to an address the desktop cannot see | Default receive and change addresses are the plain BIP-86 variant the desktop wallet derives and recovers. The XMSS-committed variant is still watched and spendable, and is only handed out from Receive behind an explicit warning. |
| Wrong-network sends | Address HRP is validated against the wallet's network; testnet addresses are rejected on mainnet and vice versa. Payment links for the other network are refused with a message. |
| Malicious QR codes / links | Every parser (bech32, `pearl:` URIs, amounts) is total: malformed input yields "invalid", never an exception. Labels are truncated. |
| Fee griefing | Fee rate is bounded below by the relay minimum and shown in grain/vB; custom rates are user-entered. |
| Bugs in home-grown crypto | secp256k1 arithmetic and Schnorr signing use libsecp256k1 (via secp256k1-kmp). SHAKE256, XMSS keygen, BIP-32/39 and BIP-341 hashing are implemented in Kotlin and verified byte-for-byte against the Pearl reference implementation (see ARCHITECTURE.md). Every signature is self-verified before broadcast. |

## Multiple wallets

Each wallet has its own vault file, password, data-encryption key, database and
Android Keystore alias; nothing is shared between wallets. Only one wallet is
unlocked at a time: switching wallets locks the previous one, and auto-lock
locks every open session. Deleting a wallet removes only that wallet's files
and Keystore entry.

## Background payment check

When enabled in Settings, a JobScheduler job runs about every minute while the
phone is awake (Doze defers it) and performs the same sync the foreground app
does, using only the public address list in the local database. It never needs
the password, never touches the vault or the Keystore key, and only contacts the
configured Blockbook host. New incoming transactions are surfaced as a
notification; with "Hide balances" on, the notification carries no amount and
is marked private for the lock screen.

## Price feed

With "Show USD value" on (default, mainnet only) the app queries public market
APIs (CoinPaprika, CoinGecko, CoinEx, in that order). Only the app's
user-agent is sent; no addresses, balances or identifiers. Turn it off in
Settings if you don't want those hosts to see your IP.

## Not covered

* A rooted / malware-infested device can read process memory. No mobile wallet
  survives that.
* Side channels on the JVM (BigInteger / HMAC timing). Private-key arithmetic is
  in libsecp256k1, but PBKDF2 and BIP-32 HMACs run in Kotlin. Remote exploitation
  of these on a phone is not a realistic threat for this design.
* The phrase is only as safe as where you write it down.
* Certificate pinning is not used (the indexer's certificates rotate); the system
  trust store applies.
* Contacts and transaction notes are stored in plaintext in the app's private
  database. They are not secrets, but they are personal; deleting the wallet
  removes them.

## Residual data

* `filesDir/wallets.json` – list of wallets (names, networks, file names). No secrets.
* `filesDir/vault.json`, `filesDir/vault-<id>.json` – encrypted seed material,
  one per wallet (deleted with best-effort overwrite on wallet deletion).
* `databases/wallet.db`, `databases/wallet-<id>.db` – public addresses,
  transactions, UTXOs, contacts, notes, one per wallet.
* `shared_prefs/pocketprl.settings.xml` – non-secret preferences.
* `cacheDir/exports/` – the most recent CSV export, if you used that feature
  (shared through a FileProvider scoped to that directory only).

## Reporting

Please report security issues privately to the maintainers before disclosure.
