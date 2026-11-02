package coredevices.util.transcription

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

actual object WatchAudioArchive {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var applicationContext: Context? = null
    @Volatile var lastError: String? = null
        private set
    fun initialize(context: Context) { applicationContext = context.applicationContext }
    fun directory(context: Context) = File(context.noBackupFilesDir, "watch-audio")
    fun enabled(context: Context) = context.getSharedPreferences("watch-audio", 0).getBoolean("enabled", true)
    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("watch-audio", 0).edit().putBoolean("enabled", value).apply()
    }
    fun status(context: Context) = context.getSharedPreferences("watch-audio", 0).getString("state", "waiting") ?: "waiting"
    private fun state(context: Context, value: String) { context.getSharedPreferences("watch-audio", 0).edit().putString("state", value).apply() }

    actual fun begin(sampleRate: Int, frameSize: Int, encoder: String): WatchAudioRecording? {
      return try {
        val context = checkNotNull(applicationContext) { "Audio archive not initialized" }
        if (!enabled(context)) return null
        val root = directory(context).apply { mkdirs() }
        // Include incomplete groups in retention, so interrupted processes cannot grow storage forever.
        val ids = root.listFiles().orEmpty().map { it.name.substringBefore('.') }
            .filter { it.matches(Regex("dictation-[0-9]+-[0-9a-f-]+")) }.distinct().sortedDescending()
        ids.drop(4).forEach { delete(context, it) }
        val now = System.currentTimeMillis()
        val info = WatchAudioInfo("dictation-$now-${UUID.randomUUID()}", now, sampleRate, frameSize, encoder)
        val writer = AndroidWatchAudioRecording(root, info)
        lastError = null
        state(context, "recording")
        Log.i("WatchAudioArchive", "Start ${info.id}: rate=$sampleRate frameSize=$frameSize")
        object : WatchAudioRecording {
            override fun append(encoded: ByteArray, pcm: ByteArray?) { writer.append(encoded, pcm) }
            override fun finish(outcome: String) {
                writer.finish(outcome)
                val saved = runCatching { json.decodeFromString<WatchAudioInfo>(File(root, "${info.id}.json").readText()) }.getOrNull()
                if (saved == null) {
                    lastError = writer.failureReason ?: "FinalizationFailed"
                    state(context, "failed")
                    Log.e("WatchAudioArchive", "Save failed: $lastError")
                } else {
                    state(context, "saved")
                    Log.i("WatchAudioArchive", "Saved ${saved.id}: bytes=${saved.pcmBytes} frames=${saved.frames} failed=${saved.failedFrames} peak=${saved.peak}")
                }
            }
        }
    } catch (error: Exception) {
        lastError = error.javaClass.simpleName
        Log.e("WatchAudioArchive", "Could not start recording", error)
        applicationContext?.let { state(it, "failed") }
        null
      }
    }

    fun recordings(context: Context): List<WatchAudioInfo> = directory(context).listFiles().orEmpty()
        .filter { it.extension == "json" }.mapNotNull { file ->
            runCatching { json.decodeFromString<WatchAudioInfo>(file.readText()) }.getOrNull()
                ?.takeIf { it.id == file.nameWithoutExtension && File(file.parentFile, "${it.id}.wav").isFile }
        }.sortedByDescending { it.startedAt }

    fun audio(context: Context, info: WatchAudioInfo) = File(directory(context), "${info.id}.wav")

    fun delete(context: Context, id: String) {
        require(id.matches(Regex("dictation-[0-9]+-[0-9a-f-]+")))
        listOf("wav", "wav.part", "json", "json.tmp", "speexframes", "speexframes.part").forEach {
            File(directory(context), "$id.$it").delete()
        }
        File(File(context.cacheDir, "watch-audio-shared"), "$id.wav").delete()
    }
}
