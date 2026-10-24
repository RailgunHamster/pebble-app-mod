package coredevices.pebble.ui

import PlatformUiContext

internal actual fun openModBackupSettings(uiContext: PlatformUiContext?, checkUpdates: Boolean) {
    // The existing personal-build backup/update activity is Android-only;
    // its settings entries are shown only on Android.
}
