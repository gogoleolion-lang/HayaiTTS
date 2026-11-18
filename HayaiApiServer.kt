package dev.ahmedmohamed.hayaitts.tts

import co.touchlab.kermit.Logger
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Tiny local HTTP server so other apps can ask for emotional speech.
 * Listens on 127.0.0.1 only. No third-party library: plain sockets.
 *
 *   GET  /health          -> {"ok":true,"voice":"...","sampleRate":24000}
 *   GET  /emotions        -> {"emotions":[...]}
 *   POST /speak           -> raw 16-bit mono PCM, streamed as it is generated (chunked)
 *        body: {"text":"...", "emotion":"happy", "intensity":2, "voice":"zf_xiaoyi", "speed":1.0, "pitch":1.0}
 *        header X-Sample-Rate tells the sample rate.
 *   POST /stop            -> cancels whatever is being synthesised
 *
 * Closing the connection also cancels. Requests are handled one at a time (the model is shared).
 */
class HayaiApiServer(private val runtime: SherpaTtsRuntime) {

    private val log = Logger.withTag("HayaiApiServer")
    private val pool = Executors.newCachedThreadPool()
    private val synthLock = Any()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var cancel = false

    val running: Boolean get() = server?.isClosed == false

    fun start() {
        if (running) return
        val s = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
        server = s
        pool.execute {
            warmUp()
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Throwable) { break }
                pool.execute { handle(c) }
            }
        }
        log.i { "API listening on 127.0.0.1:$PORT" }
    }

    fun stop() {
        cancel = true
        runCatching { server?.close() }
        server = null
    }

    /** Loads the voice and runs one tiny synthesis so the first real sentence is not slow. */
    private fun warmUp() {
        runCatching {
            val (voiceId, sid) = resolveVoice(DEFAULT_SPEAKER) ?: return
            synchronized(synthLock) {
                runtime.synthesize(voiceId = voiceId, text = "Hi.", sid = sid, languageHint = "en-US")
            }
        }.onFailure { log.w(it) { "warm-up failed" } }
    }

    private fun resolveVoice(speaker: String): Pair<String, Int>? {
        val voices = runtime.listAvailableVoices()
        val withSpeaker = voices.firstNotNullOfOrNull { v ->
            v.speakers.firstOrNull { it.name.equals(speaker, ignoreCase = true) }?.let { v.voiceId to it.id }
        }
        if (withSpeaker != null) return withSpeaker
        // Fall back to the first installed voice so the API still answers.
        return voices.firstOrNull()?.let { it.voiceId to (it.speakers.firstOrNull()?.id ?: 0) }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 30_000
            val input = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()
            try {
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                var length = 0
                while (true) {
                    val h = readLine(input) ?: return
                    if (h.isEmpty()) break
                    if (h.startsWith("content-length:", ignoreCase = true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
                }
                val body = if (length in 1..MAX_BODY) String(readExactly(input, length), Charsets.UTF_8) else ""
                route(parts[0].uppercase(), parts[1].substringBefore('?'), body, s, out)
            } catch (t: Throwable) {
                log.w(t) { "request failed" }
                runCatching { respond(out, 500, JSONObject().put("error", t.message ?: "error").toString()) }
            }
        }
    }

    private fun route(method: String, path: String, body: String, socket: Socket, out: OutputStream) {
        when {
            method == "GET" && path == "/health" -> {
                val v = resolveVoice(DEFAULT_SPEAKER)
                val rate = v?.let { runCatching { runtime.sampleRateOf(it.first, languageHint = "en-US") }.getOrNull() }
                respond(out, 200, JSONObject().put("ok", v != null).put("voice", v?.first ?: "").put("sampleRate", rate ?: 0).toString())
            }
            method == "GET" && path == "/emotions" ->
                respond(out, 200, JSONObject().put("emotions", org.json.JSONArray(EmotionPresets.names.toList())).toString())
            method == "POST" && path == "/stop" -> { cancel = true; respond(out, 200, "{\"ok\":true}") }
            method == "POST" && path == "/speak" -> speak(JSONObject(body.ifBlank { "{}" }), socket, out)
            else -> respond(out, 404, "{\"error\":\"not found\"}")
        }
    }

    private fun speak(req: JSONObject, socket: Socket, out: OutputStream) {
        val text = req.optString("text").take(MAX_TEXT)
        if (text.isBlank()) { respond(out, 400, "{\"error\":\"text is empty\"}"); return }
        val (voiceId, sid) = resolveVoice(req.optString("voice", DEFAULT_SPEAKER).ifBlank { DEFAULT_SPEAKER })
            ?: run { respond(out, 503, "{\"error\":\"no voice installed\"}"); return }
        val shaping = EmotionPresets.shape(
            emotion = req.optString("emotion", "calm"),
            intensity = req.optInt("intensity", 2),
            text = text,
            userSpeed = req.optDouble("speed", 1.0).toFloat(),
            userPitch = req.optDouble("pitch", 1.0).toFloat(),
        )
        val rate = runtime.sampleRateOf(voiceId, languageHint = "en-US")

        synchronized(synthLock) {
            cancel = false
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: audio/L16;rate=$rate;channels=1\r\nX-Sample-Rate: $rate\r\n" +
                    "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n").toByteArray()
            )
            out.flush()
            var broken = false
            // The pitch is made by resampling, which also changes tempo. Ask the model for the opposite
            // tempo so the speed the caller asked for is what they hear.
            runtime.synthesizeStreaming(
                voiceId = voiceId,
                text = shaping.text,
                sid = sid,
                speed = (shaping.speed / shaping.pitch).coerceIn(0.4f, 2.0f),
                pitch = 1.0f,
                languageHint = "en-US",
            ) { chunk ->
                if (cancel || broken || socket.isClosed) return@synthesizeStreaming 0
                val pcm = toPcm(shapeChunk(chunk, shaping.pitch, shaping.gain))
                try {
                    out.write("${Integer.toHexString(pcm.size)}\r\n".toByteArray())
                    out.write(pcm); out.write("\r\n".toByteArray()); out.flush()
                    1
                } catch (_: Throwable) { broken = true; 0 }
            }
            if (!broken) runCatching { out.write("0\r\n\r\n".toByteArray()); out.flush() }
        }
    }

    /** Pitch by resampling (the whole chunk at once), then gain. */
    private fun shapeChunk(chunk: FloatArray, pitch: Float, gain: Float): FloatArray {
        val shifted = if (abs(pitch - 1f) < 0.001f) chunk else dev.ahmedmohamed.hayaitts.data.playground.PitchResampler.resample(chunk, pitch)
        if (abs(gain - 1f) < 0.001f) return shifted
        return FloatArray(shifted.size) { (shifted[it] * gain).coerceIn(-1f, 1f) }
    }

    private fun toPcm(f: FloatArray): ByteArray {
        val b = ByteArray(f.size * 2)
        for (i in f.indices) {
            val v = (f[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
            b[i * 2] = (v and 0xFF).toByte(); b[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return b
    }

    private fun respond(out: OutputStream, code: Int, json: String) {
        val bytes = json.toByteArray()
        out.write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes); out.flush()
    }

    private fun readExactly(i: BufferedInputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = i.read(buf, got, n - got)
            if (r < 0) break
            got += r
        }
        return buf.copyOf(got)
    }

    private fun readLine(i: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    companion object {
        const val PORT = 11435
        const val DEFAULT_SPEAKER = "zf_xiaoyi"
        private const val MAX_BODY = 64 * 1024
        private const val MAX_TEXT = 2000
    }
}
