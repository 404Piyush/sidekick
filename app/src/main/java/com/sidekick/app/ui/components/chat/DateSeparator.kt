package com.sidekick.app.ui.components.chat

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Compute the [DateSeparatorLabel] for a message timestamp, relative to
 * a "now" reference point. Used to decide when to insert a
 * "Today" / "Yesterday" / "Aug 30" row between two consecutive turns.
 *
 * The actual UI rendering lives in [DateSeparatorRow]; this object is
 * the pure-function side that decides what string to show.
 */
internal object DateSeparator {

    /**
     * Return the appropriate label for [timestamp].
     *
     *  - Same calendar day as [reference] → `Today`
     *  - Exactly one calendar day before [reference] → `Yesterday`
     *  - Same calendar year → `Aug 30` (short month + day)
     *  - Different calendar year → `Aug 30, 2024`
     */
    fun labelFor(timestamp: Long, reference: Long = System.currentTimeMillis()): String {
        val tsCal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val refCal = Calendar.getInstance().apply { timeInMillis = reference }
        val sameDay = isSameDay(tsCal, refCal)
        if (sameDay) return "Today"
        val oneDayBefore = isSameDay(tsCal, shiftDays(refCal, -1))
        if (oneDayBefore) return "Yesterday"
        val sameYear = tsCal.get(Calendar.YEAR) == refCal.get(Calendar.YEAR)
        val fmt = if (sameYear) {
            SimpleDateFormat("MMM d", Locale.getDefault())
        } else {
            SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        }
        return fmt.format(Date(timestamp))
    }

    /**
     * Decide whether two consecutive messages need a date separator
     * between them.
     *
     * The rule mirrors WhatsApp / iMessage: insert a separator if the
     * gap is at least [MIN_GAP_MINUTES] OR the messages fall on
     * different calendar days.
     */
    fun shouldInsert(
        prevTimestamp: Long,
        nextTimestamp: Long,
    ): Boolean {
        if (prevTimestamp == 0L || nextTimestamp == 0L) return false
        val gap = nextTimestamp - prevTimestamp
        if (gap < 0) return false
        val gapMinutes = TimeUnit.MILLISECONDS.toMinutes(gap)
        if (gapMinutes >= MIN_GAP_MINUTES) return true
        val prevCal = Calendar.getInstance().apply { timeInMillis = prevTimestamp }
        val nextCal = Calendar.getInstance().apply { timeInMillis = nextTimestamp }
        return !isSameDay(prevCal, nextCal)
    }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun shiftDays(c: Calendar, days: Int): Calendar =
        (c.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, days) }

    /**
     * Threshold above which we always insert a date separator, even
     * within the same calendar day. The 1-minute floor matches the
     * iQOO demo's expected cadence — back-to-back exchanges don't
     * spam the transcript with separators, but a 10-minute pause
     * does.
     */
    const val MIN_GAP_MINUTES: Long = 1
}