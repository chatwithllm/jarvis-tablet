package net.npalakurla.jarvis.tablet

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 2 — policy controller, native half (PRD v2.2 FR-24/25/50/51/55).
 * The server-side half lives in HA (ha/policy-controller.md): the agent
 * only ever calls `propose_action`; this object is the tablet-side record
 * of pending confirmations and the hard denylist.
 *
 * Rules enforced here (defense in depth — HA enforces them too):
 * - Hard denylist: lock.*, alarm_control_panel.*, every garage actuator
 *   (FR-25). Refused for everyone, even in a direct tool call.
 * - Every state-changing action needs a pending action with a UUID,
 *   a 10 s expiry, and a source device (FR-51). Timeout or no cancels
 *   with no service call (FR-24).
 * - A yes from another device is ignored (FR-55): resolve() only
 *   accepts the originating device's id.
 * - Audit log keeps proposed / confirmed / refused / expired (FR-51).
 *   No audio is ever stored.
 */
object PolicyController {

    /** 10 s confirm window (PRD locked). */
    const val CONFIRM_TIMEOUT_MS = 10_000L

    private val refusedDomains = setOf("lock", "alarm_control_panel")

    data class PendingAction(
        val id: String,
        val summary: String,
        val domain: String,
        val entityId: String,
        val newState: String,
        val sourceDevice: String,
        val createdAtMs: Long = System.currentTimeMillis(),
    ) {
        fun isExpired(nowMs: Long = System.currentTimeMillis()) =
            nowMs - createdAtMs > CONFIRM_TIMEOUT_MS
    }

    data class AuditEntry(
        val atMs: Long,
        val action: String, // proposed | confirmed | refused | expired | declined
        val id: String,
        val summary: String,
    )

    private val pending = ConcurrentHashMap<String, PendingAction>()
    private val audit = ArrayDeque<AuditEntry>()

    /** Hard denylist (FR-25). Garage = cover with garage device class. */
    fun isRefused(domain: String, entityId: String, deviceClass: String? = null): Boolean {
        if (domain in refusedDomains) return true
        if (domain == "cover" &&
            (deviceClass == "garage" || deviceClass == "garage_door" ||
                entityId.contains("garage", ignoreCase = true))
        ) return true
        return false
    }

    /** Propose an action. Returns null when the action is refused. */
    fun propose(
        summary: String,
        domain: String,
        entityId: String,
        newState: String,
        sourceDevice: String,
        deviceClass: String? = null,
    ): PendingAction? {
        if (isRefused(domain, entityId, deviceClass)) {
            audit("refused", "refused", summary)
            return null
        }
        val action = PendingAction(
            id = UUID.randomUUID().toString(),
            summary = summary,
            domain = domain,
            entityId = entityId,
            newState = newState,
            sourceDevice = sourceDevice,
        )
        pending[action.id] = action
        audit("proposed", action.id, summary)
        return action
    }

    /**
     * Resolve a pending action. Returns false when the id is unknown,
     * expired, or the yes came from a different device (FR-55).
     */
    fun resolve(id: String, confirmed: Boolean, sourceDevice: String): Boolean {
        val action = pending.remove(id) ?: return false
        if (action.isExpired()) {
            audit("expired", id, action.summary)
            return false
        }
        if (action.sourceDevice != sourceDevice) {
            // Cross-device yes is ignored (FR-55) — keep it pending so
            // the originating device can still answer in its window.
            pending[id] = action
            return false
        }
        audit(if (confirmed) "confirmed" else "declined", id, action.summary)
        return true
    }

    /** Drop expired pendings (call on a timer in Phase 2). */
    fun sweepExpired(nowMs: Long = System.currentTimeMillis()) {
        pending.entries.removeIf { (_, action) ->
            action.isExpired(nowMs).also { expired ->
                if (expired) audit("expired", action.id, action.summary)
            }
        }
    }

    fun auditTrail(): List<AuditEntry> = audit.toList()

    private fun audit(action: String, id: String, summary: String) {
        audit.addLast(AuditEntry(System.currentTimeMillis(), action, id, summary))
        while (audit.size > 200) audit.removeFirst()
    }
}
