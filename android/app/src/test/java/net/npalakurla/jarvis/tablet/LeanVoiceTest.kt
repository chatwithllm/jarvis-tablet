package net.npalakurla.jarvis.tablet

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Counts real chat-completion attempts. A permit is not an HTTP post.
 * No network, no model, and no speech engine.
 */
class LeanVoiceTest {

    @Test
    fun a_plain_reply_is_one_post_and_a_400_is_not_retried() {
        val posts = ArrayList<Int>()
        val outcome = LeanChat.run(
            post = {
                posts.add(posts.size + 1)
                assistant("The kitchen light is on.")
            },
            onTools = { _, _ -> },
        )
        assertEquals(1, outcome.posts)
        assertEquals(1, posts.size)
        assertEquals("The kitchen light is on.", outcome.text)

        var attempts = 0
        val failed = runCatching {
            LeanChat.run(
                post = {
                    attempts += 1
                    throw LlmCallFailed(400, "llm HTTP 400")
                },
                onTools = { _, _ -> },
            )
        }
        assertTrue(failed.isFailure)
        assertEquals(1, attempts)
    }

    @Test
    fun tool_calls_with_text_do_not_post_again() {
        var posts = 0
        var tools = 0
        val outcome = LeanChat.run(
            post = {
                posts += 1
                assistant(
                    "Waiting for a yes.",
                    tool("propose_action", """{"entity_id":"light.kitchen"}"""),
                )
            },
            onTools = { _, calls -> tools += calls.size },
        )
        assertEquals(1, posts)
        assertEquals(1, tools)
        assertEquals(1, outcome.posts)
        assertEquals("Waiting for a yes.", outcome.text)
    }

    @Test
    fun a_tool_only_reply_gets_one_follow_up_and_stops() {
        var posts = 0
        val outcome = LeanChat.run(
            post = {
                posts += 1
                if (posts == 1) {
                    assistant("", tool("ha_get_state", """{"entity_id":"light.kitchen"}"""))
                } else {
                    assistant(
                        "It is on.",
                        tool("ha_get_state", """{"entity_id":"light.kitchen"}"""),
                    )
                }
            },
            onTools = { _, _ -> },
        )
        assertEquals(2, posts)
        assertEquals(2, outcome.posts)
        assertEquals("It is on.", outcome.text)
        assertTrue(outcome.posts <= LeanChat.MAX_POSTS)
    }

    @Test
    fun vosk_mishearings_match_time_or_weather_and_not_other_requests() {
        val timeHeard = listOf(
            "what time is it",
            "what time in it",
            "that time in it",
            "what time is it now",
            "what time did it out",
        )
        val weatherHeard = listOf(
            "what's the weather",
            "what is that where the",
            "what's that where the",
            "is that where the",
            "what is the weather like",
            "what is that where weather like",
            "what is that weather like",
        )
        val neither = listOf(
            "they'll be a short fun fact",
            "tell me your phone",
            "gave me a fun fact",
            "turn on the kitchen light",
            "lock the front door",
            "open the garage",
            "what time is the meeting",
            "where is the kitchen light",
        )
        val now = ZonedDateTime.of(2026, 11, 8, 21, 41, 0, 0, ZoneId.of(SpokenFacts.ZONE))
        timeHeard.forEach { heard ->
            assertTrue(heard, SpokenFacts.isTimeQuestion(heard))
            assertFalse(heard, SpokenFacts.isWeatherQuestion(heard))
            var states = 0
            val reply = SpokenFacts.localReply(heard, now) {
                states += 1
                null
            }
            assertEquals("It is 9:41 PM in America/New_York.", reply)
            assertEquals(0, states)
        }
        weatherHeard.forEach { heard ->
            assertTrue(heard, SpokenFacts.isWeatherQuestion(heard))
            assertFalse(heard, SpokenFacts.isTimeQuestion(heard))
        }
        neither.forEach { heard ->
            assertFalse(heard, SpokenFacts.isTimeQuestion(heard))
            assertFalse(heard, SpokenFacts.isWeatherQuestion(heard))
            assertNull(SpokenFacts.localReply(heard, now) { "[]" })
        }
        assertNull(SpokenFacts.fromGrammar("[unk]"))
        assertNull(SpokenFacts.fromGrammar("turn on the kitchen light"))
        assertEquals("what time is it", SpokenFacts.fromGrammar("what time is it"))
        assertTrue(SpokenFacts.intentGrammarJson().contains("[unk]"))
        assertTrue(SpokenFacts.intentGrammarJson().contains("what time is it"))
        assertTrue(SpokenFacts.intentGrammarJson().contains("what is the weather"))
    }

    @Test
    fun time_uses_the_clock_and_does_not_ask_for_states_or_chat() {
        val now = ZonedDateTime.of(2026, 11, 8, 21, 41, 0, 0, ZoneId.of(SpokenFacts.ZONE))
        var states = 0
        var posts = 0
        val reply = SpokenFacts.localReply("What time is it?", now) {
            states += 1
            null
        }
        assertEquals("It is 9:41 PM in America/New_York.", reply)
        assertEquals(0, states)
        assertFalse(SpokenFacts.isTimeQuestion("what time is the meeting"))
        posts += 0
        assertEquals(0, posts)
    }

    @Test
    fun weather_uses_a_fresh_home_assistant_reading_or_says_unavailable() {
        val now = Instant.parse("2026-11-08T02:30:00Z")
        val fresh = states(
            "weather.home",
            "sunny",
            "35",
            "°F",
            "2026-11-08T02:10:00Z",
        )
        val sentence = SpokenFacts.weatherSentence(fresh, now)
        assertTrue(sentence.startsWith("weather.home is 35 °F and sunny"))
        assertTrue(sentence.contains("America/New_York"))
        assertFalse(sentence.contains("I think"))

        val stale = states(
            "weather.home",
            "sunny",
            "35",
            "°F",
            "2026-11-08T00:00:00Z",
        )
        assertEquals(SpokenFacts.UNAVAILABLE, SpokenFacts.weatherSentence(stale, now))
        assertEquals(SpokenFacts.UNAVAILABLE, SpokenFacts.weatherSentence(null, now))
        assertEquals(
            SpokenFacts.UNAVAILABLE,
            SpokenFacts.weatherSentence(states("light.kitchen", "on", null, null, "2026-11-08T02:10:00Z"), now),
        )

        var posts = 0
        val reply = SpokenFacts.localReply("what is the weather", now.atZone(ZoneId.of(SpokenFacts.ZONE))) {
            fresh
        }
        assertEquals(sentence, reply)
        assertEquals(0, posts)
        assertNull(
            SpokenFacts.localReply(
                "turn on the kitchen light",
                now.atZone(ZoneId.of(SpokenFacts.ZONE)),
            ) { fresh },
        )
    }

    @Test
    fun aliases_and_revocation_still_spend_nothing() {
        val permit = TurnPermit(clock = { 0L })
        assertFalse(WakeAccept.isExactPhrase("okay computer"))
        assertFalse(WakeAccept.isExactPhrase("hey jarvis"))
        val token = permit.tryBegin(TurnPermit.Source.TALK)
        permit.revoke()
        assertFalse(permit.allowChat(token!!))
        assertFalse(permit.allowTts(token))
    }

    @Test
    fun production_turn_does_not_fall_back_or_retry() {
        val root = File("src/main/java/net/npalakurla/jarvis/tablet")
        val turn = File(root, "VoiceTurn.kt").readText()
        val direct = turn.substringAfter("private suspend fun runTurn(token: Long)")
            .substringBefore("private suspend fun finishRelay")
        assertFalse(direct.contains("transport.runTurn"))
        assertFalse(direct.contains("relayTurn("))
        val speech = File(root, "DeviceSpeech.kt").readText()
        val speak = speech.substringAfter("suspend fun speak(").substringBefore("private suspend fun playMp3")
        assertFalse(speak.contains("speakAndroid"))
        val chat = File(root, "ContextForgeLlmTransport.kt").readText()
        assertFalse(chat.contains("retriedWithoutTools"))
        assertFalse(chat.contains("MAX_ROUNDS"))
        assertTrue(chat.contains("LeanChat.run"))
    }

    private fun assistant(text: String, tool: JSONObject? = null): JSONObject {
        val message = JSONObject().put("role", "assistant").put("content", text)
        if (tool != null) message.put("tool_calls", JSONArray().put(tool))
        return JSONObject().put(
            "choices",
            JSONArray().put(JSONObject().put("message", message)),
        )
    }

    private fun tool(name: String, args: String): JSONObject =
        JSONObject()
            .put("id", "call_$name")
            .put(
                "function",
                JSONObject().put("name", name).put("arguments", args),
            )

    private fun states(
        id: String,
        state: String,
        temperature: String?,
        unit: String?,
        observed: String,
    ): String {
        val attrs = JSONObject()
        if (temperature != null) attrs.put("temperature", temperature)
        if (unit != null) attrs.put("temperature_unit", unit)
        return JSONArray()
            .put(
                JSONObject()
                    .put("entity_id", id)
                    .put("state", state)
                    .put("last_updated", observed)
                    .put("attributes", attrs),
            )
            .toString()
    }
}
