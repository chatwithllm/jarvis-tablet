package net.npalakurla.jarvis.tablet

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Phase 2 — openWakeWord inference (PRD v2.2: hey_jack.onnx, 16 kHz,
 * 80 ms frames, ONNX Runtime).
 *
 * Mirrors the openWakeWord Python pipeline (model.py / utils.py,
 * Apache-2.0) exactly, so scores match the reference implementation:
 *
 *   80 ms frame (1280 samples @ 16 kHz)
 *     -> melspectrogram.onnx on the last 1760 samples (1280 + 480
 *        lookback), transform spec/10 + 2, ~8 new mel rows (32 bins)
 *     -> embedding_model.onnx on the last 76 mel rows
 *        [1, 76, 32, 1] -> one 96-dim embedding
 *     -> hey_jack.onnx on the last 16 embeddings [1, 16, 96]
 *        -> one score 0..1
 *
 * The first 5 frames return 0 (warm-up, same as openWakeWord).
 * Buffers are capped like the reference: mel 970 rows (~10 s),
 * features 120 rows (~10 s).
 *
 * States: "missing" (no hey_jack.onnx yet — normal until the Phase 2
 * training run lands it), "ready", "error" (a shared model failed
 * to load, or inference threw). Until the model exists, [WakeService]
 * runs mic-level metering and tap-to-talk only; ambient listening
 * never opens a Grok session.
 */
object WakeModel {
    var state: String = "missing"
        private set

    private var melspecSession: OrtSession? = null
    private var embeddingSession: OrtSession? = null
    private var classifierSession: OrtSession? = null

    /** How many embedding frames the classifier consumes (read from its input shape; 16 standard). */
    private var classifierFrames: Int = 16

    private val melBuffer = ArrayDeque<FloatArray>()
    private val featureBuffer = ArrayDeque<FloatArray>()
    private val rawSamples = ArrayDeque<Short>()
    private var framesSeen = 0

    private const val MEL_BINS = 32
    private const val MEL_WINDOW = 76
    private const val EMBEDDING_DIM = 96
    private const val MEL_MAX_ROWS = 970
    private const val FEATURE_MAX_ROWS = 120
    private const val RAW_LOOKBACK = 480 // 160*3, same as openWakeWord
    private const val WARMUP_FRAMES = 5

    /** Load the three ONNX models from assets/wake/. Safe to call repeatedly. */
    @Synchronized
    fun load(context: Context): Boolean {
        if (classifierSession != null && melspecSession != null && embeddingSession != null) {
            return true
        }
        return try {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
            }
            melspecSession = loadSession(context, env, opts, "wake/melspectrogram.onnx")
            embeddingSession = loadSession(context, env, opts, "wake/embedding_model.onnx")
            try {
                classifierSession = loadSession(context, env, opts, "wake/hey_jack.onnx")
                // Read how many feature frames the classifier expects.
                val info = classifierSession!!.inputInfo.values.first().info
                if (info is ai.onnxruntime.TensorInfo) {
                    val shape = info.shape
                    if (shape.size >= 2 && shape[1] > 0) classifierFrames = shape[1].toInt()
                }
            } catch (e: java.io.IOException) {
                // No wake-word model yet — expected until Phase 2 training lands.
                classifierSession = null
            }
            state = if (classifierSession != null) "ready" else "missing"
            classifierSession != null
        } catch (e: Exception) {
            state = "error"
            false
        }
    }

    private fun loadSession(
        context: Context,
        env: OrtEnvironment,
        opts: OrtSession.SessionOptions,
        asset: String,
    ): OrtSession {
        val bytes = context.assets.open(asset).use { it.readBytes() }
        return env.createSession(bytes, opts)
    }

    /**
     * Wake score for one 80 ms frame (1280 samples @ 16 kHz), 0..1.
     * Runs the full mel -> embedding -> classifier pipeline. Returns 0
     * when the model is not ready, while warming up, or when there is
     * not enough buffered history yet.
     */
    @Synchronized
    fun score(frame: ShortArray): Float {
        if (state != "ready") return 0f
        framesSeen++
        if (framesSeen <= WARMUP_FRAMES) return 0f
        return try {
            // 1. Accumulate raw audio; keep the lookback window.
            for (s in frame) rawSamples.addLast(s)
            while (rawSamples.size > 1280 + RAW_LOOKBACK) rawSamples.removeFirst()
            if (rawSamples.size < 1280 + RAW_LOOKBACK) return 0f

            // 2. Mel spectrogram over the last 1760 samples.
            val audio = FloatArray(rawSamples.size) { rawSamples.elementAt(it).toFloat() }
            val mel = runMelspec(audio) ?: return 0f
            for (row in mel) {
                melBuffer.addLast(row)
                while (melBuffer.size > MEL_MAX_ROWS) melBuffer.removeFirst()
            }

            // 3. One embedding from the last 76 mel rows.
            if (melBuffer.size < MEL_WINDOW) return 0f
            val embedding = runEmbedding(melBuffer.takeLast(MEL_WINDOW)) ?: return 0f
            featureBuffer.addLast(embedding)
            while (featureBuffer.size > FEATURE_MAX_ROWS) featureBuffer.removeFirst()

            // 4. Classifier over the last N embeddings.
            if (featureBuffer.size < classifierFrames) return 0f
            runClassifier(featureBuffer.takeLast(classifierFrames)) ?: 0f
        } catch (e: Exception) {
            state = "error"
            0f
        }
    }

    /** melspectrogram.onnx: [1, N] float32 -> rows of 32 mel bins (spec/10 + 2). */
    private fun runMelspec(audio: FloatArray): List<FloatArray>? {
        val session = melspecSession ?: return null
        val inputName = session.inputNames.first()
        val input = OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(),
            FloatBuffer.wrap(audio),
            longArrayOf(1, audio.size.toLong()),
        )
        val output = session.run(mapOf(inputName to input))
        val raw = (output[0].value as? FloatArray) ?: return null
        val frames = raw.size / MEL_BINS
        if (frames <= 0) return null
        return List(frames) { f ->
            FloatArray(MEL_BINS) { b -> raw[f * MEL_BINS + b] / 10f + 2f }
        }
    }

    /** embedding_model.onnx: [1, 76, 32, 1] float32 -> one 96-dim embedding. */
    private fun runEmbedding(melRows: List<FloatArray>): FloatArray? {
        val session = embeddingSession ?: return null
        val inputName = session.inputNames.first()
        val flat = FloatArray(MEL_WINDOW * MEL_BINS)
        for (r in melRows.indices) {
            melRows[r].copyInto(flat, r * MEL_BINS)
        }
        val input = OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(),
            FloatBuffer.wrap(flat),
            longArrayOf(1, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1),
        )
        val output = session.run(mapOf(inputName to input))
        val raw = (output[0].value as? FloatArray) ?: return null
        // Output may be [96] or [1, 96]; take the first 96 values.
        if (raw.size < EMBEDDING_DIM) return null
        return raw.copyOf(EMBEDDING_DIM)
    }

    /** hey_jack.onnx: [1, N, 96] float32 -> one score 0..1. */
    private fun runClassifier(featureRows: List<FloatArray>): Float? {
        val session = classifierSession ?: return null
        val inputName = session.inputNames.first()
        val flat = FloatArray(featureRows.size * EMBEDDING_DIM)
        for (r in featureRows.indices) {
            featureRows[r].copyInto(flat, r * EMBEDDING_DIM)
        }
        val input = OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(),
            FloatBuffer.wrap(flat),
            longArrayOf(1, featureRows.size.toLong(), EMBEDDING_DIM.toLong()),
        )
        val output = session.run(mapOf(inputName to input))
        val raw = (output[0].value as? FloatArray) ?: return null
        if (raw.isEmpty()) return null
        return raw[0].coerceIn(0f, 1f)
    }

    /** Reset the streaming buffers (e.g. after a mic gap). */
    @Synchronized
    fun reset() {
        melBuffer.clear()
        featureBuffer.clear()
        rawSamples.clear()
        framesSeen = 0
    }
}
