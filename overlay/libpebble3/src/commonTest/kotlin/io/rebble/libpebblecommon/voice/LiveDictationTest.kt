package io.rebble.libpebblecommon.voice

import io.rebble.libpebblecommon.connection.endpointmanager.audio.AudioPcmDecoder
import io.rebble.libpebblecommon.packets.DictationResult
import io.rebble.libpebblecommon.packets.Result
import io.rebble.libpebblecommon.packets.VoiceCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveDictationTest {
    private fun pcm(amplitude: Int, milliseconds: Int = 20): ByteArray = ByteArray(16 * milliseconds * 2).also {
        for (i in 0 until it.size / 2) {
            val value = if (i % 2 == 0) amplitude else -amplitude
            it[2 * i] = value.toByte(); it[2 * i + 1] = (value shr 8).toByte()
        }
    }

    @Test fun silenceEndsAfterThreeSecondsWithoutInventingSpeech() {
        val detector = DictationActivity(16000)
        repeat(149) { assertFalse(detector.accept(pcm(30))) }
        assertTrue(detector.accept(pcm(30)))
        assertFalse(detector.speechDetected)
    }

    @Test fun endpointRequiresSpeechThenSevenHundredMillisecondsOfSilence() {
        val detector = DictationActivity(16000)
        repeat(30) { assertFalse(detector.accept(pcm(1600))) }
        assertTrue(detector.speechDetected)
        repeat(34) { assertFalse(detector.accept(pcm(0))) }
        assertTrue(detector.accept(pcm(0)))
    }

    @Test fun briefPauseDoesNotCutOffTheUtterance() {
        val detector = DictationActivity(16000)
        repeat(30) { detector.accept(pcm(1000)) }
        repeat(20) { assertFalse(detector.accept(pcm(0))) }
        repeat(20) { assertFalse(detector.accept(pcm(1000))) }
        repeat(34) { assertFalse(detector.accept(pcm(0))) }
        assertTrue(detector.accept(pcm(0)))
    }

    @Test fun packetCommandsKeepSessionIdAndLegacyResultEncoding() {
        for (command in listOf(VoiceCommand.DictationPartial, VoiceCommand.SpeechEnd, VoiceCommand.DictationResult)) {
            val packet = DictationResult(0x1234u, Result.Success, emptyList(), command).serialize().takeLast(9)
            assertEquals(listOf(command.value, 0u, 0u, 0u, 0u, 0x34u, 0x12u, 0u, 0u).map { it.toUByte() }, packet)
        }
    }

    @Test fun slowInferenceConflatesSnapshotsAndRecognizesTheWholeFinalClip() = runTest {
        val dec = object : AudioPcmDecoder {
            var closed = false
            override fun decode(frame: UByteArray) = frame.toByteArray()
            override fun close() { closed = true }
        }
        val requests = mutableListOf<Int>()
        val partials = mutableListOf<String>()
        var ended = 0
        val result = liveDictation(
            audioFrames = flow {
                repeat(150) { emit(pcm(1500).toUByteArray()); delay(20) }
                repeat(35) { emit(pcm(0).toUByteArray()); delay(20) }
            }, sampleRate = 16000, decoder = dec,
            recognize = { frames ->
                requests += frames.size
                delay(1000)
                TranscriptionResult.Success(listOf(TranscriptionWord("字数${frames.size}", 0.9f)))
            }, onPartial = { partials += it.single().word }, onSpeechEnd = { ended++ },
        )
        assertEquals(1, ended)
        assertTrue(dec.closed)
        assertTrue(partials.size >= 2)
        assertEquals(185, requests.last())
        assertTrue(requests.size <= 5, "Obsolete snapshots must be conflated: $requests")
        assertEquals("字数185", (result as TranscriptionResult.Success).words.single().word)
    }

    @Test fun cancellationClosesDecoderAndDoesNotSendAFinalOrStopMessage() = runTest {
        var closed = false
        var ended = false
        try {
            liveDictation(flow { throw CancellationException("cancelled") }, 16000,
                object : AudioPcmDecoder {
                    override fun decode(frame: UByteArray) = pcm(0)
                    override fun close() { closed = true }
                }, { TranscriptionResult.Failed }, {}, { ended = true })
        } catch (_: CancellationException) { }
        assertTrue(closed)
        assertFalse(ended)
    }
}
