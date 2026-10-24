package coredevices.pebble.ui

import PlatformUiContext

// Reuse the Android personal build's launcher-shortcut actions from settings.
internal expect fun openModBackupSettings(uiContext: PlatformUiContext?, checkUpdates: Boolean)
