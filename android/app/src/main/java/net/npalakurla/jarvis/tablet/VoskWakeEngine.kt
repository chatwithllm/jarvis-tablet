package net.npalakurla.jarvis.tablet

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.math.sqrt

/**
 * Grammar-restricted offline wake word (Vosk small en-US).
 *
 * The openWakeWord hey_jack.onnx model was trained on synthetic speech
 * and does not fire on real voices. This replaces that detector. The
 * recognizer only considers the phrases in wake/phrases.json (armed
 * phrases, look-alikes, and the unknown token). A hit requires the
 * final text to be an armed phrase, not a reject (hey jarvis is always
 * a reject), and every word at or above the confidence floor.
 *
 * Audio is fed only while it is above the noise floor, with ~300 ms of
 * pre-roll and 0.8 s of hang. A partial that still ends in an armed
 * phrase after ~300 ms is finalized early. The same [Session] runs for
 * the microphone and for a debug WAV.
 */
object VoskWakeEngine {

    data class Hit(val phrase: String, val conf: Float)

    @Volatile var state: String = "missing"
        private set

    private val lock = Any()
    @Volatile var injecting: Boolean = false
        private set

    private var model: Model? = null
    private var session: Session? = null
    private var catalog: WakePhrases.Catalog? = null

    fun ensureReady(context: Context): Boolean {
        synchronized(lock) {
            if (state == "ready" && session != null) return true
            return try {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val loaded = catalog ?: WakePhrases.load(context).also { catalog = it }
                val dir = File(context.filesDir, "vosk-model")
                if (!File(dir, "conf/model.conf").exists()) {
                    copyAssetDir(context, "vosk-model", dir)
                }
                if (!File(dir, "conf/model.conf").exists()) {
                    state = "missing"
                    Log.w(WakeLog.TAG, "vosk model asset missing")
                    return false
                }
                val voskModel = model ?: Model(dir.absolutePath).also { model = it }
                val recognizer = Recognizer(voskModel, loaded.sampleRate.toFloat(), WakePhrases.grammarJson(loaded))
                recognizer.setWords(true)
                recognizer.setPartialWords(true)
                session = Session(recognizer, loaded)
                state = "ready"
                true
            } catch (e: Exception) {
                state = "error"
                Log.w(WakeLog.TAG, "vosk load failed: ${e.javaClass.simpleName}")
                false
            }
        }
    }

    /** One microphone frame. Null when the frame is not a wake. */
    fun onMicrophoneFrame(context: Context, frame: ShortArray, count: Int): Hit? {
        if (injecting) return null
        synchronized(lock) {
            if (!ensureReady(context)) return null
            val active = session ?: return null
            return active.onFrame(frame, count, WakeConfig.current(context, active.catalog))
        }
    }

    /**
     * Push a 16 kHz mono PCM buffer through the same frame pipeline the
     * microphone uses. Returns the first hit, or null.
     */
    fun runPcm(context: Context, pcm: ShortArray): Hit? {
        injecting = true
        try {
            synchronized(lock) {
                if (!ensureReady(context)) return null
                val active = session ?: return null
                active.reset()
                val settings = WakeConfig.current(context, active.catalog)
                val frame = active.catalog.frameSamples
                var offset = 0
                while (offset < pcm.size) {
                    val n = minOf(frame, pcm.size - offset)
                    val slice = ShortArray(n)
                    System.arraycopy(pcm, offset, slice, 0, n)
                    active.onFrame(slice, n, settings)?.let { return it }
                    offset += n
                }
                return active.finish(settings)
            }
        } finally {
            injecting = false
        }
    }

    fun close() {
        synchronized(lock) {
            session?.close()
            session = null
            runCatching { model?.close() }
            model = null
            state = "missing"
        }
    }

    private inner class Session(
        private val recognizer: Recognizer,
        val catalog: WakePhrases.Catalog,
    ) {
        private val preRoll = ArrayDeque<ShortArray>()
        private var preRollSamples = 0
        private var feeding = false
        private var hangLeft = 0
        private var noiseFloor = 0.02
        private var matchPhrase: String? = null
        private var matchSamples = 0

        private val preRollMax = catalog.sampleRate * catalog.preRollMs / 1000
        private val hangSamples = catalog.sampleRate * catalog.hangMs / 1000
        private val earlySamples = catalog.sampleRate * catalog.earlyFinalizeMs / 1000

        fun reset() {
            preRoll.clear()
            preRollSamples = 0
            feeding = false
            hangLeft = 0
            matchPhrase = null
            matchSamples = 0
            runCatching { recognizer.reset() }
        }

        fun onFrame(frame: ShortArray, count: Int, settings: WakeConfig.Settings): Hit? {
            if (count <= 0) return null
            val level = rms(frame, count)
            val hot = level >= maxOf(noiseFloor * catalog.energyMultiplier, catalog.energyFloor)
            if (!hot && !feeding) {
                noiseFloor = noiseFloor * 0.98 + level * 0.02
                pushPreRoll(frame, count)
                return null
            }
            if (!feeding) {
                feeding = true
                hangLeft = hangSamples
                for (chunk in preRoll) {
                    accept(chunk, chunk.size, settings)?.let { return it }
                }
                preRoll.clear()
                preRollSamples = 0
            }
            if (!hot) {
                hangLeft -= count
            } else {
                hangLeft = hangSamples
            }
            val hit = accept(frame, count, settings)
            if (hit != null) return hit
            if (hangLeft <= 0) return finish(settings)
            return null
        }

        fun finish(settings: WakeConfig.Settings): Hit? {
            if (!feeding && matchPhrase == null) return null
            val hit = decide(recognizer.finalResult, settings)
            reset()
            return hit
        }

        fun close() {
            runCatching { recognizer.close() }
        }

        private fun accept(frame: ShortArray, count: Int, settings: WakeConfig.Settings): Hit? {
            val ended = recognizer.acceptWaveForm(frame, count)
            if (ended) {
                val hit = decide(recognizer.result, settings)
                recognizer.reset()
                feeding = false
                matchPhrase = null
                matchSamples = 0
                return hit
            }
            return notePartial(count, settings)
        }

        private fun notePartial(count: Int, settings: WakeConfig.Settings): Hit? {
            val text = normalize(
                runCatching { JSONObject(recognizer.partialResult).optString("partial") }.getOrDefault(""),
            )
            if (endsWithAny(text, catalog.rejects)) {
                matchPhrase = null
                matchSamples = 0
                return null
            }
            val phrase = endingPhrase(text, settings.enabled)
            if (phrase == null || phrase != matchPhrase) {
                matchPhrase = phrase
                matchSamples = if (phrase == null) 0 else count
                return null
            }
            matchSamples += count
            if (matchSamples < earlySamples) return null
            // Forcing a final here drops audio when the second word's
            // confidence is still climbing. Accept only when the partial
            // already has a per-word score at or above the floor; otherwise
            // keep listening until Vosk endpoints or the hang expires.
            val partial = runCatching { JSONObject(recognizer.partialResult) }.getOrNull()
                ?: return null
            val hit = decide(partialWordsAsFinal(partial), settings) ?: return null
            recognizer.reset()
            feeding = false
            matchPhrase = null
            matchSamples = 0
            return hit
        }

        /** Map a partial JSON object onto the final-result shape [evaluate] reads. */
        private fun partialWordsAsFinal(partial: JSONObject): String {
            val words = partial.optJSONArray("partial_result") ?: return ""
            return JSONObject()
                .put("text", partial.optString("partial"))
                .put("result", words)
                .toString()
        }

        private fun decide(json: String, settings: WakeConfig.Settings): Hit? =
            evaluate(json, settings, catalog.rejects)

        private fun pushPreRoll(frame: ShortArray, count: Int) {
            val copy = frame.copyOf(count)
            preRoll.addLast(copy)
            preRollSamples += count
            while (preRollSamples > preRollMax && preRoll.isNotEmpty()) {
                preRollSamples -= preRoll.removeFirst().size
            }
        }
    }

    /**
     * A hit is an armed phrase, said in full, with every word at or
     * above [WakeConfig.Settings.threshold]. Rejects and [unk] are not hits.
     */
    internal fun evaluate(json: String, settings: WakeConfig.Settings, rejects: Set<String>): Hit? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val text = normalize(obj.optString("text"))
        if (text.isEmpty() || text == "[unk]") return null
        if (text !in settings.enabled || text in rejects || text == "hey jarvis") return null
        val words = obj.optJSONArray("result") ?: return null
        if (words.length() == 0) return null
        var minConf = 1f
        val spoken = ArrayList<String>(words.length())
        for (i in 0 until words.length()) {
            val word = words.optJSONObject(i) ?: return null
            val conf = word.optDouble("conf", 0.0).toFloat()
            if (conf < settings.threshold) return null
            if (conf < minConf) minConf = conf
            spoken.add(normalize(word.optString("word")))
        }
        if (spoken.joinToString(" ") != text) return null
        return Hit(text, minConf)
    }

    internal fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[^a-z\\[\\] ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    internal fun endingPhrase(text: String, phrases: Collection<String>): String? =
        phrases.filter { text == it || text.endsWith(" $it") }.maxByOrNull { it.length }

    private fun endsWithAny(text: String, phrases: Collection<String>): Boolean =
        endingPhrase(text, phrases) != null

    private fun rms(frame: ShortArray, count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) {
            val v = frame[i] / 32768.0
            sum += v * v
        }
        return sqrt(sum / count.coerceAtLeast(1))
    }

    private fun copyAssetDir(context: Context, assetPath: String, dest: File) {
        val kids = context.assets.list(assetPath)
        if (kids == null || kids.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            return
        }
        dest.mkdirs()
        for (kid in kids) {
            copyAssetDir(context, "$assetPath/$kid", File(dest, kid))
        }
    }
}
