package net.npalakurla.jarvis.tablet

import android.content.Context

/**
 * Which wake phrases are armed, and the per-word confidence floor.
 * Not a secret: plain SharedPreferences, not the encrypted token store.
 * "hey jarvis" cannot be armed.
 */
object WakeConfig {
    private const val PREFS = "jarvis_wake"
    private const val KEY_PHRASES = "enabled_phrases"
    private const val KEY_CONF = "confidence"

    data class Settings(
        val enabled: Set<String>,
        val threshold: Float,
    )

    fun current(context: Context, catalog: WakePhrases.Catalog): Settings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getStringSet(KEY_PHRASES, null)
        val enabled = (stored ?: catalog.defaults)
            .map { it.trim().lowercase() }
            .filter { it in catalog.choices && it != "hey jarvis" }
            .toSet()
            .ifEmpty { catalog.defaults }
        val threshold = prefs.getFloat(KEY_CONF, catalog.confidenceDefault)
            .coerceIn(0.50f, 0.99f)
        return Settings(enabled, threshold)
    }

    fun save(context: Context, enabled: Set<String>, threshold: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_PHRASES, enabled.map { it.trim().lowercase() }.toSet())
            .putFloat(KEY_CONF, threshold.coerceIn(0.50f, 0.99f))
            .apply()
    }
}
