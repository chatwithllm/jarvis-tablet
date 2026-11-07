package net.npalakurla.jarvis.tablet

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloud STT and TTS through the ContextForge `/llm/v1` base.
 *
 * xAI's routes are POST /v1/stt and POST /v1/tts, not the OpenAI
 * audio transcription and speech paths. This client calls {llmBase}/stt and {llmBase}/tts
 * with the saved LLM bearer. The gateway does not proxy those routes
 * today, so a 404 (or any transport failure) returns [Stt.Unavailable]
 * / null and the caller falls back to Vosk or Android TTS.
 *
 * Audio bytes and the bearer are never logged.
 */
object CloudVoice {

    sealed class Stt {
        data class Ok(val text: String) : Stt()
        data object Unavailable : Stt()
    }

    private const val STT_MODEL = "grok-voice-transcribe-2.0"
    private const val TAG = "JarvisVoice"

    fun transcribeWav(context: android.content.Context, wav: ByteArray): Stt {
        val token = TokenStore.getLlmToken(context) ?: return Stt.Unavailable
        if (wav.isEmpty() || wav.size > 8_000_000) return Stt.Unavailable
        val url = endpoint(TokenStore.getLlmBaseUrl(context), "stt")
        return try {
            val boundary = "jarvis-${System.nanoTime()}"
            val body = ByteArrayOutputStream()
            fun text(name: String, value: String) {
                body.write("--$boundary\r\n".toByteArray())
                body.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                body.write(value.toByteArray(Charsets.UTF_8))
                body.write("\r\n".toByteArray())
            }
            text("model", STT_MODEL)
            text("language", "en")
            body.write("--$boundary\r\n".toByteArray())
            body.write(
                "Content-Disposition: form-data; name=\"file\"; filename=\"turn.wav\"\r\n".toByteArray(),
            )
            body.write("Content-Type: audio/wav\r\n\r\n".toByteArray())
            body.write(wav)
            body.write("\r\n--$boundary--\r\n".toByteArray())
            val (code, bytes) = post(
                url,
                token,
                "multipart/form-data; boundary=$boundary",
                body.toByteArray(),
                readTimeoutMs = 40_000,
            )
            if (code !in 200..299) {
                Log.i(TAG, "stt_http=$code")
                return Stt.Unavailable
            }
            val text = runCatching { JSONObject(String(bytes, Charsets.UTF_8)).optString("text") }
                .getOrDefault("")
            Stt.Ok(text.trim())
        } catch (e: Exception) {
            Log.i(TAG, "stt_http=0")
            Stt.Unavailable
        }
    }

    /** MP3 bytes, or null when the gateway has no TTS route. */
    fun synthesize(context: android.content.Context, text: String): ByteArray? {
        val spoken = text.trim()
        if (spoken.isEmpty()) return null
        val token = TokenStore.getLlmToken(context) ?: return null
        val url = endpoint(TokenStore.getLlmBaseUrl(context), "tts")
        val payload = JSONObject()
            .put("text", spoken.take(2_000))
            .put("voice_id", JarvisVoices.selectedId(context))
            .put("language", "en")
            .put("speed", JarvisVoices.SPEED)
            .put(
                "output_format",
                JSONObject()
                    .put("codec", "mp3")
                    .put("sample_rate", 24000)
                    .put("bit_rate", 128000),
            )
        return try {
            val (code, bytes) = post(
                url,
                token,
                "application/json; charset=utf-8",
                payload.toString().toByteArray(Charsets.UTF_8),
                readTimeoutMs = 40_000,
            )
            if (code !in 200..299 || bytes.isEmpty()) {
                Log.i(TAG, "tts_http=$code")
                return null
            }
            if (bytes[0] == '{'.code.toByte()) {
                val b64 = runCatching { JSONObject(String(bytes, Charsets.UTF_8)).optString("audio") }
                    .getOrDefault("")
                if (b64.isEmpty()) return null
                Base64.decode(b64, Base64.DEFAULT)
            } else {
                bytes
            }
        } catch (e: Exception) {
            Log.i(TAG, "tts_http=0")
            null
        }
    }

    /** 16 kHz mono 16-bit PCM wrapped in a WAV header. */
    fun pcm16kToWav(pcm: ShortArray): ByteArray {
        val data = ByteArray(pcm.size * 2)
        var i = 0
        while (i < pcm.size) {
            val v = pcm[i].toInt()
            data[i * 2] = (v and 0xFF).toByte()
            data[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            i++
        }
        val out = ByteArrayOutputStream()
        val dataSize = data.size
        out.write("RIFF".toByteArray())
        out.write(le32(36 + dataSize))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(le32(16))
        out.write(le16(1))
        out.write(le16(1))
        out.write(le32(16_000))
        out.write(le32(16_000 * 2))
        out.write(le16(2))
        out.write(le16(16))
        out.write("data".toByteArray())
        out.write(le32(dataSize))
        out.write(data)
        return out.toByteArray()
    }

    private fun endpoint(base: String, leaf: String): String {
        val trimmed = base.trim().trimEnd('/')
        return if (trimmed.endsWith("/$leaf")) trimmed else "$trimmed/$leaf"
    }

    private fun post(
        url: String,
        token: String,
        contentType: String,
        body: ByteArray,
        readTimeoutMs: Int,
    ): Pair<Int, ByteArray> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", contentType)
            conn.setRequestProperty("Accept", "application/json, audio/mpeg, application/octet-stream")
            conn.connectTimeout = 10_000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code in 300..399) return code to ByteArray(0)
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return code to bytes
        } finally {
            conn.disconnect()
        }
    }

    private fun le16(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun le32(v: Int): ByteArray =
        byteArrayOf(
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 24) and 0xFF).toByte(),
        )
}
