package coredevices.util.transcription

import android.content.Context
import co.touchlab.kermit.Logger
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import coredevices.util.models.ModelDownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Personal build: on-device speech recognition with SenseVoice-Small (Chinese, English,
 * Japanese, Korean, Cantonese) through sherpa-onnx, int8, on the CPU. This is the
 * "System (On-Device)" mode of the speech settings; the model (~230 MB) is downloaded once
 * from a Hugging Face mirror reachable in China. When it fails, the mode falls back to the
 * cloud recognizer (kirinki) like upstream does.
 *
 * The sherpa-onnx classes (util/libs/sherpa-onnx.jar) and native libraries
 * (androidApp/src/main/jniLibs) come from the sherpa-onnx release AAR, unpacked by CI.
 */
actual class PlatformSpeechRecognizer {
    private val logger = Logger.withTag("SenseVoice")
    private val context: Context by lazy { KoinPlatform.getKoin().get<Context>() }
    private val dir: File get() = File(context.filesDir, "sensevoice")
    private val modelFile: File get() = File(dir, MODEL)
    private val tokensFile: File get() = File(dir, TOKENS)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val recognizers = mutableMapOf<String, OfflineRecognizer>()   // by language hint
    private var downloadJob: Job? = null
    private val _downloadStatus = MutableStateFlow<ModelDownloadStatus>(ModelDownloadStatus.Idle)

    private val engineLoads: Boolean by lazy {
        try {
            System.loadLibrary("sherpa-onnx-jni")
            true
        } catch (e: Throwable) {
            logger.w(e) { "sherpa-onnx native library not available" }
            false
        }
    }

    actual suspend fun isAvailable(): Boolean = engineLoads

    /** Nothing to grant: the audio comes from the watch, not the phone's microphone. */
    actual suspend fun isAuthorized(): Boolean = true

    actual val supportedLanguageTags: StateFlow<List<String>> =
        MutableStateFlow(listOf("zh-CN", "en-US", "ja-JP", "ko-KR", "yue-CN"))

    private fun installed(): Boolean = modelFile.length() > 0 && tokensFile.length() > 0

    actual suspend fun modelAvailability(languageTag: String?): SpeechModelAvailability = when {
        languageTag != null && hint(languageTag) == null -> SpeechModelAvailability.Unsupported
        downloadJob?.isActive == true -> SpeechModelAvailability.Downloading
        installed() -> SpeechModelAvailability.Installed
        else -> SpeechModelAvailability.NotDownloaded
    }

    actual val downloadStatus: StateFlow<ModelDownloadStatus> = _downloadStatus

    actual fun downloadModel(languageTag: String?): Boolean {
        if (!engineLoads || downloadJob?.isActive == true) return false
        downloadJob = scope.launch {
            try {
                dir.mkdirs()
                _downloadStatus.value = ModelDownloadStatus.Downloading(SLUG, 0f)
                fetch(TOKENS, tokensFile) {}
                fetch(MODEL, modelFile) { p -> _downloadStatus.value = ModelDownloadStatus.Downloading(SLUG, p) }
                _downloadStatus.value = ModelDownloadStatus.Idle
                logger.i { "SenseVoice model downloaded (${modelFile.length() / 1048576} MB)" }
            } catch (e: Exception) {
                logger.w(e) { "SenseVoice model download failed" }
                _downloadStatus.value = ModelDownloadStatus.Failed(SLUG, e.message ?: e.toString())
            }
        }
        return true
    }

    /** Downloads one file of the model repository, trying each mirror; reports 0..1 progress. */
    private fun fetch(name: String, dest: File, progress: (Float) -> Unit) {
        if (dest.length() > 0) return
        var last: Exception? = null
        for (base in MIRRORS) {
            val part = File(dest.path + ".part")
            try {
                val conn = URL("$base/$REPO/resolve/main/$name").openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = true
                try {
                    if (conn.responseCode != 200) throw IllegalStateException("$base: HTTP ${conn.responseCode}")
                    val total = conn.contentLengthLong
                    var done = 0L
                    var reported = -1
                    conn.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != reported) {
                                        reported = pct
                                        progress(done.toFloat() / total)
                                    }
                                }
                            }
                        }
                    }
                    if (total > 0 && done != total) throw IllegalStateException("$base: incomplete ($done of $total bytes)")
                } finally {
                    conn.disconnect()
                }
                if (!part.renameTo(dest)) throw IllegalStateException("can't save $name")
                return
            } catch (e: Exception) {
                part.delete()
                last = e
                logger.w { "download of $name from $base failed: ${e.message}" }
            }
        }
        throw last ?: IllegalStateException("download failed")
    }

    // BEGIN releaseUnusedModels (CI removes this block for upstream versions without it)
    actual fun releaseUnusedModels(languageTag: String?) {
        val keep = languageTag?.let { hint(it) } ?: ""
        scope.launch {
            lock.withLock {
                recognizers.keys.filter { it != keep }.forEach { recognizers.remove(it)?.release() }
            }
        }
    }
    // END releaseUnusedModels

    actual suspend fun transcribe(pcm: ByteArray, sampleRate: Int, languageTag: String?): String {
        if (!engineLoads) throw TranscriptionException.TranscriptionServiceUnavailable(PLATFORM_STT_MODEL_NAME)
        if (!installed()) {
            throw TranscriptionException.TranscriptionRequiresDownload(
                "SenseVoice model not downloaded", PLATFORM_STT_MODEL_NAME,
            )
        }
        val language = languageTag?.let { hint(it) } ?: ""
        return withContext(Dispatchers.Default) {
            // little-endian 16-bit PCM -> float samples in [-1, 1)
            val samples = FloatArray(pcm.size / 2) { i ->
                ((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)).toShort() / 32768f
            }
            lock.withLock {
                val recognizer = recognizers.getOrPut(language) { create(language) }
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(samples, sampleRate)
                    recognizer.decode(stream)
                    recognizer.getResult(stream).text.trim()
                } finally {
                    stream.release()
                }
            }
        }
    }

    private fun create(language: String): OfflineRecognizer {
        val start = System.currentTimeMillis()
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = modelFile.absolutePath,
                    language = language,
                    useInverseTextNormalization = true,   // punctuation, digits
                ),
                tokens = tokensFile.absolutePath,
                numThreads = THREADS,
                debug = false,
            ),
        )
        return OfflineRecognizer(config = config).also {
            logger.i { "SenseVoice loaded (${language.ifEmpty { "auto" }}) in ${System.currentTimeMillis() - start} ms" }
        }
    }

    /** SenseVoice language hint for a BCP-47 tag; null when SenseVoice doesn't speak it. */
    private fun hint(languageTag: String): String? {
        val tag = languageTag.lowercase().replace('_', '-')
        if (tag.startsWith("yue") || tag == "zh-hk" || tag == "zh-mo") return "yue"
        return when (val primary = tag.substringBefore('-')) {
            "zh", "en", "ja", "ko" -> primary
            else -> null
        }
    }

    private companion object {
        const val SLUG = "sensevoice-small-int8"
        const val REPO = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"
        const val MODEL = "model.int8.onnx"
        const val TOKENS = "tokens.txt"
        const val THREADS = 4
        val MIRRORS = listOf("https://hf-mirror.com", "https://huggingface.co")
    }
}
