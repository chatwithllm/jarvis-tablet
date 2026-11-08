package net.npalakurla.jarvis.tablet

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloud STT and TTS through the ContextForge `/llm/v1` base.
 *
 * xAI's routes are POST /v1/stt and POST /v1/tts, not the OpenAI
 * audio transcription and speech paths. This client does not call STT.
 * TTS uses {llmBase}/tts with the saved LLM bearer and falls back to
 * Android speech when the gateway has no TTS route.
 *
 * Audio bytes and the bearer are never logged.
 */
object CloudVoice {

    sealed class Stt {
        data class Ok(val text: String) : Stt()
        data object Unavailable : Stt()
    }

    private const val TAG = "JarvisVoice"

    /**
     * Cloud STT is not deployed. This returns without opening a socket
     * so a turn cannot spend a transcription call.
     */
    @Suppress("UNUSED_PARAMETER")
    fun transcribeWav(context: android.content.Context, wav: ByteArray): Stt = Stt.Unavailable

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

}
