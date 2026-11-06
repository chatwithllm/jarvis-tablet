package net.npalakurla.jarvis.tablet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

/**
 * Phase 2 — turn-based voice (PRD v2.2: HA Assist + Grok HACS, local
 * Whisper + Piper, via the Mini voice relay).
 *
 * The tablet never talks to Home Assistant directly and never holds a
 * raw HA token (issue #13). Audio goes to the Mini voice relay
 * (mini/voice-relay.py, LAN only), which holds the HA token and runs
 * the HA Assist pipeline (Whisper STT -> Grok HACS agent -> Piper TTS).
 * The relay returns the transcript, the response text, TTS audio, and
 * any action the agent proposed. Proposed actions go through
 * [PolicyController] (confirm card, 10 s timeout, source binding) —
 * the relay never executes a service call on its own; the tablet
 * sends the execute call only after the originating device confirms.
 *
 * Turn states: idle -> recording -> thinking -> speaking -> idle.
 * End of speech: 1.5 s of silence after speech, or 10 s max.
 * TTS volume: 20% during quiet hours (FR-26), full otherwise.
 * No audio is stored on the tablet; the recording buffer is dropped
 * when the turn ends. No audio is ever logged.
 */
class VoiceTurn(
    private val context: Context,
    private val bridge: JarvisBridge,
    private val transport: Transport,
    private val isQuietHours: () -> Boolean,
) {

    /** One turn's result from the relay. */
    data class TurnResult(
        val transcript: String,
        val responseText: String,
        /** TTS audio as WAV bytes (Piper output), or null when there is none. */
        val ttsAudio: ByteArray?,
        /** Action the agent proposed (null for read-only answers). */
        val proposedAction: ProposedAction?,
    )

    data class ProposedAction(
        val id: String,
        val summary: String,
        val domain: String,
        val entityId: String,
        val newState: String,
        val room: String?,
    )

    /** The relay behind the turn. Swappable (e.g. a ContextForge conversation tool later). */
    interface Transport {
        suspend fun runTurn(audioWav: ByteArray): Result<TurnResult>
        suspend fun executeAction(actionId: String): Result<Unit>
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var turnJob: Job? = null
    private var recorder: AudioRecord? = null
    @Volatile private var recording = false
    private var pendingConfirm: CompletableDeferred<Boolean>? = null
    private var pendingConfirmId: String? = null

    @Volatile var isActive: Boolean = false
        private set

    /** Start a turn (tap Talk, or a wake word). No-op when one is already running. */
    fun start() {
        if (isActive) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            bridge.sendEvent(
                "error",
                JSONObject()
                    .put("code", "mic_permission")
                    .put("message", "Microphone permission missing — voice is off."),
            )
            return
        }
        isActive = true
        // Mute wake scoring while the turn runs so the user's speech
        // does not re-trigger (FR-29 tail; restored when the turn ends).
        WakeService.muteTailMs = TURN_MUTE_MS
        turnJob = scope.launch {
            try {
                runTurn()
            } catch (e: Exception) {
                Log.w("VoiceTurn", "turn failed: ${e.message}")
                bridge.sendEvent(
                    "error",
                    JSONObject()
                        .put("code", "voice_failed")
                        .put("message", "Voice turn failed. Try again."),
                )
            } finally {
                WakeService.muteTailMs = 0L
                isActive = false
            }
        }
    }

    /** Cancel the current turn (orb tapped while listening, or Back). */
    fun cancel() {
        recording = false
        turnJob?.cancel()
        isActive = false
        bridge.sendEvent("thinking", JSONObject().put("step", "cancelled"))
    }

    /**
     * Called by MainActivity after PolicyController.resolve() for a
     * confirm card this turn raised. Completes the waiting turn so it
     * can execute (confirmed) or skip (declined/expired) the action.
     */
    fun onConfirmResolved(id: String, confirmed: Boolean) {
        if (id == pendingConfirmId) {
            pendingConfirm?.complete(confirmed)
            pendingConfirm = null
            pendingConfirmId = null
        }
    }

    private suspend fun runTurn() {
        val audio = recordUntilSilence() ?: return
        bridge.sendEvent("thinking", JSONObject().put("step", "transcribing"))
        val result = transport.runTurn(audio).getOrElse {
            bridge.sendEvent(
                "error",
                JSONObject()
                    .put("code", "ha_unreachable")
                    .put("message", "Could not reach the voice relay. Check the Mini."),
            )
            return
        }
        bridge.sendEvent(
            "transcript_final",
            JSONObject().put("text", result.transcript),
        )
        // A proposed action goes through the confirm gate first.
        val action = result.proposedAction
        if (action != null) {
            val pending = PolicyController.propose(
                summary = action.summary,
                domain = action.domain,
                entityId = action.entityId,
                newState = action.newState,
                sourceDevice = MainActivity.DEVICE_ID,
            )
            if (pending == null) {
                // Hard denylist (lock / alarm / garage, FR-25).
                bridge.sendEvent(
                    "refused",
                    JSONObject()
                        .put("reason", "denied")
                        .put("message", "That is not allowed by voice."),
                )
                bridge.sendEvent(
                    "confirm_resolved",
                    JSONObject().put("id", action.id).put("outcome", "refused"),
                )
            } else {
                bridge.sendEvent(
                    "confirm_requested",
                    JSONObject().apply {
                        put("id", pending.id)
                        put(
                            "action",
                            JSONObject().apply {
                                put("summary", pending.summary)
                                put("domain", pending.domain)
                                put("entity_id", pending.entityId)
                                put("new_state", pending.newState)
                                action.room?.let { put("room", it) }
                            },
                        )
                        put("expiry", PolicyController.CONFIRM_TIMEOUT_MS / 1000)
                    },
                )
                // Wait for the originating device's yes/no (10 s, FR-24).
                val deferred = CompletableDeferred<Boolean>()
                pendingConfirm = deferred
                pendingConfirmId = pending.id
                val confirmed = withTimeoutOrNull(PolicyController.CONFIRM_TIMEOUT_MS) {
                    deferred.await()
                } ?: false
                pendingConfirm = null
                pendingConfirmId = null
                if (confirmed) {
                    transport.executeAction(action.id).getOrElse {
                        bridge.sendEvent(
                            "error",
                            JSONObject()
                                .put("code", "ha_unreachable")
                                .put("message", "Confirmed, but the action could not run."),
                        )
                        return
                    }
                    bridge.sendEvent(
                        "confirm_resolved",
                        JSONObject().put("id", pending.id).put("outcome", "confirmed"),
                    )
                } else {
                    bridge.sendEvent(
                        "confirm_resolved",
                        JSONObject().put("id", pending.id).put("outcome", "declined"),
                    )
                }
            }
        }
        // Speak the response (if any), at quiet-hours volume when set.
        val tts = result.ttsAudio
        if (tts != null) {
            bridge.sendEvent(
                "tts_started",
                JSONObject().put("text", result.responseText),
            )
            playPcm(tts)
            bridge.sendEvent("tts_finished", JSONObject())
        } else if (result.responseText.isNotBlank()) {
            bridge.sendEvent(
                "transcript_final",
                JSONObject().put("text", result.responseText),
            )
        }
    }

    /**
     * Record until 1.5 s of silence after speech, or 10 s max.
     * Returns the audio as a WAV (16-bit 16 kHz mono), or null when
     * nothing was said / the turn was cancelled.
     */
    private fun recordUntilSilence(): ByteArray? {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(FRAME_SAMPLES * 2) * 2
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        recorder = rec
        rec.startRecording()
        recording = true
        val out = ByteArrayOutputStream()
        val frame = ShortArray(FRAME_SAMPLES)
        var heardSpeech = false
        var silenceMs = 0L
        var totalMs = 0L
        try {
            while (recording && totalMs < MAX_TURN_MS) {
                val read = rec.read(frame, 0, FRAME_SAMPLES)
                if (read <= 0) continue
                val level = rmsLevel(frame, read)
                // Speech threshold: above the room-noise floor.
                if (level > SPEECH_LEVEL) {
                    heardSpeech = true
                    silenceMs = 0
                } else if (heardSpeech) {
                    silenceMs += FRAME_MS
                }
                // Write the raw PCM.
                for (i in 0 until read) {
                    val v = frame[i].toInt()
                    out.write(v and 0xFF)
                    out.write((v shr 8) and 0xFF)
                }
                totalMs += FRAME_MS
                if (heardSpeech && silenceMs >= SILENCE_END_MS) break
            }
        } finally {
            recording = false
            runCatching { rec.stop() }
            rec.release()
            recorder = null
        }
        if (!heardSpeech) return null
        return wavBytes(out.toByteArray())
    }

    /** Play 16-bit 16 kHz PCM WAV bytes via AudioTrack. */
    private fun playPcm(wav: ByteArray) {
        val pcm = wavToPcm(wav) ?: return
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size)
            .build()
        // FR-26: quiet hours 21:00-07:00 at 20% volume.
        track.setVolume(if (isQuietHours()) 0.2f else 1.0f)
        track.write(pcm, 0, pcm.size)
        track.play()
        // Block until playback finishes (static mode: position == size).
        while (track.playbackHeadPosition < pcm.size / 2) {
            Thread.sleep(50)
        }
        track.stop()
        track.release()
    }

    private fun rmsLevel(frame: ShortArray, count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) {
            val v = frame[i] / 32768.0
            sum += v * v
        }
        return sqrt(sum / count).coerceIn(0.0, 1.0)
    }

    /** Wrap raw PCM in a 44-byte WAV header (16-bit 16 kHz mono). */
    private fun wavBytes(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val dataSize = pcm.size
        val total = 36 + dataSize
        out.write("RIFF".toByteArray())
        out.write(intLE(total))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(intLE(16))
        out.write(shortLE(1)) // PCM
        out.write(shortLE(1)) // mono
        out.write(intLE(SAMPLE_RATE))
        out.write(intLE(SAMPLE_RATE * 2)) // byte rate
        out.write(shortLE(2)) // block align
        out.write(shortLE(16)) // bits per sample
        out.write("data".toByteArray())
        out.write(intLE(dataSize))
        out.write(pcm)
        return out.toByteArray()
    }

    /** Extract the PCM payload from a WAV (find the "data" chunk). */
    private fun wavToPcm(wav: ByteArray): ByteArray? {
        var i = 0
        while (i + 8 <= wav.size) {
            val tag = String(wav, i, 4, Charsets.US_ASCII)
            val size = (wav[i + 4].toInt() and 0xFF) or
                ((wav[i + 5].toInt() and 0xFF) shl 8) or
                ((wav[i + 6].toInt() and 0xFF) shl 16) or
                ((wav[i + 7].toInt() and 0xFF) shl 24)
            if (tag == "data") return wav.copyOfRange(i + 8, minOf(i + 8 + size, wav.size))
            i += 8 + size + (size % 2)
        }
        return null
    }

    private fun intLE(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())

    private fun shortLE(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 1_280 // 80 ms @ 16 kHz
        const val FRAME_MS = 80L
        const val SILENCE_END_MS = 1_500L
        const val MAX_TURN_MS = 10_000L
        /** Mute wake scoring for this long while a turn runs (FR-29). */
        const val TURN_MUTE_MS = 15_000L
        /** RMS above this counts as speech (room-noise floor). */
        const val SPEECH_LEVEL = 0.02
    }
}

/**
 * The Mini voice relay transport (mini/voice-relay.py, LAN only).
 * POSTs the turn audio as a WAV and parses the relay's JSON reply.
 * The relay URL is configured in Settings (TokenStore.voiceRelayUrl);
 * the relay holds the HA token, never the tablet.
 */
class MiniRelayTransport(private val context: Context) : VoiceTurn.Transport {

    override suspend fun runTurn(audioWav: ByteArray): Result<VoiceTurn.TurnResult> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val base = TokenStore.getVoiceRelayUrl(context)
                    ?: throw IllegalStateException("Voice relay not configured (Settings)")
                val conn = URL("$base/voice/turn").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "audio/wav")
                conn.connectTimeout = 10_000
                conn.readTimeout = 60_000
                conn.doOutput = true
                conn.outputStream.use { it.write(audioWav) }
                if (conn.responseCode !in 200..299) {
                    throw IllegalStateException("relay HTTP ${conn.responseCode}")
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(text)
                val actionJson = json.optJSONObject("action")
                VoiceTurn.TurnResult(
                    transcript = json.optString("transcript", ""),
                    responseText = json.optString("response_text", ""),
                    ttsAudio = json.optString("tts_audio_b64", "").ifEmpty { null }?.let {
                        Base64.decode(it, Base64.DEFAULT)
                    },
                    proposedAction = actionJson?.let {
                        VoiceTurn.ProposedAction(
                            id = it.optString("id", ""),
                            summary = it.optString("summary", ""),
                            domain = it.optString("domain", ""),
                            entityId = it.optString("entity_id", ""),
                            newState = it.optString("new_state", ""),
                            room = it.optString("room", "").ifEmpty { null },
                        )
                    },
                )
            }
        }

    override suspend fun executeAction(actionId: String): Result<Unit> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val base = TokenStore.getVoiceRelayUrl(context)
                    ?: throw IllegalStateException("Voice relay not configured (Settings)")
                val conn = URL("$base/voice/execute").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                conn.doOutput = true
                val body = JSONObject().put("action_id", actionId).toString()
                conn.outputStream.bufferedWriter().use { it.write(body) }
                if (conn.responseCode !in 200..299) {
                    throw IllegalStateException("relay HTTP ${conn.responseCode}")
                }
                conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
            }
        }
}
