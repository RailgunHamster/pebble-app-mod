package io.rebble.libpebblecommon.database.entity

import androidx.compose.runtime.Immutable
import io.rebble.libpebblecommon.util.DataBuffer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

enum class HomeSwipeDirection(val label: String) {
    Up("Watchface: Swipe up"), Down("Watchface: Swipe down"),
    Left("Watchface: Swipe left"), Right("Watchface: Swipe right"),
}

private val homeSwipeJson = Json { ignoreUnknownKeys = true }
private val noApp = Uuid.parse("ffffffff-ffff-ffff-ffff-ffffffffffff")
val APP_LIST_UUID: Uuid = Uuid.parse("dec0424c-0625-4878-b1f2-147e57e83688")

@Immutable
@Serializable
data class HomeSwipeSettings(
    val up: QuickLaunchSetting = QuickLaunchSetting(false, null),
    val down: QuickLaunchSetting = QuickLaunchSetting(false, null),
    val left: QuickLaunchSetting = QuickLaunchSetting(false, null),
    val right: QuickLaunchSetting = QuickLaunchSetting(true, APP_LIST_UUID),
) {
    operator fun get(direction: HomeSwipeDirection): QuickLaunchSetting = when (direction) {
        HomeSwipeDirection.Up -> up
        HomeSwipeDirection.Down -> down
        HomeSwipeDirection.Left -> left
        HomeSwipeDirection.Right -> right
    }

    fun updated(direction: HomeSwipeDirection, action: QuickLaunchSetting): HomeSwipeSettings = when (direction) {
        HomeSwipeDirection.Up -> copy(up = action)
        HomeSwipeDirection.Down -> copy(down = action)
        HomeSwipeDirection.Left -> copy(left = action)
        HomeSwipeDirection.Right -> copy(right = action)
    }

    fun toJson(): String = homeSwipeJson.encodeToString(this)

    fun toBytes(): UByteArray = HomeSwipeDirection.entries.fold(ubyteArrayOf()) { bytes, direction ->
        val action = this[direction]
        bytes + QLMappable(action.enabled, action.uuid).toBytes()
    }

    companion object {
        fun fromJson(value: String): HomeSwipeSettings = homeSwipeJson.decodeFromString(value)

        fun fromBytes(value: UByteArray): HomeSwipeSettings {
            require(value.size == 4 * 17) { "Invalid home swipe settings length" }
            var result = HomeSwipeSettings()
            HomeSwipeDirection.entries.forEachIndexed { index, direction ->
                val record = QLMappable().apply { fromBytes(DataBuffer(value.copyOfRange(index * 17, (index + 1) * 17))) }
                result = result.updated(direction, QuickLaunchSetting(record.enabled.get(), record.uuid.get().takeUnless { it == noApp }))
            }
            return result
        }
    }
}

enum class HomeSwipeWatchPref : WatchPref<HomeSwipeSettings> {
    HomeSwipes;

    override val id = "homeSwipes"
    override val displayName = "Watchface swipe actions"
    override val description: String? = null
    override val isDebugSetting = false
    override val type = WatchPrefType.TypeHomeSwipes
    override val defaultValue = HomeSwipeSettings()
    override fun decodeValue(value: String): HomeSwipeSettings = HomeSwipeSettings.fromJson(value)
    override fun encodeValue(value: HomeSwipeSettings): String = value.toJson()
}

@Serializable
data class HomeSwipeSettingsSyncState(val refreshedIdentifiers: Set<String> = emptySet()) {
    fun needsRefresh(identifier: String): Boolean = identifier !in refreshedIdentifiers
}
