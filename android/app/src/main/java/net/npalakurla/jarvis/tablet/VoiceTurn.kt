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
import java.io.File
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Turn-based voice (PRD v2.2 FR-24/25/26).
 *
 * Two transports, picked per turn by [RoutingVoiceTransport]:
 * - Direct: on-device [DeviceSpeech] (SpeechRecognizer + TextToSpeech)
 *   and ContextForge `POST /llm/v1/chat/completions`, when the
 *   `cf-jarvis-tablet-llm` bearer is saved.
 * - Fallback: the Mini voice relay (`POST /voice/turn`), which still
 *   runs Whisper + the HA Assist pipeline + Piper. Used when the LLM
 *   bearer is missing, speech recognition cannot start, or the direct
 *   call fails (401/403, unreachable). A failed direct attempt listens
 *   a second time for the relay — the recognizer already consumed the
 *   first utterance, and the relay needs WAV audio.
 *
 * Proposed actions go through [PolicyController] (confirm card, spoken
 * yes or tap, 10 s timeout = cancel, source binding). Locks, garage,
 * and alarm are refused. The tablet never holds a raw HA token.
 *
 * Turn states: idle -> recording -> thinking -> speaking -> idle.
 * TTS volume: 20% during quiet hours (FR-26), full otherwise.
 * No audio is stored on the tablet. No audio is ever logged.
 */
class VoiceTurn(
    private val context: Context,
    private val bridge: JarvisBridge,
    private val transport: Transport,
    private val isQuietHours: () -> Boolean,
) {

    /** One turn's result from the relay or the direct client. */
    data class TurnResult(
        val transcript: String,
        val responseText: String,
        /** TTS audio as WAV bytes (Piper output), or null when the device speaks the text. */
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
        /** HA service to run after confirm. Null on the relay path (the Mini stored it). */
        val service: String? = null,
    )

    /**
     * The voice backend behind a turn. [MiniRelayTransport] speaks WAV.
     * [RoutingVoiceTransport] adds the direct text path.
     */
    interface Transport {
        /** True when the LLM bearer is saved and this turn should try ContextForge first. */
        fun preferDirect(): Boolean = false

        suspend fun runTurn(audioWav: ByteArray): Result<TurnResult>

        /** Direct path: transcript already recognized on the device. */
        suspend fun completeText(transcript: String): Result<TurnResult> =
            Result.failure(IllegalStateException("text transport not available"))

        suspend fun executeAction(actionId: String): Result<Unit>

        /** Drop a proposed action that was not confirmed. */
        fun discardAction(actionId: String) {}
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val deviceSpeech = DeviceSpeech(context)
    private var turnJob: Job? = null
    private var recorder: AudioRecord? = null
    @Volatile private var recording = false
    private val confirmLock = Any()
    private var pendingConfirm: CompletableDeferred<Boolean>? = null
    private var pendingConfirmId: String? = null
    @Volatile private var confirmOpen = false

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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "turn failed: ${e.javaClass.simpleName}")
                bridge.sendEvent(
                    "error",
                    JSONObject()
                        .put("code", "voice_failed")
                        .put("message", "Voice turn failed. Try again."),
                )
            } finally {
                WakeService.muteTailMs = 0L
                // Inside launch {}, bare isActive is CoroutineScope.isActive (a val).
                this@VoiceTurn.isActive = false
            }
        }
    }

    /**
     * Debug path: run one full turn from a 16 kHz mono WAV instead of
     * the microphone. `direct` transcribes with the bundled Vosk model
     * and posts the text to /llm/v1. Otherwise the WAV goes to the relay.
     * An unsafe transcript is refused before either call.
     */
    fun startFromWav(path: String?, direct: Boolean = false) {
        Log.i(VOICE, "debug_wav")
        if (isActive) {
            Log.i(VOICE, "result=busy")
            return
        }
        val file = path?.let { File(it) }
        val pcm = if (file != null && file.isFile) DebugWake.decodeWav(file) else null
        if (file == null || pcm == null) {
            Log.i(VOICE, "result=bad_wav")
            bridge.sendEvent(
                "error",
                JSONObject().put("code", "voice_failed").put("message", "That WAV could not be used."),
            )
            return
        }
        val wav = file.readBytes()
        isActive = true
        bridge.sendEvent("thinking", JSONObject().put("step", "listening"))
        WakeService.muteTailMs = TURN_MUTE_MS
        turnJob = scope.launch {
            try {
                val heard = transcribeWav(wav, pcm)
                if (SafetyGate.block(heard)) {
                    showRefused()
                    return@launch
                }
                if (direct) {
                    Log.i(VOICE, "path=direct")
                    if (heard.isNullOrBlank()) {
                        Log.i(VOICE, "result=silent")
                        bridge.sendEvent("tts_finished", JSONObject())
                        return@launch
                    }
                    val llm = transport.completeText(heard)
                    if (llm.isSuccess) {
                        Log.i(VOICE, "result=ok")
                        present(llm.getOrThrow())
                        return@launch
                    }
                    val http = (llm.exceptionOrNull() as? LlmCallFailed)?.http ?: 0
                    if (http == 401 || http == 403) {
                        Log.i(VOICE, "result=llm_auth")
                    }
                    Log.i(VOICE, "path=relay")
                } else {
                    Log.i(VOICE, "path=relay")
                }
                val result = transport.runTurn(wav)
                val value = result.getOrElse {
                    Log.i(VOICE, "result=error")
                    bridge.sendEvent(
                        "error",
                        JSONObject()
                            .put("code", "ha_unreachable")
                            .put("message", "Could not reach the voice relay. Check the Mini."),
                    )
                    return@launch
                }
                Log.i(VOICE, "result=ok")
                present(value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(VOICE, "result=error")
                bridge.sendEvent(
                    "error",
                    JSONObject().put("code", "voice_failed").put("message", "Voice turn failed. Try again."),
                )
            } finally {
                WakeService.muteTailMs = 0L
                this@VoiceTurn.isActive = false
            }
        }
    }

    /** Cancel the current turn (orb tapped while listening, or Back). */
    fun cancel() {
        recording = false
        confirmOpen = false
        turnJob?.cancel()
        isActive = false
        bridge.sendEvent("thinking", JSONObject().put("step", "cancelled"))
    }

    /** Release the speech engine. MainActivity calls this from onDestroy. */
    fun close() {
        cancel()
        deviceSpeech.shutdown()
    }

    /**
     * Called by MainActivity after PolicyController.resolve() for a
     * confirm card this turn raised. Completes the waiting turn so it
     * can execute (confirmed) or skip (declined/expired) the action.
     */
    fun onConfirmResolved(id: String, confirmed: Boolean) {
        synchronized(confirmLock) {
            if (id != pendingConfirmId) return
            val waiting = pendingConfirm ?: return
            if (!waiting.isCompleted) waiting.complete(confirmed)
        }
    }

    private suspend fun runTurn() {
        bridge.sendEvent("thinking", JSONObject().put("step", "listening"))
        if (!transport.preferDirect()) {
            Log.i(VOICE, "path=relay")
            Log.i(VOICE, "mic_start")
            finishRelay(relayTurn(announce = false), directError = null)
            return
        }
        Log.i(VOICE, "path=direct")
        Log.i(VOICE, "mic_start")
        val heard = try {
            deviceSpeech.listen(
                onPartial = { text ->
                    bridge.sendEvent("transcript_partial", JSONObject().put("text", text))
                },
                onLevel = { level ->
                    bridge.sendEvent("listening_level", JSONObject().put("level", level))
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "listen failed: ${e.javaClass.simpleName}")
            DeviceSpeech.ListenResult(null, "client")
        }
        val transcript = heard.transcript?.trim().orEmpty()
        if (transcript.isNotEmpty()) {
            if (SafetyGate.block(transcript)) {
                showRefused()
                return
            }
            bridge.sendEvent("thinking", JSONObject().put("step", "transcribing"))
            val direct = transport.completeText(transcript)
            if (direct.isSuccess) {
                Log.i(VOICE, "result=ok")
                present(direct.getOrThrow())
                return
            }
            val failure = direct.exceptionOrNull()
            val http = (failure as? LlmCallFailed)?.http ?: 0
            Log.w(TAG, "direct llm failed: ${failure?.message}")
            // 401/403 (and any other direct failure) continues on the
            // Mini relay instead of ending the turn on the error.
            if (http == 401 || http == 403) {
                Log.i(VOICE, "result=llm_auth")
            }
            Log.i(VOICE, "path=relay")
            Log.i(VOICE, "mic_start")
            finishRelay(relayTurn(announce = true), failure)
            return
        }
        if (isSilence(heard.error)) {
            // Recognizer ran and nobody spoke. Don't open a second listen.
            Log.i(VOICE, "result=silent")
            bridge.sendEvent("tts_finished", JSONObject())
            return
        }
        Log.w(TAG, "direct stt failed: ${heard.error}")
        Log.i(VOICE, "path=relay")
        Log.i(VOICE, "mic_start")
        finishRelay(relayTurn(announce = true), IllegalStateException("stt ${heard.error}"))
    }

    private suspend fun finishRelay(attempt: RelayAttempt, directError: Throwable?) {
        when (attempt) {
            RelayAttempt.Presented -> Log.i(VOICE, "result=ok")
            RelayAttempt.Silent -> Log.i(VOICE, "result=silent")
            RelayAttempt.Refused -> Unit
            RelayAttempt.Unconfigured -> {
                Log.i(VOICE, "result=error")
                bridge.sendEvent(
                    "error",
                    JSONObject()
                        .put("code", if (directError == null) "ha_unreachable" else "stt_failed")
                        .put("message", unconfiguredMessage(directError)),
                )
            }
            is RelayAttempt.Failed -> {
                Log.i(VOICE, "result=error")
                Log.w(TAG, "relay failed: ${attempt.error.message}")
                bridge.sendEvent(
                    "error",
                    JSONObject()
                        .put("code", "ha_unreachable")
                        .put(
                            "message",
                            if (directError != null) {
                                "Voice failed on ContextForge and the Mini relay."
                            } else {
                                "Could not reach the voice relay. Check the Mini."
                            },
                        ),
                )
            }
        }
    }

    private fun unconfiguredMessage(directError: Throwable?): String {
        if (directError == null) {
            return "No LLM token and no Mini relay URL. Set one of them in Settings."
        }
        val msg = directError.message.orEmpty()
        return if (msg.contains("401") || msg.contains("403")) {
            "The LLM token was rejected. Check the cf-jarvis-tablet-llm bearer in Settings."
        } else if (msg.startsWith("stt ")) {
            "Speech recognition is unavailable, and no Mini relay is configured."
        } else {
            "Could not reach ContextForge, and no Mini relay is configured."
        }
    }

    private sealed class RelayAttempt {
        data object Presented : RelayAttempt()
        data object Silent : RelayAttempt()
        data object Unconfigured : RelayAttempt()
        data object Refused : RelayAttempt()
        data class Failed(val error: Throwable) : RelayAttempt()
    }

    /**
     * Record one utterance and send it to the Mini relay.
     * [announce] is set when this is the fallback after a direct-path
     * failure, so the person knows to speak again.
     */
    private suspend fun relayTurn(announce: Boolean): RelayAttempt {
        if (announce) {
            bridge.sendEvent("thinking", JSONObject().put("step", "relay_listen"))
        }
        val audio = recordUntilSilence() ?: run {
            bridge.sendEvent("tts_finished", JSONObject())
            return RelayAttempt.Silent
        }
        val shorts = wavToPcm(audio)?.let { pcm16leToShorts(it) }
        val heard = if (shorts != null) transcribeWav(audio, shorts) else null
        if (SafetyGate.block(heard)) {
            showRefused()
            return RelayAttempt.Refused
        }
        bridge.sendEvent("thinking", JSONObject().put("step", "transcribing"))
        val result = transport.runTurn(audio)
        val value = result.getOrElse { return RelayAttempt.Failed(it) }
        present(value)
        return RelayAttempt.Presented
    }

    /** Cloud STT first. Vosk free-form is the offline fallback. */
    private fun transcribeWav(wav: ByteArray, pcm: ShortArray): String? {
        if (TokenStore.hasLlmCredentials(context)) {
            when (val cloud = CloudVoice.transcribeWav(context, wav)) {
                is CloudVoice.Stt.Ok -> {
                    Log.i(VOICE, "stt=cloud")
                    return cloud.text.trim().ifEmpty { null }
                }
                CloudVoice.Stt.Unavailable -> Unit
            }
        }
        Log.i(VOICE, "stt=vosk")
        return VoskWakeEngine.transcribe(context, pcm)
    }

    private fun showRefused() {
        bridge.sendEvent(
            "refused",
            JSONObject()
                .put("reason", "denied")
                .put("message", "That is not allowed by voice."),
        )
        bridge.sendEvent("tts_finished", JSONObject())
    }

    private suspend fun present(result: TurnResult) {
        if (result.transcript.isNotBlank()) {
            bridge.sendEvent(
                "transcript_final",
                JSONObject().put("text", result.transcript),
            )
        }
        val action = result.proposedAction
        val outcome = if (action != null) confirmAction(action) else null
        if (action != null && outcome != "confirmed") transport.discardAction(action.id)
        val text = when (outcome) {
            null -> result.responseText
            "confirmed" -> result.responseText.ifBlank { "Confirmed." }
            "declined" -> "Cancelled."
            "refused" -> "That is not allowed by voice."
            else -> ""
        }
        // Piper audio is the model's reply. After a no / refusal / failed
        // execute, don't play it — it may claim the change already happened.
        val wav = if (outcome == null || outcome == "confirmed") result.ttsAudio else null
        speakResponse(text, wav)
    }

    /**
     * Confirm gate (FR-24/25/55). Returns confirmed, declined, refused,
     * or failed (confirmed, but the execute call did not succeed).
     */
    private suspend fun confirmAction(action: ProposedAction): String {
        if (SafetyGate.refusesTool(action.domain, action.entityId, action.service, action.summary) ||
            SafetyGate.refuses(action.newState)
        ) {
            Log.i(VOICE, "refused=safety")
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
            return "refused"
        }
        val pending = PolicyController.propose(
            summary = action.summary,
            domain = action.domain,
            entityId = action.entityId,
            newState = action.newState,
            sourceDevice = MainActivity.DEVICE_ID,
            service = action.service,
        )
        if (pending == null) {
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
            return "refused"
        }
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
        val deferred = CompletableDeferred<Boolean>()
        synchronized(confirmLock) {
            pendingConfirm = deferred
            pendingConfirmId = pending.id
            confirmOpen = true
        }
        val confirmed = coroutineScope {
            val listenJob = launch {
                try {
                    listenForSpokenConfirm(pending.id, pending.createdAtMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "confirm listen failed: ${e.javaClass.simpleName}")
                }
            }
            try {
                val remaining = (
                    pending.createdAtMs + PolicyController.CONFIRM_TIMEOUT_MS -
                        System.currentTimeMillis()
                    ).coerceAtLeast(0L)
                withTimeoutOrNull(remaining) { deferred.await() } ?: false
            } finally {
                listenJob.cancel()
                synchronized(confirmLock) {
                    confirmOpen = false
                    if (!deferred.isCompleted) {
                        PolicyController.resolve(
                            pending.id,
                            confirmed = false,
                            sourceDevice = MainActivity.DEVICE_ID,
                        )
                    }
                    pendingConfirm = null
                    pendingConfirmId = null
                }
            }
        }
        if (!confirmed) {
            bridge.sendEvent(
                "confirm_resolved",
                JSONObject().put("id", pending.id).put("outcome", "declined"),
            )
            return "declined"
        }
        val executed = transport.executeAction(action.id)
        if (executed.isFailure) {
            Log.w(TAG, "execute failed: ${executed.exceptionOrNull()?.message}")
            bridge.sendEvent(
                "error",
                JSONObject()
                    .put("code", "ha_unreachable")
                    .put("message", "Confirmed, but the action could not run."),
            )
            return "failed"
        }
        bridge.sendEvent(
            "confirm_resolved",
            JSONObject().put("id", pending.id).put("outcome", "confirmed"),
        )
        return "confirmed"
    }

    /** Listen for a short yes/no until the confirm window closes (FR-24). Tap still works. */
    private suspend fun listenForSpokenConfirm(policyId: String, createdAtMs: Long) {
        WakeService.pauseMic()
        try {
            val deadline = createdAtMs + PolicyController.CONFIRM_TIMEOUT_MS
            while (coroutineContext.isActive && System.currentTimeMillis() < deadline) {
                val stillOpen = synchronized(confirmLock) {
                    confirmOpen && pendingConfirmId == policyId
                }
                if (!stillOpen) return
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) return
                val heard = deviceSpeech.listen(
                    timeoutMs = remaining.coerceAtMost(8_000L),
                    manageWakeMic = false,
                )
                val said = heard.transcript?.let { interpretConfirm(it) }
                if (said != null) {
                    acceptSpokenConfirm(policyId, said)
                    return
                }
                if (heard.error == "recognizer_unavailable" ||
                    heard.error == "permissions" ||
                    heard.error == "client"
                ) {
                    return
                }
                delay(250)
            }
        } finally {
            WakeService.resumeMic()
        }
    }

    private fun acceptSpokenConfirm(policyId: String, saidYes: Boolean) {
        synchronized(confirmLock) {
            if (!confirmOpen || pendingConfirmId != policyId) return
            val waiting = pendingConfirm ?: return
            if (waiting.isCompleted) return
            val accepted = PolicyController.resolve(
                policyId,
                confirmed = saidYes,
                sourceDevice = MainActivity.DEVICE_ID,
            )
            if (!accepted) return
            waiting.complete(saidYes)
        }
    }

    private suspend fun speakResponse(text: String, wav: ByteArray?) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && wav == null) {
            bridge.sendEvent("tts_finished", JSONObject())
            return
        }
        bridge.sendEvent("tts_started", JSONObject().put("text", trimmed))
        try {
            if (wav != null) {
                playPcm(wav)
            } else {
                // FR-26: quiet hours 21:00-07:00 at 20% volume.
                deviceSpeech.speak(trimmed.take(600), if (isQuietHours()) 0.2f else 1.0f)
            }
        } finally {
            bridge.sendEvent("tts_finished", JSONObject())
        }
    }

    private fun isSilence(error: String?): Boolean =
        error == null || error == "no_match" || error == "speech_timeout" || error == "timeout"

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
                bridge.sendEvent("listening_level", JSONObject().put("level", level))
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
        if (pcm.isEmpty()) return
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
            .setBufferSizeInBytes(pcm.size.coerceAtLeast(2))
            .build()
        // FR-26: quiet hours 21:00-07:00 at 20% volume.
        track.setVolume(if (isQuietHours()) 0.2f else 1.0f)
        track.write(pcm, 0, pcm.size)
        track.play()
        // Block until playback finishes (static mode: position == size).
        // Cap the wait so a stuck track cannot hold the turn open.
        val maxMs = (pcm.size / 2 * 1000L / SAMPLE_RATE) + 2_000L
        val started = System.currentTimeMillis()
        while (track.playbackHeadPosition < pcm.size / 2) {
            if (System.currentTimeMillis() - started > maxMs) break
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
    private fun pcm16leToShorts(pcm: ByteArray): ShortArray {
        val n = pcm.size / 2
        val out = ShortArray(n)
        var i = 0
        while (i < n) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort()
            i++
        }
        return out
    }

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
        byteArrayOf(
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 24) and 0xFF).toByte(),
        )

    private fun shortLE(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    companion object {
        private const val TAG = "VoiceTurn"
        private const val VOICE = "JarvisVoice"
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
 * The Mini voice relay transport (mini/jarvis-mini.py, LAN only).
 * POSTs the turn audio as a WAV and parses the relay's JSON reply.
 * The relay URL is configured in Settings (TokenStore.voiceRelayUrl);
 * the relay holds the HA token, never the tablet.
 */
class MiniRelayTransport(private val context: Context) : VoiceTurn.Transport {

    override suspend fun runTurn(audioWav: ByteArray): Result<VoiceTurn.TurnResult> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val base = TokenStore.getVoiceRelayUrl(context)
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
                            service = it.optString("service", "").ifEmpty { null },
                        )
                    },
                )
            }
        }

    override suspend fun executeAction(actionId: String): Result<Unit> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val base = TokenStore.getVoiceRelayUrl(context)
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

/**
 * Prefer the direct ContextForge client when the LLM bearer is saved.
 * [VoiceTurn] falls back to [MiniRelayTransport] itself when [completeText]
 * fails; this class only routes execute/discard to whoever created the
 * action id. Relay audio turns always go to the Mini.
 */
class RoutingVoiceTransport(private val context: Context) : VoiceTurn.Transport {
    private val relay = MiniRelayTransport(context)
    private val direct = ContextForgeLlmTransport(context)

    override fun preferDirect(): Boolean = TokenStore.hasLlmCredentials(context)

    override suspend fun runTurn(audioWav: ByteArray): Result<VoiceTurn.TurnResult> =
        relay.runTurn(audioWav)

    override suspend fun completeText(transcript: String): Result<VoiceTurn.TurnResult> =
        direct.complete(transcript)

    override suspend fun executeAction(actionId: String): Result<Unit> =
        if (direct.owns(actionId)) direct.execute(actionId) else relay.executeAction(actionId)

    override fun discardAction(actionId: String) {
        direct.discard(actionId)
    }
}
