package net.npalakurla.jarvis.tablet

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Speech for the direct ContextForge path.
 *
 * A turn records PCM, then uses cloud STT (`POST /stt`) and cloud TTS
 * (`POST /tts`). Vosk free-form and Android [TextToSpeech] are the
 * fallbacks when the gateway has no audio route. Wake-word detection
 * stays on the Vosk grammar model. Audio is not stored and not logged.
 *
 * `EXTRA_PREFER_OFFLINE` is left unset on purpose: on Android 11 the
 * flag forces a failure when no offline pack is installed. The platform
 * recognizer (on-device when the tablet has one) is the accepted STT.
 */
class DeviceSpeech(private val context: Context) {

    data class ListenResult(val transcript: String?, val error: String?)

    @Volatile private var tts: TextToSpeech? = null

    /**
     * @param manageWakeMic when true (the default), the wake service's
     * recorder is released for this listen and started again afterwards.
     * A confirm window that listens more than once passes false and
     * pauses the wake mic itself, so it is not restarted between phrases.
     */
    suspend fun listen(
        timeoutMs: Long = 12_000,
        onPartial: (String) -> Unit = {},
        onLevel: (Double) -> Unit = {},
        manageWakeMic: Boolean = true,
    ): ListenResult {
        // Release the wake service's AudioRecord first. A second open
        // from the recognition service fails on some Android 11 devices
        // while that recorder is held.
        if (manageWakeMic) WakeService.pauseMic()
        try {
            return withContext(Dispatchers.IO) {
                // Cloud STT is first. Vosk (no grammar) is the offline
                // fallback. The platform SpeechRecognizer is not used:
                // this tablet does not have one, and it was flaky where
                // it did. Wake-word detection stays on the grammar model.
                val pcm = recordPcm(onLevel) ?: return@withContext ListenResult(null, "no_match")
                transcribeRecorded(pcm)
            }
        } finally {
            if (manageWakeMic) WakeService.resumeMic()
        }
    }

    private fun transcribeRecorded(pcm: ShortArray): ListenResult {
        if (TokenStore.hasLlmCredentials(context)) {
            when (val cloud = CloudVoice.transcribeWav(context, CloudVoice.pcm16kToWav(pcm))) {
                is CloudVoice.Stt.Ok -> {
                    Log.i("JarvisVoice", "stt=cloud")
                    val text = cloud.text.trim()
                    return if (text.isEmpty()) ListenResult(null, "no_match") else ListenResult(text, null)
                }
                CloudVoice.Stt.Unavailable -> Unit
            }
        }
        Log.i("JarvisVoice", "stt=vosk")
        if (!VoskWakeEngine.modelReady(context)) return ListenResult(null, "recognizer_unavailable")
        val text = VoskWakeEngine.transcribe(context, pcm)?.trim().orEmpty()
        return if (text.isEmpty()) ListenResult(null, "no_match") else ListenResult(text, null)
    }

    /** Record one utterance and transcribe it with the wake model, no grammar. */
    private suspend fun listenWithVosk(onLevel: (Double) -> Unit): ListenResult {
        if (!VoskWakeEngine.modelReady(context)) return ListenResult(null, "recognizer_unavailable")
        val pcm = recordPcm(onLevel) ?: return ListenResult(null, "no_match")
        val text = VoskWakeEngine.transcribe(context, pcm)?.trim().orEmpty()
        return if (text.isEmpty()) ListenResult(null, "no_match") else ListenResult(text, null)
    }

    private suspend fun recordPcm(onLevel: (Double) -> Unit): ShortArray? {
        val bufferSize = android.media.AudioRecord.getMinBufferSize(
            VoiceTurn.SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(VoiceTurn.FRAME_SAMPLES * 2) * 2
        val rec = android.media.AudioRecord(
            android.media.MediaRecorder.AudioSource.MIC,
            VoiceTurn.SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        rec.startRecording()
        val frames = ArrayList<ShortArray>()
        val frame = ShortArray(VoiceTurn.FRAME_SAMPLES)
        var heard = false
        var silenceMs = 0L
        var totalMs = 0L
        try {
            while (currentCoroutineContext().isActive && totalMs < VoiceTurn.MAX_TURN_MS) {
                val read = rec.read(frame, 0, VoiceTurn.FRAME_SAMPLES)
                if (read <= 0) {
                    yield()
                    continue
                }
                var sum = 0.0
                for (i in 0 until read) {
                    val v = frame[i] / 32768.0
                    sum += v * v
                }
                val level = kotlin.math.sqrt(sum / read).coerceIn(0.0, 1.0)
                onLevel(level)
                if (level > VoiceTurn.SPEECH_LEVEL) {
                    heard = true
                    silenceMs = 0
                } else if (heard) {
                    silenceMs += VoiceTurn.FRAME_MS
                }
                frames.add(frame.copyOf(read))
                totalMs += VoiceTurn.FRAME_MS
                if (heard && silenceMs >= VoiceTurn.SILENCE_END_MS) break
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        if (!heard) return null
        val total = frames.sumOf { it.size }
        val pcm = ShortArray(total)
        var at = 0
        for (chunk in frames) {
            chunk.copyInto(pcm, at)
            at += chunk.size
        }
        return pcm
    }

    /** True = yes, false = no, null = not a confirm phrase (or nothing heard). */
    suspend fun listenYesNo(timeoutMs: Long, manageWakeMic: Boolean = true): Boolean? {
        val heard = listen(timeoutMs = timeoutMs, manageWakeMic = manageWakeMic)
        val text = heard.transcript ?: return null
        return interpretConfirm(text)
    }

    /**
     * Speak [text] at [volume] (1 = full, 0.2 = quiet hours, FR-26).
     * Returns when the utterance finishes, the engine errors, or 25 s
     * passes. A missing engine is not a turn failure — the HUD already
     * shows the text.
     */
    suspend fun speak(text: String, volume: Float) {
        val spoken = text.trim()
        if (spoken.isEmpty()) return
        if (TokenStore.hasLlmCredentials(context)) {
            val audio = withContext(Dispatchers.IO) { CloudVoice.synthesize(context, spoken) }
            val played = audio != null && audio.isNotEmpty() &&
                runCatching { withTimeout(30_000) { playMp3(audio, volume) } }.getOrDefault(false)
            if (played) {
                Log.i("JarvisVoice", "tts=cloud")
                return
            }
        }
        Log.i("JarvisVoice", "tts=android")
        speakAndroid(spoken, volume)
    }

    private suspend fun playMp3(bytes: ByteArray, volume: Float): Boolean =
        withContext(Dispatchers.IO) {
            val file = java.io.File.createTempFile("jarvis-tts", ".mp3", context.cacheDir)
            try {
                file.writeBytes(bytes)
                suspendCancellableCoroutine { cont ->
                    val player = android.media.MediaPlayer()
                    fun finish(ok: Boolean) {
                        runCatching { player.release() }
                        runCatching { file.delete() }
                        if (cont.isActive) cont.resume(ok)
                    }
                    cont.invokeOnCancellation {
                        runCatching { player.release() }
                        runCatching { file.delete() }
                    }
                    player.setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    player.setOnCompletionListener { finish(true) }
                    player.setOnErrorListener { _, _, _ ->
                        finish(false)
                        true
                    }
                    val started = runCatching {
                        player.setDataSource(file.absolutePath)
                        val level = volume.coerceIn(0f, 1f)
                        player.setVolume(level, level)
                        player.prepare()
                        player.start()
                    }
                    if (started.isFailure) finish(false)
                }
            } catch (e: Exception) {
                runCatching { file.delete() }
                false
            }
        }

    private suspend fun speakAndroid(spoken: String, volume: Float) {
        val engine = ensureEngine() ?: return
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val id = "jarvis-${System.nanoTime()}"
                val handler = Handler(Looper.getMainLooper())
                val finished = AtomicBoolean(false)
                fun finish() {
                    if (!finished.compareAndSet(false, true)) return
                    handler.removeCallbacksAndMessages(null)
                    if (cont.isActive) cont.resume(Unit)
                }
                cont.invokeOnCancellation {
                    handler.removeCallbacksAndMessages(null)
                    runCatching { engine.stop() }
                }
                handler.postDelayed({
                    runCatching { engine.stop() }
                    finish()
                }, 25_000)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id) finish()
                    }
                    override fun onError(utteranceId: String?) {
                        finish()
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        finish()
                    }
                })
                val params = Bundle().apply {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
                }
                val code = runCatching {
                    engine.speak(spoken, TextToSpeech.QUEUE_FLUSH, params, id)
                }.getOrDefault(TextToSpeech.ERROR)
                if (code == TextToSpeech.ERROR) finish()
            }
        }
    }

    fun shutdown() {
        val engine = tts
        tts = null
        val main = Handler(Looper.getMainLooper())
        main.post { runCatching { engine?.shutdown() } }
    }

    private suspend fun ensureEngine(): TextToSpeech? {
        tts?.let { return it }
        return withContext(Dispatchers.Main) {
            tts?.let { return@withContext it }
            suspendCancellableCoroutine<TextToSpeech?> { cont ->
                var engine: TextToSpeech? = null
                engine = TextToSpeech(context) { status ->
                    val created = engine
                    if (status == TextToSpeech.SUCCESS && created != null) {
                        created.language = Locale.US
                        created.setAudioAttributes(
                            android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                        )
                        tts = created
                        if (cont.isActive) cont.resume(created)
                    } else {
                        runCatching { created?.shutdown() }
                        Log.w(TAG, "tts init failed")
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
    }

    private suspend fun listenOnMain(
        timeoutMs: Long,
        onPartial: (String) -> Unit,
        onLevel: (Double) -> Unit,
    ): ListenResult = suspendCancellableCoroutine { cont ->
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val lastPartial = AtomicReference<String?>(null)
        fun destroy() {
            handler.removeCallbacksAndMessages(null)
            val drop = {
                runCatching { recognizer.cancel() }
                runCatching { recognizer.destroy() }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) drop() else handler.post { drop() }
        }
        fun finish(text: String?, error: String?) {
            if (!finished.compareAndSet(false, true)) return
            destroy()
            if (cont.isActive) {
                cont.resume(ListenResult(text?.trim()?.ifEmpty { null }, error))
            }
        }
        handler.postDelayed({
            val partial = lastPartial.get()?.trim().orEmpty()
            if (partial.isNotEmpty()) finish(partial, null) else finish(null, "timeout")
        }, timeoutMs)
        cont.invokeOnCancellation {
            if (finished.compareAndSet(false, true)) destroy()
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                // Recognizer RMS is about -2..10 dB. Map into the HUD's 0..1 level.
                val level = ((rmsdB + 2f) / 12f).toDouble().coerceIn(0.0, 1.0)
                runCatching { onLevel(level) }
            }
            override fun onBufferReceived(buffer: ByteArray?) {
                // Format is undocumented and often empty. Never log it, never keep it.
            }
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                val partial = lastPartial.get()?.trim().orEmpty()
                if (partial.isNotEmpty()) finish(partial, null)
                else finish(null, sttErrorName(error))
            }
            override fun onResults(results: Bundle) {
                val text = results
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                finish(text, null)
            }
            override fun onPartialResults(partialResults: Bundle) {
                val text = partialResults
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isEmpty()) return
                lastPartial.set(text)
                runCatching { onPartial(text) }
            }
            override fun onEvent(eventType: Int, params: Bundle) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L)
        }
        runCatching { recognizer.startListening(intent) }
            .onFailure {
                Log.w(TAG, "startListening failed: ${it.javaClass.simpleName}")
                finish(null, "client")
            }
    }

    companion object {
        private const val TAG = "DeviceSpeech"

        internal fun sttErrorName(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "audio"
            SpeechRecognizer.ERROR_CLIENT -> "client"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permissions"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
            SpeechRecognizer.ERROR_NO_MATCH -> "no_match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
            SpeechRecognizer.ERROR_SERVER -> "server"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech_timeout"
            else -> "stt_$code"
        }
    }
}

/**
 * Spoken confirm (FR-24). A short yes/no only — a longer sentence is
 * ignored so ambient speech cannot confirm a change. Null = not a decision.
 */
internal fun interpretConfirm(transcript: String): Boolean? {
    val cleaned = transcript.lowercase()
        .replace(Regex("[^a-z' ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (cleaned.isEmpty()) return null
    val words = cleaned.split(' ')
    if (words.size > 4) return null
    if (cleaned in NO_PHRASES) return false
    if (cleaned in YES_PHRASES) return true
    val first = words.first().trim('\'')
    if (first in NO_WORDS) return false
    if (first in YES_WORDS) return true
    return null
}

private val YES_WORDS = setOf("yes", "yeah", "yep", "yup", "ok", "okay", "confirm", "sure")
private val NO_WORDS = setOf("no", "nope", "nah", "cancel", "stop", "don't", "dont")
private val YES_PHRASES = setOf("do it", "go ahead", "yes please", "ok do it")
private val NO_PHRASES = setOf("do not", "no thanks", "no thank you")
