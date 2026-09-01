package com.sidekick.app.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Format a timestamp as a short relative string for use under chat bubbles.
 *
 *  - "Just now" when under a minute old
 *  - "5m ago" / "2h ago" within the same calendar day
 *  - "Yesterday" when one calendar day old
 *  - "Mar 14" within the same calendar year
 *  - "Mar 14, 2025" otherwise
 *
 * Pure formatting — no composables, no platform dependencies beyond
 * [java.text.SimpleDateFormat]. Cheap enough to call on every recomposition.
 */
fun formatRelativeTimestamp(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val delta = now - epochMillis
    if (delta < 0L) return "Just now"
    val minutes = delta / 60_000
    if (minutes < 1) return "Just now"
    if (minutes < 60) return "${minutes}m ago"
    val hours = minutes / 60
    if (hours < 24) return "${hours}h ago"

    val then = Date(epochMillis)
    val today = Date(now)
    val nowCal = java.util.Calendar.getInstance().apply { time = today }
    val thenCal = java.util.Calendar.getInstance().apply { time = then }
    val dayDelta = nowCal.get(java.util.Calendar.DAY_OF_YEAR) -
        thenCal.get(java.util.Calendar.DAY_OF_YEAR)
    if (dayDelta == 1 && nowCal.get(java.util.Calendar.YEAR) == thenCal.get(java.util.Calendar.YEAR)) {
        return "Yesterday"
    }

    val fmt = if (thenCal.get(java.util.Calendar.YEAR) == nowCal.get(java.util.Calendar.YEAR)) {
        SimpleDateFormat("MMM d", Locale.getDefault())
    } else {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    }
    return fmt.format(then)
}