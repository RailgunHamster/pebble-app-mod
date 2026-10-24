package coredevices.pebble.ui

import PlatformUiContext
import android.content.Intent

internal actual fun openModBackupSettings(uiContext: PlatformUiContext?, checkUpdates: Boolean) {
    val activity = uiContext?.activity ?: return
    val action = if (checkUpdates) "coredevices.coreapp.mod.UPDATE" else Intent.ACTION_VIEW
    activity.startActivity(
        Intent(action).setClassName(activity.packageName, "coredevices.coreapp.mod.ModBackupActivity")
    )
}
