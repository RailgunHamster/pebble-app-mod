package io.rebble.libpebblecommon.packets

import kotlin.test.*
import kotlin.uuid.Uuid

class ContinuousVoiceTest {
    @Test fun finalChunksFitTheWatchAndKeepArbitraryUtf8Boundaries() {
        val bytes="中文😀\n".repeat(400).encodeToByteArray()
        val restored=mutableListOf<Byte>()
        for(offset in bytes.indices step 512) {
            val data=bytes.copyOfRange(offset,minOf(offset+512,bytes.size))
            val packet=LiveDictationFinalChunk(0x1234u,offset,bytes.size,data,Uuid.NIL)
            val payload=packet.serialize().drop(4)
            assertTrue(payload.size<=541)
            assertEquals(6u.toUByte(),payload[0])
            assertEquals(0x34u.toUByte(),payload[5])
            restored+=payload.takeLast(data.size).map {it.toByte()}
        }
        assertContentEquals(bytes,restored.toByteArray())
    }
    @Test fun legacyResultKeepsItsOriginalCommand() {
        val packet=DictationResult(1u,Result.Success,emptyList()).serialize()
        assertEquals(2u.toUByte(),packet[4])
    }
}
