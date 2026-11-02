package coredevices.coreapp.mod

import android.content.ClipData
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import coredevices.util.localization.appText
import coredevices.util.transcription.WatchAudioArchive
import coredevices.util.transcription.WatchAudioInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class WatchAudioRecordingsActivity : ComponentActivity() {
    private var player: MediaPlayer? = null
    private var exportingId: String? = null
    private val io = Executors.newSingleThreadExecutor()
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val id = exportingId
        exportingId = null
        if (uri != null && id != null) {
            val info = WatchAudioArchive.recordings(this).firstOrNull { it.id == id }
            if (info != null) io.execute {
                val ok = runCatching {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        WatchAudioArchive.audio(this, info).inputStream().use { it.copyTo(output) }
                    } ?: error("No export stream")
                }.isSuccess
                runOnUiThread { message(if (ok) "Recording exported" else "Could not export recording") }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchAudioArchive.initialize(applicationContext)
        exportingId = savedInstanceState?.getString("exportingId")
        title = appText("Original watch recordings")
        actionBar?.hide()
        volumeControlStream = AudioManager.STREAM_MUSIC
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("exportingId", exportingId)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() { super.onResume(); render() }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun render() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        column.addView(TextView(this).apply { text = appText("Original watch recordings"); textSize = 22f })
        column.addView(Switch(this).apply {
            text = appText("Keep original watch audio")
            isChecked = WatchAudioArchive.enabled(this@WatchAudioRecordingsActivity)
            setOnCheckedChangeListener { _, checked -> WatchAudioArchive.setEnabled(this@WatchAudioRecordingsActivity, checked) }
        })
        column.addView(TextView(this).apply {
            text = appText("Keeps the latest 5 recordings on this phone, including failed or cancelled dictation. WAV audio is saved before recognition, without extra volume or noise processing.")
        })
        column.addView(button("Refresh") { stop(); render() })
        WatchAudioArchive.lastError?.let { error -> column.addView(TextView(this).apply {
            text = appText("Recording capture error: %1\$s", error)
        }) }
        val recordings = WatchAudioArchive.recordings(this)
        if (recordings.isEmpty()) column.addView(TextView(this).apply {
            val status = when (WatchAudioArchive.status(this@WatchAudioRecordingsActivity)) {
                "recording" -> "Capturing watch audio"
                "failed" -> "Recording could not be saved"
                else -> "Waiting for watch audio"
            }
            text = appText(status) + "\n" + appText("No saved recordings yet. Speak on the watch, then finish or cancel and tap Refresh.")
        })
        recordings.forEach { info ->
            column.addView(TextView(this).apply {
                textSize = 18f
                text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(info.startedAt))
            })
            column.addView(TextView(this).apply {
                text = appText("%1\$s seconds · %2\$s Hz · %3\$s frames · %4\$s decode failures",
                    String.format(Locale.getDefault(), "%.2f", info.seconds), info.sampleRate, info.frames, info.failedFrames) +
                    "\n" + appText("Peak %1\$s% · clipped samples %2\$s · %3\$s",
                    String.format(Locale.getDefault(), "%.1f", info.peak * 100.0 / 32768), info.clippedSamples,
                    appText(when (info.outcome) { "saved", "finished" -> "Recording saved"; "cancelled" -> "Cancelled"; "failed" -> "Recognition failed"; "limit" -> "Recording limit reached"; else -> "Interrupted" }))
            })
            column.addView(LinearLayout(this).apply {
                addView(button("Play") { play(info) })
                addView(button("Stop") { stop() })
                addView(button("Export WAV") { exportingId = info.id; export.launch("${info.id}.wav") })
            })
            column.addView(LinearLayout(this).apply {
                addView(button("Share") { share(info) })
                addView(button("Delete") { stop(); WatchAudioArchive.delete(this@WatchAudioRecordingsActivity, info.id); render() })
            })
        }
        val scroll = ScrollView(this).apply { addView(column, ViewGroup.LayoutParams(-1, -2)) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = appText(label); setOnClickListener { action() }
    }

    private fun play(info: WatchAudioInfo) {
        stop()
        if (info.pcmBytes == 0L) { message("Recording is empty or cannot be played"); return }
        try {
            val next = MediaPlayer()
            player = next
            next.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            next.setDataSource(WatchAudioArchive.audio(this, info).absolutePath)
            next.setOnPreparedListener { if (player === it) it.start() }
            next.setOnCompletionListener { stop() }
            next.setOnErrorListener { _, _, _ -> stop(); message("Recording is empty or cannot be played"); true }
            next.prepareAsync()
        } catch (_: Exception) { stop(); message("Recording is empty or cannot be played") }
    }

    private fun share(info: WatchAudioInfo) {
        io.execute {
            val file = runCatching {
                val root = File(cacheDir, "watch-audio-shared").apply { mkdirs() }
                val existing = WatchAudioArchive.recordings(this).map { "${it.id}.wav" }.toSet()
                root.listFiles().orEmpty().filter { it.name !in existing }.forEach { it.delete() }
                File(root, "${info.id}.wav").also { WatchAudioArchive.audio(this, info).copyTo(it, overwrite = true) }
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (file == null) { message("Could not export recording"); return@runOnUiThread }
                val uri = FileProvider.getUriForFile(this, "$packageName.watchaudio", file)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "audio/wav"; putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("WAV", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, appText("Share")))
            }
        }
    }

    private fun message(label: String) { Toast.makeText(this, appText(label), Toast.LENGTH_LONG).show() }
    private fun stop() { player?.release(); player = null }
    override fun onStop() { stop(); super.onStop() }
    override fun onDestroy() { io.shutdown(); super.onDestroy() }
}
