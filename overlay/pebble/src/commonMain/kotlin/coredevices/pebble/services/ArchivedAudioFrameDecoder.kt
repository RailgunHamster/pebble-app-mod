package coredevices.pebble.services

import coredevices.util.transcription.WatchAudioArchive
import io.rebble.libpebblecommon.connection.endpointmanager.audio.AudioFrameDecoder
import io.rebble.libpebblecommon.connection.endpointmanager.audio.AudioPcmDecoder
import io.rebble.libpebblecommon.voice.VoiceEncoderInfo

/** Captures the same decoded bytes recognition receives, plus the untouched encoded frames. */
@OptIn(ExperimentalUnsignedTypes::class)
internal class ArchivedAudioFrameDecoder(private val delegate: AudioFrameDecoder) : AudioFrameDecoder {
    override fun open(encoderInfo: VoiceEncoderInfo): AudioPcmDecoder? {
        val decoder = delegate.open(encoderInfo) ?: return null
        val frameSize = (encoderInfo as? VoiceEncoderInfo.Speex)?.frameSize ?: (encoderInfo.sampleRate / 50).toInt()
        val recording = WatchAudioArchive.begin(encoderInfo.sampleRate.toInt(), frameSize, encoderInfo.toString())
        return object : AudioPcmDecoder {
            override fun decode(frame: UByteArray): ByteArray? {
                val pcm = decoder.decode(frame)
                recording?.append(frame.asByteArray(), pcm)
                return pcm
            }
            override fun close() {
                try { decoder.close() } finally { recording?.finish("saved") }
            }
        }
    }
}
