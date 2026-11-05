package net.npalakurla.jarvis.tablet

import android.content.Context
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession

/**
 * Phase 2 — openWakeWord model holder (PRD v2.2: hey_jack.onnx, 16 kHz,
 * 80 ms frames, ONNX Runtime).
 *
 * States: "missing" (no model asset yet — normal until the Phase 2
 * training run produces hey_jack.onnx), "ready", "error".
 * Until the model exists, [WakeService] runs mic-level metering and
 * tap-to-talk only; ambient listening never opens a Grok session.
 *
 * Phase 2 still to wire: the openWakeWord feature pipeline
 * (melspectrogram + embedding + classifier) feeding [score]. The frame
 * plumbing (16 kHz, 80 ms) is already in [WakeService].
 */
object WakeModel {
    var state: String = "missing"
        private set

    private var session: OrtSession? = null

    /** Load assets/wake/hey_jack.onnx if present. Safe to call repeatedly. */
    fun load(context: Context): Boolean {
        if (session != null) return true
        return try {
            val bytes = context.assets.open("wake/hey_jack.onnx").use { it.readBytes() }
            val env = OrtEnvironment.getEnvironment()
            session = env.createSession(bytes, OrtSession.SessionOptions())
            state = "ready"
            true
        } catch (e: java.io.IOException) {
            // No model asset yet — expected until Phase 2 training lands.
            state = "missing"
            false
        } catch (e: Exception) {
            state = "error"
            false
        }
    }

    /**
     * Wake score for one 80 ms frame (1280 samples @ 16 kHz), 0..1.
     * Phase 2: replace the stub with the melspectrogram + embedding +
     * classifier pass over the loaded session.
     */
    @Suppress("UNUSED_PARAMETER")
    fun score(frame: ShortArray): Float = 0f
}
