package net.npalakurla.jarvis.tablet

/**
 * The only spoken wake that may start a paid turn.
 *
 * Anything else — okay computer, hey jarvis, hey jack, a shorter
 * fragment, or a longer sentence — is not a wake. A unit test that
 * this function rejects those strings is not a claim that Vosk never
 * false-positives on "hey tablet".
 */
object WakeAccept {
    const val PHRASE = "hey tablet"

    fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[^a-z ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun isExactPhrase(text: String): Boolean = normalize(text) == PHRASE

    fun accept(text: String, wordConfidence: List<Float>, threshold: Float): Boolean {
        val spoken = normalize(text)
        if (spoken != PHRASE) return false
        val words = spoken.split(' ')
        if (words.size != 2 || wordConfidence.size != words.size) return false
        return wordConfidence.all { it >= threshold }
    }
}
