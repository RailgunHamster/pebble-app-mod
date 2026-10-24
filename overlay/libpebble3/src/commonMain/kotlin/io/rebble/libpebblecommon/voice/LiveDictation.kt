package io.rebble.libpebblecommon.voice

import io.rebble.libpebblecommon.connection.endpointmanager.audio.AudioPcmDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds

/** PCM-based endpointing; counts audio samples so delivery jitter cannot end speech early. */
internal class DictationActivity(private val sampleRate: Int) {
    private var total = 0L
    private var voiced = 0L
    private var quiet = 0L
    private var noise = 100.0
    var speechDetected = false
        private set

    fun accept(pcm: ByteArray): Boolean {
        val samples = pcm.size / 2
        if (samples == 0) return false
        var energy = 0.0
        for (i in 0 until samples) {
            val value = ((pcm[2 * i].toInt() and 255) or (pcm[2 * i + 1].toInt() shl 8)).toShort().toDouble()
            energy += value * value
        }
        val rms = sqrt(energy / samples)
        total += samples
        if (rms > maxOf(250.0, noise * 3)) {
            voiced += samples
            quiet = 0
            if (voiced >= sampleRate * 120L / 1000) speechDetected = true
        } else {
            quiet += samples
            if (!speechDetected) {
                voiced = 0
                noise = (noise * 0.95 + rms * 0.05).coerceAtMost(1000.0)
            }
        }
        return total >= sampleRate * 15L ||
            (speechDetected && quiet >= sampleRate * 700L / 1000) ||
            (!speechDetected && total >= sampleRate * 3L)
    }
}

/**
 * Incremental snapshots for existing batch recognizers (including SenseVoice and the private
 * relay). One inference at a time; a conflated queue drops obsolete work rather than building
 * a backlog. The final full clip is always recognized, even if an earlier snapshot failed.
 */
internal suspend fun liveDictation(
    audioFrames: Flow<UByteArray>,
    sampleRate: Int,
    decoder: AudioPcmDecoder,
    recognize: suspend (List<UByteArray>) -> TranscriptionResult,
    onPartial: suspend (List<TranscriptionWord>) -> Unit,
    onSpeechEnd: suspend () -> Unit,
): TranscriptionResult = coroutineScope {
    require(sampleRate in 8000..48000)
    data class Snapshot(val frames: List<UByteArray>, val final: Boolean)
    val pending = Channel<Snapshot>(Channel.CONFLATED)
    val activity = DictationActivity(sampleRate)
    val frames = mutableListOf<UByteArray>()
    var samples = 0L
    var lastSnapshot = 0L
    var lastText = ""
    var result: TranscriptionResult = TranscriptionResult.Error("No speech result")
    val worker = launch {
        for (snapshot in pending) {
            val next = try {
                withTimeoutOrNull(if (snapshot.final) 12.seconds else 3.seconds) {
                    recognize(snapshot.frames)
                } ?: TranscriptionResult.ConnectionError("Live transcription timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                TranscriptionResult.Error("Live transcription failed")
            }
            if (snapshot.final) result = next
            if (next is TranscriptionResult.Success) {
                val text = next.words.joinToString(" ") { it.word }
                if (text.isNotBlank() && text != lastText) {
                    lastText = text
                    // A single word preserves CJK spacing and bounds the protocol payload.
                    onPartial(listOf(TranscriptionWord(text.take(160), 0.9f)))
                }
            }
        }
    }
    try {
        audioFrames.takeWhile { frame ->
            frames.add(frame.copyOf())
            val pcm = decoder.decode(frame) ?: return@takeWhile true
            samples += pcm.size / 2
            val ended = activity.accept(pcm)
            if (activity.speechDetected && samples - lastSnapshot >= sampleRate * 3L / 4) {
                lastSnapshot = samples
                pending.trySend(Snapshot(frames.toList(), false))
            }
            !ended
        }.collect { }
        // Sent before the final result so firmware stops capture and starts its result timer.
        onSpeechEnd()
        if (!activity.speechDetected) {
            pending.close()
            worker.cancel()
            return@coroutineScope TranscriptionResult.Success(emptyList())
        }
        pending.send(Snapshot(frames.toList(), true))
        pending.close()
        if (withTimeoutOrNull(13.seconds) { worker.join(); true } != true) {
            worker.cancel()
            result = TranscriptionResult.ConnectionError("Final transcription timed out")
        }
        result
    } finally {
        pending.close()
        worker.cancel()
        decoder.close()
    }
}
