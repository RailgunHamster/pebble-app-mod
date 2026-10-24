package coredevices.util.transcription

import com.russhwolf.settings.Settings
import coredevices.util.CommonBuildKonfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds

const val DICTATION_MAX_BYTES = 8000
const val DICTATION_POLISH_KEY = "mod.dictation.polish"
const val DICTATION_ORIGINAL_KEY = "mod.dictation.original"

/** Applies revisions by segment identity; arrival order never changes sentence order. */
class DictationTranscript {
    private data class Segment(val revision: Int, val text: String, val final: Boolean)
    private val segments = mutableMapOf<Int, Segment>()
    fun update(id: Int, revision: Int, text: String, final: Boolean): Boolean {
        require(id in 0..1000 && revision >= 0)
        val prior = segments[id]
        if (prior != null && (revision <= prior.revision || (prior.final && !final))) return false
        require(text.encodeToByteArray().size <= DICTATION_MAX_BYTES)
        val size = segments.values.sumOf { it.text.encodeToByteArray().size } -
            (prior?.text?.encodeToByteArray()?.size ?: 0) + text.encodeToByteArray().size
        require(size <= DICTATION_MAX_BYTES) { "Dictation text limit reached" }
        segments[id] = Segment(revision, text, final)
        return true
    }
    fun text() = segments.keys.sorted().joinToString("") { segments.getValue(it).text }
}

/** Keep a valid UTF-8 tail for the small watch preview; the phone retains the full text. */
fun dictationPreview(text: String, limit: Int = 480): String {
    val bytes = text.replace('\n', ' ').replace('\r', ' ').encodeToByteArray()
    var start = (bytes.size-limit).coerceAtLeast(0)
    while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) start++
    return bytes.decodeToString(startIndex = start)
}

/** Shared Android/iOS transport. Recording ends only when the supplied PCM flow ends. */
class ContinuousDictation(private val settings: Settings) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient { install(WebSockets) }

    suspend fun transcribe(
        pcm: Flow<ByteArray>, sampleRate: Int, language: String,
        remote: Boolean, recognize: suspend (ByteArray) -> String,
        onPreview: suspend (String) -> Unit,
    ): String = coroutineScope {
        require(sampleRate == 16000) { "Continuous dictation requires 16 kHz mono PCM" }
        // Start consuming before connecting, so the first word is retained during the handshake.
        val input = pcm.buffer(256).produceIn(this)
        try {
            val original = if (remote && CommonBuildKonfig.KIRINKI_URL != null) {
                remoteSession(input, language, onPreview)
            } else {
                local(input, sampleRate, recognize, onPreview)
            }
            require(original.encodeToByteArray().size <= DICTATION_MAX_BYTES)
            settings.putString(DICTATION_ORIGINAL_KEY, original)
            if (settings.getBoolean(DICTATION_POLISH_KEY, true)) refine(original, language) else original
        } finally {
            input.cancel()
            client.close()
        }
    }

    private suspend fun remoteSession(input: kotlinx.coroutines.channels.ReceiveChannel<ByteArray>, language: String,
                               onPreview: suspend (String) -> Unit): String = coroutineScope {
        val base = CommonBuildKonfig.KIRINKI_URL!!.trimEnd('/').removeSuffix("/stt")
        val url = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/stt/live"
        val socket = withTimeout(5.seconds) {
            client.webSocketSession(url) { CommonBuildKonfig.KIRINKI_TOKEN?.let { bearerAuth(it) } }
        }
        val transcript = DictationTranscript()
        var expectedSession: String? = null
        val endOfInput = CompletableDeferred<Unit>()
        var sender: kotlinx.coroutines.Job? = null
        try {
            socket.send(Frame.Text(buildJsonObject {
                put("type", "start"); put("sample_rate", 16000); put("language", language)
            }.toString()))
            val ready = withTimeout(5.seconds) { socket.incoming.receive() } as? Frame.Text
                ?: error("No live dictation handshake")
            val handshake = json.parseToJsonElement(ready.readText()).jsonObject
            require(handshake["type"]?.jsonPrimitive?.content == "ready")
            expectedSession = handshake["session"]!!.jsonPrimitive.content
            sender = launch {
                for (chunk in input) {
                    // Watch frames are small; enforce the server's per-message bound as well.
                    var offset = 0
                    while (offset < chunk.size) {
                        val end = minOf(offset+3200, chunk.size)
                        socket.send(Frame.Binary(true, chunk.copyOfRange(offset,end))); offset=end
                    }
                }
                socket.send(Frame.Text("{\"type\":\"finish\"}"))
                endOfInput.complete(Unit)
            }
            while (true) {
                // Wake an already pending receive when recording finishes, so an unresponsive
                // server cannot keep the watch waiting for the entire capture timeout.
                val frame = if (endOfInput.isCompleted) withTimeout(13.seconds) { socket.incoming.receive() }
                    else withTimeout(320.seconds) {
                        select<Frame> {
                            socket.incoming.onReceive { it }
                            endOfInput.onAwait { withTimeout(13.seconds) { socket.incoming.receive() } }
                        }
                    }
                if (frame !is Frame.Text) continue
                val message = json.parseToJsonElement(frame.readText()).jsonObject
                val type = message["type"]?.jsonPrimitive?.content
                if (type == "error") error("Continuous speech connection failed; draft retained")
                require(message["session"]?.jsonPrimitive?.content == expectedSession)
                val text = message["text"]?.jsonPrimitive?.content.orEmpty()
                if (type == "session_final") {
                    require(text.encodeToByteArray().size <= DICTATION_MAX_BYTES)
                    sender.join()
                    return@coroutineScope text
                }
                if (type in listOf("partial", "segment_final")) {
                    if (transcript.update(message["segment"]!!.jsonPrimitive.int,
                            message["revision"]!!.jsonPrimitive.int, text, type == "segment_final")) {
                        val all = transcript.text()
                        settings.putString(DICTATION_ORIGINAL_KEY, all)
                        onPreview(dictationPreview(all))
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") ""
        } finally {
            sender?.cancel()
            socket.close()
        }
    }

    /** On-device fallback: bounded current-segment snapshots, never whole-session redecoding. */
    private suspend fun local(input: kotlinx.coroutines.channels.ReceiveChannel<ByteArray>, rate: Int,
                              recognize: suspend (ByteArray) -> String,
                              onPreview: suspend (String) -> Unit): String = coroutineScope {
        data class Work(val id: Int, val revision: Int, val data: ByteArray, val final: Boolean)
        val transcript = DictationTranscript()
        val work = Channel<Work>(1)
        val worker = launch {
            for (item in work) {
                val text = try {
                    withTimeout(if (item.final) 8.seconds else 3.seconds) { recognize(item.data) }
                } catch (_: TimeoutCancellationException) { "" }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { "" }
                if (text.isNotBlank() && transcript.update(item.id,item.revision,text,item.final)) {
                    val all=transcript.text(); settings.putString(DICTATION_ORIGINAL_KEY,all)
                    onPreview(dictationPreview(all))
                }
            }
        }
        var id=0;var revision=0;var samples=0;var total=0L;var quiet=0;var spoke=false
        var lastPreview=0;val chunks=mutableListOf<ByteArray>()
        fun snapshot(): ByteArray {
            val out=ByteArray(chunks.sumOf { it.size });var offset=0
            for (chunk in chunks) { chunk.copyInto(out,offset);offset+=chunk.size }
            return out
        }
        suspend fun commit() {
            if (spoke) work.send(Work(id++,1000000,snapshot(),true))
            chunks.clear();samples=0;quiet=0;spoke=false;revision=0;lastPreview=0
        }
        try {
            for (chunk in input) {
                require(chunk.size%2==0)
                chunks.add(chunk.copyOf());val count=chunk.size/2;samples+=count;total+=count
                require(total <= rate*300L) { "Dictation duration limit reached" }
                var energy=0.0
                for (i in 0 until count) {
                    val value=((chunk[i*2].toInt() and 255) or (chunk[i*2+1].toInt() shl 8)).toShort().toDouble()
                    energy+=value*value
                }
                val voiced=count>0 && sqrt(energy/count)>250
                if (voiced) { spoke=true;quiet=0 } else quiet+=count
                if ((spoke && quiet >= rate*9/10) || samples >= rate*15) {
                    commit()
                } else if (spoke && samples-lastPreview>=rate) {
                    lastPreview=samples
                    // Partial work may be skipped; final segments always use send().
                    work.trySend(Work(id,++revision,snapshot(),false))
                } else if (!spoke && samples>=rate*2) { chunks.clear();samples=0 }
            }
            commit();work.close();withTimeout(13.seconds) { worker.join() }
            transcript.text()
        } finally { work.close();worker.cancel() }
    }

    private suspend fun refine(original: String, language: String): String {
        val base=CommonBuildKonfig.KIRINKI_URL?.trimEnd('/')?.removeSuffix("/stt") ?: return original
        if (original.isBlank()) return original
        return try {
            withTimeout(9.seconds) {
                val response=client.post(base+"/stt/refine") {
                    CommonBuildKonfig.KIRINKI_TOKEN?.let { bearerAuth(it) }
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("text",original);put("language",language) }.toString())
                }
                if (response.status.value!=200) return@withTimeout original
                val result=json.parseToJsonElement(response.bodyAsText()).jsonObject
                val text=result["text"]?.jsonPrimitive?.content.orEmpty()
                if (text.isBlank() || text.encodeToByteArray().size>DICTATION_MAX_BYTES) original else text
            }
        } catch (_: TimeoutCancellationException) { original }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { original }
    }
}
