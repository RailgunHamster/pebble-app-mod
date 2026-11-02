package coredevices.pebble.ui

import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import coredevices.util.localization.appText
import io.rebble.libpebblecommon.SystemAppIDs
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.APP_LIST_UUID
import io.rebble.libpebblecommon.database.entity.HomeSwipeDirection
import io.rebble.libpebblecommon.database.entity.HomeSwipeSettings
import io.rebble.libpebblecommon.database.entity.QuickLaunchSetting
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

internal fun homeSwipePrefs(
    item: WatchPreference<HomeSwipeSettings>,
    libPebble: LibPebble,
    options: List<QuickLaunchOption>,
    scope: CoroutineScope,
): List<SettingsItem> {
    val settings = item.value ?: return listOf(SettingsItem(
        id = "homeSwipes.pending",
        title = appText("Watchface swipe actions"),
        topLevelType = TopLevelType.Watch,
        section = Section.QuickLaunch,
        isDebugSetting = false,
        item = { ListItem(
            headlineContent = { Text(appText("Watchface swipe actions")) },
            supportingContent = { Text(appText("Connect your watch to read its swipe settings. Requires watchface swipe support.")) },
        ) },
    ))
    val builtIns = listOf(
        QuickLaunchOption(APP_LIST_UUID, "App list"),
        QuickLaunchOption(SystemAppIDs.SETTINGS_APP_UUID, "Settings"),
        QuickLaunchOption(SystemAppIDs.MUSIC_APP_UUID, "Music"),
        QuickLaunchOption(SystemAppIDs.NOTIFICATIONS_APP_UUID, "Notifications"),
        QuickLaunchOption(SystemAppIDs.ALARMS_APP_UUID, "Alarms"),
        QuickLaunchOption(SystemAppIDs.CALENDAR_APP_UUID, "Calendar"),
        QuickLaunchOption(SystemAppIDs.WEATHER_APP_UUID, "Weather"),
        QuickLaunchOption(SystemAppIDs.HEALTH_APP_UUID, "Health"),
        QuickLaunchOption(SystemAppIDs.WORKOUT_APP_UUID, "Workouts"),
    )
    val choices = (builtIns + options).distinctBy { it.uuid }
    val systemActions = builtIns.mapNotNull { it.uuid }.toSet() + setOf(SystemAppIDs.QUIET_TIME_TOGGLE_UUID,
        SystemAppIDs.BACKLIGHT_UUID, SystemAppIDs.MOTION_BACKLIGHT_UUID,
        SystemAppIDs.AIRPLANE_MODE_UUID, SystemAppIDs.TOUCH_TOGGLE_UUID,
        SystemAppIDs.TIMELINE_PAST_UUID, SystemAppIDs.TIMELINE_FUTURE_UUID,
        SystemAppIDs.CLEAR_NOTIFICATIONS_UUID)
    return HomeSwipeDirection.entries.map { direction ->
        val action = settings[direction]
        val selected = if (!action.enabled) choices.first { it.uuid == null }
            else choices.firstOrNull { it.uuid == action.uuid }
                ?: QuickLaunchOption(action.uuid, appText("Unavailable app") + " (${action.uuid.toString().take(8)})")
        basicSettingsDropdownItem(
            id = "homeSwipes.${direction.name}",
            title = appText(direction.label),
            topLevelType = TopLevelType.Watch,
            section = Section.QuickLaunch,
            items = if (selected in choices) choices else choices + selected,
            selectedItem = selected,
            onItemSelected = { choice -> scope.launch {
                libPebble.setHomeSwipe(direction, QuickLaunchSetting(choice.uuid != null, choice.uuid))
            } },
            itemText = { option -> if (option.uuid == null || option.uuid in systemActions) appText(option.displayName) else option.displayName },
        )
    }
}
