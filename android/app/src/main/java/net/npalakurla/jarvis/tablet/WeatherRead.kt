package net.npalakurla.jarvis.tablet

import java.time.Instant

/**
 * Remember the Home Assistant weather entity id and read that one
 * entity when it is still fresh. The first lookup, and any stale or
 * missing one, uses the existing ha-get-states tool. No new endpoint.
 */
internal class WeatherRead {
    var entityId: String? = null

    data class Outcome(
        val detail: SpokenFacts.WeatherDetail,
        val tool: String,
        val millis: Long,
    )

    fun load(
        now: Instant,
        readOne: (String) -> String?,
        readAll: () -> String?,
        clock: () -> Long = { System.nanoTime() },
    ): Outcome {
        val started = clock()
        val cached = entityId
        if (cached != null) {
            val detail = SpokenFacts.weatherDetail(readOne(cached), now)
            if (detail.fresh) {
                return Outcome(detail, "ha-get-state", elapsed(started, clock))
            }
        }
        val all = readAll()
        val detail = SpokenFacts.weatherDetail(all, now)
        if (detail.entityId != null) entityId = detail.entityId
        return Outcome(detail, "ha-get-states", elapsed(started, clock))
    }

    private fun elapsed(started: Long, clock: () -> Long): Long = (clock() - started) / 1_000_000L
}
