package net.npalakurla.jarvis.tablet

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Time and weather without asking the model to invent them.
 *
 * Time is the device clock in [ZONE]. Weather is one `weather.*` entity
 * from a Home Assistant states payload that already came through
 * ContextForge. A missing, unreadable, or stale reading is unavailable.
 * This does not call a weather service and does not speak.
 */
internal object SpokenFacts {
    const val ZONE = "America/New_York"
    val MAX_WEATHER_AGE: Duration = Duration.ofMinutes(60)

    private val TIME = Regex(
        """^(what(?:'s| is) the time|what time is it|tell me the time|current time)(?: now)?$""",
    )
    private val WEATHER = Regex(
        """\b(weather|temperature|forecast|raining|rainy|humidity|humid)\b""",
    )
    private val COMMAND = Regex("""\b(turn on|turn off|toggle|switch)\b""")

    fun normalize(text: String): String =
        text.lowercase(Locale.US)
            .replace(Regex("[^a-z' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun isTimeQuestion(text: String): Boolean = TIME.matches(normalize(text))

    fun isWeatherQuestion(text: String): Boolean {
        val spoken = normalize(text)
        if (COMMAND.containsMatchIn(spoken)) return false
        return WEATHER.containsMatchIn(spoken)
    }

    /**
     * A sentence to speak and show, or null when the transcript should
     * go to the model. [states] is called only for a weather question.
     */
    fun localReply(transcript: String, now: ZonedDateTime, states: () -> String?): String? {
        if (isTimeQuestion(transcript)) return timeSentence(now)
        if (isWeatherQuestion(transcript)) return weatherSentence(states(), now.toInstant())
        return null
    }

    fun timeSentence(now: ZonedDateTime): String {
        val zoned = now.withZoneSameInstant(ZoneId.of(ZONE))
        val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(zoned)
        return "It is $clock in $ZONE."
    }

    fun weatherSentence(payload: String?, now: Instant, maxAge: Duration = MAX_WEATHER_AGE): String {
        val entity = freshestWeather(payload) ?: return UNAVAILABLE
        val observed = entity.observed ?: return UNAVAILABLE
        val age = Duration.between(observed, now)
        if (age.isNegative || age > maxAge) return UNAVAILABLE
        if (entity.condition.isBlank() || entity.temperature == null) return UNAVAILABLE
        val whenSeen = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
            .format(observed.atZone(ZoneId.of(ZONE)))
        val unit = entity.unit?.let { " $it" }.orEmpty()
        return "${entity.id} is ${entity.temperature}$unit and ${entity.condition}, observed at $whenSeen in $ZONE."
    }

    private data class Reading(
        val id: String,
        val condition: String,
        val temperature: String?,
        val unit: String?,
        val observed: Instant?,
    )

    private fun freshestWeather(payload: String?): Reading? {
        if (payload.isNullOrBlank()) return null
        val array = statesArray(payload) ?: return null
        var best: Reading? = null
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("entity_id").trim()
            if (!id.startsWith("weather.")) continue
            val attrs = obj.optJSONObject("attributes") ?: JSONObject()
            val temp = attrs.opt("temperature")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            val reading = Reading(
                id = id,
                condition = obj.optString("state").trim(),
                temperature = temp,
                unit = attrs.optString("temperature_unit").trim().ifBlank { null },
                observed = parseInstant(obj.optString("last_updated")),
            )
            val bestTime = best?.observed
            if (best == null || (reading.observed != null && (bestTime == null || reading.observed.isAfter(bestTime)))) {
                best = reading
            }
        }
        return best
    }

    private fun statesArray(payload: String): JSONArray? {
        var value: Any? = runCatching { JSONTokener(payload.trim()).nextValue() }.getOrNull() ?: return null
        if (value is String) {
            value = runCatching { JSONTokener(value.trim()).nextValue() }.getOrNull()
        }
        return when (value) {
            is JSONArray -> value
            is JSONObject -> value.optJSONArray("states") ?: value.optJSONArray("result")
            else -> null
        }
    }

    private fun parseInstant(raw: String): Instant? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(text).toInstant() }.getOrNull()
    }

    const val UNAVAILABLE = "Weather is unavailable."
}
