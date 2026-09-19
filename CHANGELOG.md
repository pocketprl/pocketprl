# Changelog

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
