package coredevices.util.transcription

import kotlinx.serialization.Serializable

interface WatchAudioRecording {
    fun append(encoded: ByteArray, pcm: ByteArray?)
    fun finish(outcome: String)
}

expect object WatchAudioArchive {
    fun begin(sampleRate: Int, frameSize: Int, encoder: String): WatchAudioRecording?
}

@Serializable
data class WatchAudioInfo(
    val id: String,
    val startedAt: Long,
    val sampleRate: Int,
    val frameSize: Int,
    val encoder: String,
    val frames: Long = 0,
    val failedFrames: Long = 0,
    val pcmBytes: Long = 0,
    val peak: Int = 0,
    val clippedSamples: Long = 0,
    val outcome: String = "recording",
) {
    val seconds: Double get() = pcmBytes.toDouble() / (sampleRate * 2)
}

/** The exact PCM handed to recognition, with a standard mono signed 16-bit WAV header. */
fun watchAudioWavHeader(sampleRate: Int, pcmBytes: Int): ByteArray {
    require(sampleRate in 8000..48000 && pcmBytes >= 0 && pcmBytes % 2 == 0)
    val out = ByteArray(44)
    fun label(offset: Int, value: String) { value.encodeToByteArray().copyInto(out, offset) }
    fun number(offset: Int, value: Int, bytes: Int) {
        repeat(bytes) { out[offset + it] = (value ushr (it * 8)).toByte() }
    }
    label(0, "RIFF"); number(4, pcmBytes + 36, 4); label(8, "WAVE")
    label(12, "fmt "); number(16, 16, 4); number(20, 1, 2); number(22, 1, 2)
    number(24, sampleRate, 4); number(28, sampleRate * 2, 4)
    number(32, 2, 2); number(34, 16, 2); label(36, "data"); number(40, pcmBytes, 4)
    return out
}
