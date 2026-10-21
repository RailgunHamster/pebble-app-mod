# pebble-app-mod

My personal version of the Core Devices Pebble app ([coredevices/mobileapp](https://github.com/coredevices/mobileapp), GPL-3.0). It is built from the newest upstream release with the patches in `patches/` applied in order.

| Patch | What it changes |
|---|---|
| `001-self-hosted-dictation` | Watch dictation goes to a self-hosted recognizer, reached through my relay, instead of WisprFlow, so Chinese works without foreign services. "Cloud Only" no longer needs a Core account. |
| `002-settings-backup-and-updates` | Long-press the app icon → 备份与更新. Export or import all settings as one zip; import restarts the app. Every 12 h the app checks the relay for a newer build and notifies. |

Adding a change: put `003-....patch` (a `git format-patch` against an upstream tag) in `patches/`, then push. The workflow (`.github/workflows/build.yml`) builds the APK, uploads it to the relay, and the app's update check offers it.

The repository holds no secrets. Tokens, the relay address and the signing key are Actions secrets (listed at the top of the workflow). The APK carries a token, so it is never published in the open: it goes to the relay only, plus a GitHub release if the repository is private.

## Phone

- **First install:** uninstall the official app (the signature differs), install the APK, pair the watch again, then Settings → Speech → **Cloud Only**.
- **Updates:** tap the notification and install over the old one. Settings stay.

No Firebase config is included, so features that need a Core Devices account (sign-in, cloud sync) don't work in this build.
