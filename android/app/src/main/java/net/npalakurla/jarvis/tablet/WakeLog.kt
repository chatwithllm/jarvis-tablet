package net.npalakurla.jarvis.tablet

import android.util.Log
import java.util.Locale

/** Stable logcat tag for the wake pipeline, including the debug WAV inject. */
object WakeLog {
    const val TAG = "JarvisWake"

    fun detected(phrase: String, conf: Float) {
        Log.i(TAG, String.format(Locale.US, "wake_detected phrase=%s conf=%.2f", phrase, conf))
    }

    fun none() {
        Log.i(TAG, "wake_none")
    }

    /** File could not be decoded. Always followed by [none] from the caller. */
    fun wavError(message: String) {
        Log.i(TAG, "wake_wav_error $message")
    }
}
