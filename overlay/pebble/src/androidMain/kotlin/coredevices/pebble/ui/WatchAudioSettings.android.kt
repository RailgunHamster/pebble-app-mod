package coredevices.pebble.ui

import PlatformUiContext
import android.content.Intent

internal actual fun openWatchAudioRecordings(uiContext: PlatformUiContext?) {
    val activity = uiContext?.activity ?: return
    activity.startActivity(Intent().setClassName(activity.packageName, "coredevices.coreapp.mod.WatchAudioRecordingsActivity"))
}
