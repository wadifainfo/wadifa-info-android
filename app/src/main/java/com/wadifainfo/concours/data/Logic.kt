package com.wadifainfo.concours.data

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Pure rules shared by the UI and the background worker (unit-tested in DataTest). */
object Logic {
    enum class Status { OPEN, CLOSING, LAST_DAY, CLOSED, UNKNOWN }

    /** Deadlines are Moroccan calendar days: compute "today" in Morocco, not in the phone's timezone (travellers, MRE). */
    val MOROCCO: ZoneId = ZoneId.of("Africa/Casablanca")
    fun today(): LocalDate = LocalDate.now(MOROCCO)

    fun parseDate(iso: String?): LocalDate? = iso?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    fun daysLeft(deadline: String?, today: LocalDate = today()): Long? = parseDate(deadline)?.let { ChronoUnit.DAYS.between(today, it) }

    /** CLOSING = 3 days or fewer left. A deadline is inclusive: day 0 is still open. */
    fun status(deadline: String?, today: LocalDate = today()): Status {
        val d = daysLeft(deadline, today) ?: return Status.UNKNOWN
        return when {
            d < 0 -> Status.CLOSED
            d == 0L -> Status.LAST_DAY
            d <= 3 -> Status.CLOSING
            else -> Status.OPEN
        }
    }

    /** Reminder thresholds before a followed deadline (days). */
    val REMIND_AT = listOf(7L, 3L, 1L, 0L)

    /**
     * The reminder due now for a followed offer, keyed by its THRESHOLD so each one fires once. A periodic check can skip
     * a calendar day (Doze, phone off): if the 7-day check is missed, the run at 6 days still sends the 7-day reminder
     * (with the real count) instead of silently dropping it. Closed or > 7 days: nothing.
     */
    fun reminderKey(offerId: Long, deadline: String?, today: LocalDate = today()): String? {
        val d = daysLeft(deadline, today) ?: return null
        if (d < 0) return null
        val threshold = REMIND_AT.filter { it >= d }.minOrNull() ?: return null
        return "due:$offerId:$threshold"
    }

    /**
     * Offers worth a "new offer" alert: not notified before, still open, published within [maxAgeDays].
     * The first run of a search only records what exists (baseline) so a fresh install never floods the user.
     */
    fun newForAlert(items: List<Offer>, alreadyNotified: Set<String>, baseline: Boolean, today: LocalDate = today(), maxAgeDays: Long = 3): List<Offer> {
        if (baseline) return emptyList()
        return items.filter { o ->
            "new:${o.id}" !in alreadyNotified &&
                status(o.deadline, today) != Status.CLOSED &&
                (parseDate(o.published)?.let { ChronoUnit.DAYS.between(it, today) <= maxAgeDays } ?: false)
        }.distinctBy { it.id }
    }

    /**
     * The API's `contract` filter (1 regular, 2 contract) recovered from the cached display label, so offline results
     * honour it too. An unrecognised label matches neither: offline we cannot tell, so it is left out rather than guessed.
     */
    fun contractKind(label: String?): Int? {
        val l = label?.lowercase() ?: return null
        return when {
            "contrat" in l || "عقد" in l -> 2
            "régulier" in l || "regulier" in l || "نظامي" in l -> 1
            else -> null
        }
    }

    /** Retry wait for transient failures: 0.7 s, 1.4 s… plus jitter; a server Retry-After (seconds) wins, capped at 8 s. */
    fun backoffMillis(attempt: Int, retryAfterHeader: String? = null, jitter: Long = (0..300).random().toLong()): Long =
        retryAfterHeader?.trim()?.toLongOrNull()?.coerceIn(1, 8)?.times(1000) ?: (700L shl attempt) + jitter
}
