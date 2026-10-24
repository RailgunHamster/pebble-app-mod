package coredevices.util.transcription

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ContinuousDictationTest {
    @Test fun revisionsAndArrivalOrderDoNotDuplicateOrReorderText() {
        val t=DictationTranscript()
        assertTrue(t.update(1,10,"后一句",false))
        assertTrue(t.update(0,1000000,"前一句。",true))
        assertFalse(t.update(0,99,"迟到草稿",false))
        assertFalse(t.update(1,10,"重复包",false))
        assertTrue(t.update(1,1000000,"后一句。",true))
        assertEquals("前一句。后一句。",t.text())
    }

    @Test fun previewKeepsValidUtf8AndFullTranscriptRemainsUnchanged() {
        val original="第一段。".repeat(100)+"最后一段😀"
        val preview=dictationPreview(original)
        assertTrue(preview.encodeToByteArray().size<=480)
        assertTrue(preview.endsWith("最后一段😀"))
        assertFalse(preview.contains('\uFFFD'))
        assertFailsWith<IllegalArgumentException> { DictationTranscript().update(0,1,"中".repeat(3000),true) }
    }

    private fun pcm(voice: Boolean): ByteArray = ByteArray(640).also {
        if(voice) for(i in 0 until 320) {it[i*2]=0;it[i*2+1]=8}
    }

    @Test fun tenSecondPauseAllowsMoreSpeechAndFinalizesOnlyOnEnd() = runTest {
        val settings=MapSettings().apply { putBoolean(DICTATION_POLISH_KEY,false) }
        val previews=mutableListOf<String>();var ended=false
        val result=ContinuousDictation(settings).transcribe(
            pcm=flow {
                repeat(60) {emit(pcm(true));delay(20)}
                repeat(500) {emit(pcm(false));delay(20)}
                assertFalse(ended)
                repeat(60) {emit(pcm(true));delay(20)}
                ended=true
            },sampleRate=16000,language="zh",remote=false,
            recognize={"测试句。"},onPreview={previews+=it},
        )
        assertEquals("测试句。测试句。",result)
        assertEquals(result,settings.getString(DICTATION_ORIGINAL_KEY,""))
        assertTrue(previews.isNotEmpty())
    }

    @Test fun threeMinutesUseBoundedSegmentsAndRetainEverySegment() = runTest {
        val settings=MapSettings().apply { putBoolean(DICTATION_POLISH_KEY,false) }
        var largest=0
        val result=ContinuousDictation(settings).transcribe(
            pcm=flow { repeat(12) {repeat(750) {emit(pcm(true));delay(20)}} },
            sampleRate=16000,language="zh",remote=false,
            recognize={largest=maxOf(largest,it.size);"段。"},onPreview={},
        )
        assertTrue(largest<=16000*2*15)
        assertEquals("段。".repeat(12),result)
    }

    @Test fun cancellationDoesNotFinalizeOrPolish() = runTest {
        val settings=MapSettings().apply { putBoolean(DICTATION_POLISH_KEY,false) }
        var cancelled=false
        try {
            ContinuousDictation(settings).transcribe(flow {
                repeat(30){emit(pcm(true));delay(20)}
                throw CancellationException("Back pressed")
            },16000,"zh",false,{"不应发送"},{})
        } catch (_: CancellationException) {cancelled=true}
        assertTrue(cancelled)
        assertEquals("",settings.getString(DICTATION_ORIGINAL_KEY,""))
    }

    @Test fun noSpeechProducesNoInventedText() = runTest {
        val settings=MapSettings().apply {putBoolean(DICTATION_POLISH_KEY,false)}
        var calls=0
        val result=ContinuousDictation(settings).transcribe(flow {repeat(500){emit(pcm(false))}},
            16000,"zh",false,{calls++;"幻觉"},{})
        assertEquals("",result);assertEquals(0,calls)
    }
}
