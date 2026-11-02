package io.rebble.libpebblecommon.database.entity

import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.DevicesWhichHaveSyncedSettings
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class HomeSwipeSettingsTest {
    private val claw = Uuid.parse("12016385-5beb-468e-8782-8461ade67159")
    private fun hex(value: String): UByteArray = value.chunked(2).map { it.toUByte(16) }.toUByteArray()

    @Test
    fun firmwareGoldenVectorUsesUpDownLeftRightPackedRecords() {
        val bytes = hex("00ffffffffffffffffffffffffffffffff" +
            "00ffffffffffffffffffffffffffffffff" +
            "01120163855beb468e87828461ade67159" +
            "01dec0424c06254878b1f2147e57e83688")
        val value = HomeSwipeSettings.fromBytes(bytes)
        assertFalse(value.up.enabled)
        assertFalse(value.down.enabled)
        assertEquals(claw, value.left.uuid)
        assertEquals(APP_LIST_UUID, value.right.uuid)
        assertContentEquals(bytes, value.toBytes())
        assertEquals(value, HomeSwipeSettings.fromJson(value.toJson()))
        assertEquals("homeSwipes", HomeSwipeWatchPref.HomeSwipes.id)
        assertEquals(HomeSwipeWatchPref.HomeSwipes, WatchPref.from("homeSwipes"))
    }

    @Test
    fun editsPreserveOtherDirectionsIncludingUnknownApplications() {
        val unknown = Uuid.parse("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
        val initial = HomeSwipeSettings(left = QuickLaunchSetting(true, unknown))
        val first = initial.updated(HomeSwipeDirection.Up, QuickLaunchSetting(true, claw))
        val second = first.updated(HomeSwipeDirection.Down, QuickLaunchSetting(true, APP_LIST_UUID))
        assertEquals(claw, second.up.uuid)
        assertEquals(APP_LIST_UUID, second.down.uuid)
        assertEquals(unknown, second.left.uuid)
        assertEquals(initial.right, second.right)
        val disabled = second.updated(HomeSwipeDirection.Up, QuickLaunchSetting(false, null))
        assertFalse(disabled.up.enabled)
        assertEquals(second.down, disabled.down)
        assertEquals(second.left, disabled.left)
    }

    @Test
    fun disabledUuidAndAllFourIndependentBindingsRoundTrip() {
        val value = HomeSwipeSettings(
            up = QuickLaunchSetting(false, claw), down = QuickLaunchSetting(true, claw),
            left = QuickLaunchSetting(true, APP_LIST_UUID), right = QuickLaunchSetting(false, null),
        )
        assertEquals(value, HomeSwipeSettings.fromBytes(value.toBytes()))
    }

    @Test
    fun truncatedOrExtendedRecordsAreRejected() {
        for (length in listOf(0, 17, 67, 69, 136)) {
            assertFailsWith<IllegalArgumentException> { HomeSwipeSettings.fromBytes(UByteArray(length)) }
        }
    }

    @Test
    fun upgradingThePhoneRequestsASettingsRefreshForAlreadyPairedWatches() {
        val previous = Json.decodeFromString<DevicesWhichHaveSyncedSettings>("{\"identifiers\":[\"old-watch\"]}")
        assertTrue("old-watch" in previous.identifiers)
        assertTrue(HomeSwipeSettingsSyncState().needsRefresh("old-watch"))
        val refreshed = HomeSwipeSettingsSyncState(setOf("old-watch"))
        val restored = Json.decodeFromString<HomeSwipeSettingsSyncState>(Json.encodeToString(refreshed))
        assertFalse(restored.needsRefresh("old-watch"))
        assertTrue(restored.needsRefresh("another-watch"))
    }
}
