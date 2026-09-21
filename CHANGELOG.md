# Changelog

## 2.5.1

* **Odometer reverted to the 1.0.0 motion.** The balance roll is the original
  implementation again — the random free-run pace per roller and the left-to-right
  settle — which reads smoother than the tuned version. The slot-machine digit
  haptics and the odometer haptics setting are kept as they were. Everything else
  is unchanged from 2.5.0.

## 2.5.0

* **Version switching.** Each release now ships up to three builds in three
  version-code lanes: primary, rollback and return ("back"). From a fresh install
  you can drop to any older version in place with your wallets kept. Installing any
  alternate build leaves the regular release line **for good**: normal updates stop
  installing, and you move among alternate builds until you reset (uninstall,
  reinstall, restore your recovery phrase). The app warns you before you commit.
* **You always know which build you are on.** Settings › About and the version
  history show whether you are on the regular, rollback or return build and what it
  can do, and every release in the list says whether it installs in place or needs
  the reset.
* The version screen is now called **Switch versions**, and it blocks a switch that
  cannot install over the running build *before* you commit: a red reason with a
  grayed control, instead of downloading and then failing.
* **Older-target warnings.** Switching to a build older than 2.2.0 warns that its
  updater cannot install, so moving forward is manual, and to anything older than
  2.4.0 warns that it has no version switcher — you won't see which build you are
  on or switch in-app until you are back on 2.4.0 or newer.
* New strings localized into all 18 supported languages.

## 2.4.1

* **Roomier onboarding.** The first-run personalization screen has more breathing
  room, a two-column theme picker so long names fit, smaller accent swatches, and
  a Security section with a biometric-unlock option that is set up once the wallet
  is created.
* **Slide to restore.** Restoring a wallet now uses the same slide-to-confirm
  control as sending, instead of a plain button.
* **Return-build handling.** A build installed above the downgrade band (a return
  build) cannot downgrade in place. The app now notices, explains it once on first
  launch, and offers to switch to the regular build; the same offer appears in the
  downgrade screen when a target will not install. Switching saves the regular
  build to Downloads first, because Android refuses a lower package version over
  this one and PocketPRL has to be uninstalled before reinstalling it.
* New strings localized into all 18 supported languages.

## 2.4.0

* **Version history and in-app downgrade.** About gains a Downgrade button under
  Check for updates; it opens a full-screen version history (down-arrow-beside-
  up-arrow hero) that lists every published GitHub release, marks the one you are
  on, and shows a release's notes before you commit to it. Picking a version runs
  the same verified download (SHA-256 + signature) and slide-to-confirm install as
  an update.
* **Per-release compatibility notes.** Each release states whether the on-disk
  wallets/contacts/notes are compatible (they are across 1.0.0 → 2.3.2, which the
  new `SchemaCompat` guard and tests pin down) and warns when a target predates
  the update checker (1.0.0), which then has to be moved forward by hand. A
  release whose asset cannot be installed in place is reported instead of being
  handed to a failing installer.
* **Downgrade band.** 2.4.0 and every older release are rebuilt with a version
  code in the `100000` range so Android will install them in place over a newer
  build; from 2.4.0 any earlier release installs, with wallets and history kept.
  See `docs/VERSIONING.md` and `tools/reissue-downgrade.sh`.
* **Downgrade warning and return build.** The version history now warns, with a
  red danger slide, that a downgrade is one-way inside the app. The v2.4.0
  release also carries `PocketPRL-2.4.0-return-100026.apk`, a build above the
  rollbacks for getting back; the updater picks the right asset for the installed
  version automatically.

## 2.3.2

* **Release notes render as Markdown.** The "what's new" screen now formats the
  GitHub release body properly — headings, bullet and numbered lists, block
  quotes, horizontal rules, fenced code, and inline bold / italic / code / links
  — instead of showing the raw markup.

## 2.3.1

* **Selectable app icon.** Settings › Appearance › App icon switches the launcher
  icon between the default, a monochrome mark that follows the system theme, and
  fixed light and dark variants. The choice is applied through activity-aliases
  and survives app updates.
* Settings spacing retuned: tighter category headers, a little more room between
  rows.

## 2.3.0

* **Transaction search.** Activity has a search field that matches an address,
  txid, contact name or note, on top of the existing filters.
* **Notification deep links.** Tapping an incoming-payment notification now opens
  that transaction directly once the wallet is unlocked, instead of the app root.
* **App shortcuts.** Long-press the launcher icon for Send, Receive and Scan,
  each with its own monochrome icon; Scan opens Send and pops the QR reader.
* **Balances in the wallet switcher.** Each wallet shows its balance (respecting
  Hide balances), so several wallets are easy to tell apart at a glance.
* **OLED black theme.** A true-black dark theme (pure #000000 surfaces) in both
  theme pickers, kept separate from the regular dark theme.
* **Update indicator.** A red dot appears on the dashboard's settings button and
  on the About row when a newer release is available.
* **What's new.** After an in-app update installs, the next unlock shows a
  full-screen summary of the release notes with a single Okay button.
* **Full-screen update flow.** Tapping Update now opens a full-screen screen in
  the same style as "what's new", and installing uses slide-to-confirm once the
  package has been downloaded and verified.
* New strings localized into all 18 supported languages.

## 2.2.1

* **Tighter settings.** The settings list rows and section cards use less
  padding, so more of the screen is content and less is air.
* **No clipped labels.** Buttons, the slide-to-confirm label and small captions
  now stay on a single line and ellipsize instead of being cut when a translation
  or a large font scale runs long; the slide label also keeps clear of the thumb.

This release exists to exercise the 2.2.0 in-app self-updater.

## 2.2.0

* **In-app updates.** "Check for updates" now downloads the release APK itself:
  it shows the file name and live KB progress, verifies GitHub's published
  SHA-256 and that the file is signed by the same key as the installed app, then
  opens the system installer. Installing needs the one-time "install unknown
  apps" grant, which the app offers to open for you. Releases without an
  attached APK still open the release page.
* **Brute-force pacing.** Wrong passwords are now rate-limited with a
  persisted, exponential lockout: three free tries, then a delay that starts at
  5 seconds and doubles per further failure, capped at 30 minutes. The counter
  lives in the wallet's vault file, so closing and reopening the app does not
  reset it. The unlock screen shows a live countdown and re-enables the field
  when it expires. Every password prompt is covered, not just unlock.
* **Optional wipe after 10 failed attempts.** Off by default; when enabled in
  Settings › Security, the tenth consecutive wrong password erases that wallet's
  keys and local history (other wallets are untouched, and the recovery phrase
  still restores it). When 3 or fewer attempts remain, the incorrect-password
  message warns how many are left.
* **Erase is now a fullscreen confirmation.** The old type-the-word dialog is
  replaced by a dedicated screen with a red trash mark, the warning, a field to
  type `ERASE`, and a red slide-to-erase control that arms only once the word
  matches. Reached from both the unlock screen ("forgot password") and Settings ›
  Danger zone.
* **Slide-to-confirm everywhere it matters.** The slide control is now one
  reusable element used for every consequential action, not just two: send,
  erase wallet, change wallet password and reveal recovery phrase all use it.
* The new security, erase and slider strings are translated into all 18
  supported languages.
* **Fix:** the custom fee rate and the "pay in fiat" field now decide their
  decimal separator the same careful way the amount field does. A comma typed as
  a thousands separator (en-US `1,000`) was being read as `1.0` PRL/kB, a 1000x
  fee underpay, and a grouped fiat entry silently became empty.
* **Fix:** a failed or cancelled biometric/password confirmation on Send no
  longer leaves the slider parked at the end; it springs back.

## 2.1.2

* Localized the strings added for the external payment-request warning, the
  send-auth requirement and the unreadable-vault error into all 18 supported
  languages, and translated the Dutch post-quantum address label.

## 2.1.1

* **Fix:** sync no longer fails on a very busy address with "response exceeds
  8388608 bytes". The indexer response ceiling is raised to 32 MiB and the
  history backfill walk now shrinks its page size instead of giving up when a
  single page is too large.
* Send is no longer screenshot-blocked; Unlock still is.

## 2.1.0

* **Security hardening** (unaudited wallet, pre-release review):
  * Fixed a locale-dependent amount-parsing bug where a comma typed as a decimal
    on an anglophone locale (grouping `,` / decimal `.`) was treated as thousands
    grouping, silently inflating a payment 10–1000x. Amounts now decide the
    decimal separator before stripping grouping.
  * Release builds no longer silently fall back to the debug signing key; a
    keystore is required (or `POCKETPRL_ALLOW_DEBUG_SIGNING=1` for throwaway local
    builds), otherwise the release APK is left unsigned.
  * Pinned the Gradle distribution checksum in the wrapper.
  * Vault writes are fsync'd and the staging file is removed on delete, so a crash
    can no longer leave a recoverable encrypted vault behind after "delete wallet".
  * Corrupt/unreadable vault files now show a clear error instead of crashing the UI.
  * Biometric keys invalidated by a new enrolment are cleared and reported instead
    of leaving the lock screen stuck.
  * Indexer responses are size-capped and the history backfill walk is bounded, so
    a hostile or broken indexer cannot OOM or hang the app.
  * Payments prefilled from an external link or QR now show an "external payment
    request" warning and the full destination address.
  * "Require authentication to send" is enforced in the view model, not just the UI.
  * Auto-lock's self-started-activity grace is bounded by the user's timeout.
  * `verify-release.sh` now fails when it cannot check the signing certificate.
  * Unlock and Send screens block screenshots/recents thumbnails.
* **On-chain stats.** A new screen under Settings › Wallet, right below Created,
  rolls up everything this wallet has ever done: total received and sent in PRL
  and your fiat, fees paid, net flow, first and last transaction, days active,
  busiest day, blocks mined, and records like the largest received/sent and the
  highest fee paid in a single transaction.
* **Charts.** Weekly inflow/outflow bars for the last twelve weeks and a
  cumulative balance curve over the whole history, drawn locally from the cached
  transactions. Both charts carry y-axis levels and respond to touch: press and
  drag to drop a crosshair on a bar or point and read its numbers.
* **Time machine.** With fiat value on, PocketPRL fetches a daily PRL price
  series (cached for six hours, last 365 days) and shows the biggest missed gain
  since a past send, the total missed across every sale that now sits below the
  current price, the average sell price against the all-time high since your
  first sale, and the current price.
* Stats and the price history are localized into all 18 supported languages.

## 2.0.1

* **Odometer performance.** The rolling digits no longer recompose and re-measure
  the cell on every animation frame: the counter is read only in the layer/draw
  phase, and the digit strip has static content that is simply translated.
* **Odometer spin.** The free-run used to chain tween legs at a randomly varying
  pace, so every second the velocity jumped and the motion blur flickered in and
  out, which read as the digits aligning and ticking instead of blurring. Each
  roller now holds one steady speed for the whole spin, matching the smooth look of
  the settle animation.
* **Background work no longer starves the UI.** XMSS lookahead keygen runs on a
  bounded, minimum-priority pool instead of the common ForkJoinPool, so a sync no
  longer pins every core while the dashboard is animating. Keygen output and
  timing-sensitive crypto are unchanged.

## 2.0.0

* **Localization.** Every user-facing string moved to resources; the app follows the
  system language. 18 European locales: German, Dutch, French, Italian, Spanish,
  Portuguese, Polish, Czech, Russian, Ukrainian, Swedish, Danish, Norwegian
  (Bokmål), Finnish, Greek, Turkish, Romanian, Hungarian. A per-app language
  picker is in Settings and in the first-run personalization step, and the
  languages are exposed to Android's per-app language settings.
* **Configurable numbers and currency.** Balance decimals (2/4/6/8), decimal
  separator, thousands grouping, and a fiat currency picker (21 currencies) that
  drives the price feed and every amount on screen. Defaults follow the system
  locale.
* **Appearance.** Seven accent themes plus Material You dynamic colour (Android
  12+), on top of the light/dark/auto modes, and a global Reduce motion switch.
* **First-run personalization.** A new onboarding step with a live, animated
  preview of the theme, accent, number format and currency.
* **Dashboard controls.** Choose total vs spendable as the big number, show or
  hide the 24h change chip, the mining rewards card, and the start-hidden
  behaviour, and pick the refresh rate (Live/Balanced/Battery saver).
* **Privacy.** Optional app-wide screenshot blocking.
* **Settings** reorganized into clear categories (Appearance, Numbers & currency,
  Dashboard, Security & privacy, Notifications, Network, Backup, About).
* Price feed, sync progress, notifications and validation errors are localized.

## 1.1.1

* **Price alerts.** Off by default. When enabled, PocketPRL checks the price in the background and notifies you when the 24 hour change reaches your threshold (5% by default, adjustable from 1% up). One alert per threshold crossed, in either direction.

## 1.1.0

* **Odometer haptics.** Every digit that rolls fires a tick, with a settle guard so the landing clicks once as the rollers stop left to right instead of buzzing through the correction wobble.
* **Odometer settings.** Turn the rolling digits off entirely, or keep the animation and silence the haptics. The haptics toggle grays out when the animation is off.
* **Check for updates.** About now checks the latest GitHub release and offers an Update button that opens the release page.
* **Source code link.** The About screen links straight to the PocketPRL repository.

## 1.0.0

First release.

* **Wallets.** Create a 12-word BIP-39 wallet or restore from a 12 to 24-word phrase or a raw hex seed. Any number of wallets on one phone, each with its own phrase, password, network, history, contacts and biometric key. Switch from the home-screen title or the unlock screen.
* **Same addresses as the desktop wallet.** Key derivation and signing match oyster byte for byte, verified against vectors generated from the Pearl reference code. Every index has the plain BIP-86 address the desktop uses and the post-quantum XMSS-committed variant; both are watched and spendable.
* **Send.** Address validation, QR scan, paste or contacts, fast/normal/slow/custom fee with block-time ETAs, MAX, USD hint. Slide to send; biometrics or the password confirm.
* **Receive.** QR code, copy and share, payment requests with amount and label, fresh-address rotation, post-quantum address on request.
* **Payment links.** `pearl:<address>?amount=…&label=…` links open Send prefilled from the browser, chat apps, the QR scanner or the clipboard. Receive produces them as QR and share link.
* **Contacts and notes.** Named addresses picked from a sheet on Send and shown instead of raw addresses in history. Per-transaction memo, exported with the history.
* **History.** Received, sent, self and mined classification, day grouping, filters, confirmations, fees, explorer links, coinbase maturity, CSV export.
* **Mining rewards card.** Last 7 days, all time, maturing amount and blocks until the next reward is spendable.
* **Incoming payment notifications.** Opt-in background check about every minute that notifies for new payments and block rewards without unlocking the wallet, once when spotted in the mempool and again when confirmed.
* **Security.** Android Keystore (StrongBox when available) biometric gating, auto-lock in the background and while idle, screenshot blocking on seed screens, hidden balances, no backups, HTTPS only.
* **Display.** Balances roll like an odometer, USD value and 24-hour price move (optional), light and dark themes, TalkBack support.
* **Networks.** Mainnet and Testnet2, custom Blockbook URL.
