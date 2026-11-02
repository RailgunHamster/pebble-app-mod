package coredevices.util.transcription

import android.content.Context
import kotlinx.serialization.json.Json
import org.koin.mp.KoinPlatform
import java.io.File
import java.util.UUID

actual object WatchAudioArchive {
    private val json = Json { ignoreUnknownKeys = true }
    fun directory(context: Context) = File(context.noBackupFilesDir, "watch-audio")
    fun enabled(context: Context) = context.getSharedPreferences("watch-audio", 0).getBoolean("enabled", true)
    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("watch-audio", 0).edit().putBoolean("enabled", value).apply()
    }

    actual fun begin(sampleRate: Int, frameSize: Int, encoder: String): WatchAudioRecording? = runCatching {
        val context = KoinPlatform.getKoin().get<Context>()
        if (!enabled(context)) return@runCatching null
        val root = directory(context).apply { mkdirs() }
        // Include incomplete groups in retention, so interrupted processes cannot grow storage forever.
        val ids = root.listFiles().orEmpty().map { it.name.substringBefore('.') }
            .filter { it.matches(Regex("dictation-[0-9]+-[0-9a-f-]+")) }.distinct().sortedDescending()
        ids.drop(4).forEach { delete(context, it) }
        val now = System.currentTimeMillis()
        val info = WatchAudioInfo("dictation-$now-${UUID.randomUUID()}", now, sampleRate, frameSize, encoder)
        AndroidWatchAudioRecording(root, info)
    }.getOrNull()

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
