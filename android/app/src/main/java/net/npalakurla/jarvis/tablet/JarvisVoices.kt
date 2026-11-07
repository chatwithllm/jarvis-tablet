package net.npalakurla.jarvis.tablet

import android.content.Context

/**
 * Stock xAI TTS voices. None of these is a clone of a real actor.
 *
 * xAI does not publish a gender field. `leo` is the stock voice
 * documented as a British accent and "authoritative and strong", which
 * is the closest preset to a calm British butler. `altair` is the
 * closest in manner ("elegant, refined, and effortlessly premium") but
 * is not documented as British. Speed is slightly above 1 so the
 * delivery is a little crisp.
 */
object JarvisVoices {

    data class Choice(val id: String, val label: String)

    const val DEFAULT_ID = "leo"

    /** Slightly fast, still inside xAI's 0.7–1.5 range. */
    const val SPEED = 1.08

    val CHOICES = listOf(
        Choice("leo", "leo — British, authoritative (default)"),
        Choice("altair", "altair — elegant, refined"),
        Choice("lux", "lux — calm, quietly wise"),
        Choice("eve", "eve — British, energetic"),
        Choice("rex", "rex — confident, clear"),
        Choice("sal", "sal — smooth, balanced"),
        Choice("ara", "ara — warm, friendly"),
        Choice("rigel", "rigel — precise, Australian"),
    )

    private const val PREFS = "jarvis_voice"
    private const val KEY = "tts_voice"

    fun selectedId(context: Context): String {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?.trim()
            ?.lowercase()
            .orEmpty()
        return if (CHOICES.any { it.id == stored }) stored else DEFAULT_ID
    }

    fun save(context: Context, id: String) {
        val clean = id.trim().lowercase()
        val value = if (CHOICES.any { it.id == clean }) clean else DEFAULT_ID
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, value)
            .apply()
    }
}
