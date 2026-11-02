package coredevices.util.transcription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WatchAudioWavTest {
    @Test fun standardPcmHeaderHasCorrectLengthsRateAndFormat() {
        val header = watchAudioWavHeader(16000, 6400)
        fun number(offset: Int, count: Int) = (0 until count).fold(0) { value, i ->
            value or ((header[offset + i].toInt() and 255) shl (i * 8))
        }
        assertEquals(44, header.size)
        assertEquals("RIFF", header.decodeToString(0, 4))
        assertEquals("WAVE", header.decodeToString(8, 12))
        assertEquals(6436, number(4, 4))
        assertEquals(1, number(20, 2))
        assertEquals(1, number(22, 2))
        assertEquals(16000, number(24, 4))
        assertEquals(32000, number(28, 4))
        assertEquals(2, number(32, 2))
        assertEquals(16, number(34, 2))
        assertEquals("data", header.decodeToString(36, 40))
        assertEquals(6400, number(40, 4))
    }
    @Test fun invalidPcmCannotProduceMisleadingHeader() {
        assertFailsWith<IllegalArgumentException> { watchAudioWavHeader(16000, 3) }
        assertFailsWith<IllegalArgumentException> { watchAudioWavHeader(0, 4) }
    }
}
