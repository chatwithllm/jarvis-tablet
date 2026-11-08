package net.npalakurla.jarvis.tablet

import android.content.Context
import org.json.JSONObject

/**
 * Wake grammar shared with wake/phrases.json (copied into assets at
 * build time). "hey jarvis" is a reject phrase: another household
 * device owns that wake word, and this tablet must never fire on it.
 */
object WakePhrases {

    data class Catalog(
        val choices: List<String>,
        val defaults: Set<String>,
        val rejects: Set<String>,
        val includeUnk: Boolean,
        val confidenceDefault: Float,
        val sampleRate: Int,
        val frameSamples: Int,
        val preRollMs: Int,
        val hangMs: Int,
        val earlyFinalizeMs: Int,
        val energyFloor: Double,
        val energyMultiplier: Double,
    )

    fun load(context: Context): Catalog {
        val text = runCatching {
            context.assets.open("phrases.json").bufferedReader().use { it.readText() }
        }.getOrNull()
        if (text != null) {
            runCatching { return parse(text) }
        }
        return FALLBACK
    }

    fun grammarJson(catalog: Catalog): String {
        val phrases = ArrayList<String>(catalog.choices.size + catalog.rejects.size + 1)
        phrases.addAll(catalog.choices)
        catalog.rejects.forEach { if (it !in phrases) phrases.add(it) }
        if (catalog.includeUnk) phrases.add("[unk]")
        val arr = org.json.JSONArray()
        phrases.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun parse(text: String): Catalog {
        val json = JSONObject(text)
        val choices = stringList(json, "choices")
        val defaults = stringList(json, "defaults").filter { it in choices }.toSet()
        // hey jarvis stays a reject even if a phrases.json edit lists it
        // as a choice. Another household device owns that wake word.
        val rejects = stringList(json, "reject").toSet() + "hey jarvis"
        val safeChoices = choices.filter { it != "hey jarvis" && it !in rejects }
        return Catalog(
            choices = safeChoices.ifEmpty { FALLBACK.choices },
            defaults = defaults.ifEmpty { FALLBACK.defaults },
            rejects = rejects,
            includeUnk = json.optBoolean("includeUnk", true),
            confidenceDefault = json.optDouble("confidenceDefault", 0.85).toFloat(),
            sampleRate = json.optInt("sampleRate", 16_000),
            frameSamples = json.optInt("frameSamples", 1_280),
            preRollMs = json.optInt("preRollMs", 300),
            hangMs = json.optInt("hangMs", 800),
            earlyFinalizeMs = json.optInt("earlyFinalizeMs", 300),
            energyFloor = json.optDouble("energyFloor", 0.012),
            energyMultiplier = json.optDouble("energyMultiplier", 2.5),
        )
    }

    private fun stringList(json: JSONObject, key: String): List<String> {
        val arr = json.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val value = arr.optString(i).trim().lowercase()
            if (value.isNotEmpty()) out.add(value)
        }
        return out
    }

    private val FALLBACK = Catalog(
        choices = listOf(WakeAccept.PHRASE),
        defaults = setOf(WakeAccept.PHRASE),
        rejects = setOf(
            "okay computer", "ok computer", "hey computer", "hey jack",
            "hey jarvis", "hi jarvis", "ok jarvis", "okay jarvis",
            "hi tablet", "okay tablet", "hey tab",
            "hey jackson", "hey jackie", "hey john", "hey jake",
            "hey james", "hey jacob", "hey jason", "hey zack",
            "hey mack", "hey hank", "hi jack",
        ),
        includeUnk = true,
        confidenceDefault = 0.85f,
        sampleRate = 16_000,
        frameSamples = 1_280,
        preRollMs = 300,
        hangMs = 800,
        earlyFinalizeMs = 300,
        energyFloor = 0.012,
        energyMultiplier = 2.5,
    )
}
