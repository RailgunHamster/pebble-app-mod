package coredevices.util.transcription

import kotlinx.serialization.json.Json
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

/** Bounded, incremental storage: never buffers the recording or changes PCM samples. */
class AndroidWatchAudioRecording(private val root: File, private var info: WatchAudioInfo) : WatchAudioRecording {
    private val wavPart = File(root, "${info.id}.wav.part")
    private val framePart = File(root, "${info.id}.speexframes.part")
    private val wav = RandomAccessFile(wavPart, "rw")
    private val frames = DataOutputStream(framePart.outputStream().buffered())
    private var ended = false
    private var writeFailed = false
    private val maxBytes = info.sampleRate * 2L * 300
    init { wav.write(watchAudioWavHeader(info.sampleRate, 0)) }

    @Synchronized
    override fun append(encoded: ByteArray, pcm: ByteArray?) {
        if (ended || writeFailed) return
        try {
            require(encoded.size <= 4096 && (pcm == null || pcm.size % 2 == 0))
            if (info.frames >= 15000 || info.pcmBytes + (pcm?.size ?: 0) > maxBytes) {
                finish("limit"); return
            }
            // Length-prefixed exact incoming frames, including their original header byte.
            frames.writeInt(encoded.size); frames.write(encoded)
            var peak = info.peak
            var clipped = info.clippedSamples
            if (pcm != null) {
                wav.write(pcm)
                for (i in pcm.indices step 2) {
                    val sample = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
                    peak = maxOf(peak, abs(sample))
                    if (sample >= 32760 || sample <= -32760) clipped++
                }
            }
            info = info.copy(frames = info.frames + 1, failedFrames = info.failedFrames + if (pcm == null) 1 else 0,
                pcmBytes = info.pcmBytes + (pcm?.size ?: 0), peak = peak, clippedSamples = clipped)
        } catch (_: Exception) { writeFailed = true }
    }

    @Synchronized
    override fun finish(outcome: String) {
        if (ended) return
        ended = true
        try {
            wav.seek(0); wav.write(watchAudioWavHeader(info.sampleRate, info.pcmBytes.toInt()))
        } catch (_: Exception) { writeFailed = true }
        finally { runCatching { wav.close() }; runCatching { frames.close() } }
        runCatching {
            check(wavPart.renameTo(File(root, "${info.id}.wav")))
            check(framePart.renameTo(File(root, "${info.id}.speexframes")))
            info = info.copy(outcome = if (writeFailed) "storage-error" else outcome)
            val temporary = File(root, "${info.id}.json.tmp")
            temporary.writeText(Json.encodeToString(info))
            check(temporary.renameTo(File(root, "${info.id}.json")))
        }
    }
}
