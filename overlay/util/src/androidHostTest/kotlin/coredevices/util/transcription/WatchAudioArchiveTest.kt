package coredevices.util.transcription

import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchAudioArchiveTest {
    @Test fun cancelledRecordingPreservesExactPcmAndFailedEncodedFrames() {
        val root = Files.createTempDirectory("watch-audio-test-").toFile()
        try {
            val id = "dictation-1-abcdef"
            val writer = AndroidWatchAudioRecording(root, WatchAudioInfo(id, 1, 16000, 320, "Speex"))
            val pcm = byteArrayOf(1, 0, -1, 127, 0, -128, 0, 0)
            writer.append(byteArrayOf(2, 3, 4), pcm)
            writer.append(byteArrayOf(5), null)
            writer.finish("cancelled")
            writer.finish("finished") // Closing twice must not destroy a valid recording.
            val wav = root.resolve("$id.wav").readBytes()
            assertContentEquals(watchAudioWavHeader(16000, pcm.size), wav.copyOfRange(0, 44))
            assertContentEquals(pcm, wav.copyOfRange(44, wav.size))
            val info = Json.decodeFromString<WatchAudioInfo>(root.resolve("$id.json").readText())
            assertEquals(2L, info.frames)
            assertEquals(1L, info.failedFrames)
            assertEquals(32768, info.peak)
            assertEquals(2L, info.clippedSamples)
            assertEquals("cancelled", info.outcome)
            DataInputStream(root.resolve("$id.speexframes").inputStream()).use {
                assertEquals(3, it.readInt()); assertContentEquals(byteArrayOf(2, 3, 4), ByteArray(3).also(it::readFully))
                assertEquals(1, it.readInt()); assertEquals(5, it.read())
                assertEquals(-1, it.read())
            }
        } finally { root.listFiles().orEmpty().forEach { it.delete() }; root.delete() }
    }

    @Test fun recordingDurationIsBoundedWithoutBufferingWholeAudio() {
        val root = Files.createTempDirectory("watch-audio-limit-").toFile()
        try {
            val id = "dictation-1-abcdef"
            val writer = AndroidWatchAudioRecording(root, WatchAudioInfo(id, 1, 8000, 160, "Speex"))
            repeat(301) { writer.append(byteArrayOf(1), ByteArray(16000)) }
            writer.finish("saved")
            val info = Json.decodeFromString<WatchAudioInfo>(root.resolve("$id.json").readText())
            assertEquals(300.0, info.seconds)
            assertEquals("limit", info.outcome)
            assertEquals(44 + 8000 * 2 * 300L, root.resolve("$id.wav").length())
            assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".part") })
        } finally { root.listFiles().orEmpty().forEach { it.delete() }; root.delete() }
    }
}
