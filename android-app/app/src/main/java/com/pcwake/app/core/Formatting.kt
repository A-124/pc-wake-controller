package com.pcwake.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Formatting {

    fun relativeTime(epochMillis: Long?, now: Long = System.currentTimeMillis()): String {
        if (epochMillis == null || epochMillis <= 0) return "Never"
        val diff = now - epochMillis
        return when {
            diff < 0 -> "Just now"
            diff < 45_000 -> "Just now"
            diff < 90_000 -> "1 min ago"
            diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)} min ago"
            diff < TimeUnit.HOURS.toMillis(2) -> "1 hour ago"
            diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)} hours ago"
            diff < TimeUnit.DAYS.toMillis(2) -> "Yesterday"
            diff < TimeUnit.DAYS.toMillis(7) -> "${TimeUnit.MILLISECONDS.toDays(diff)} days ago"
            else -> date(epochMillis, "d MMM yyyy")
        }
    }

    fun date(epochMillis: Long, pattern: String = "d MMM yyyy, HH:mm"): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMillis))

    fun time(epochMillis: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))

    fun duration(seconds: Long): String {
        val days = seconds / 86_400
        val hours = (seconds % 86_400) / 3_600
        val minutes = (seconds % 3_600) / 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    fun shortFingerprint(fingerprint: String): String {
        if (fingerprint.length <= 16) return fingerprint
        return fingerprint.take(8) + "…" + fingerprint.takeLast(8)
    }

    fun endpointHost(endpoint: String): String =
        endpoint.substringAfter("://").substringBefore("/")

    fun endpointPretty(endpoint: String): String =
        endpoint.removePrefix("https://").trimEnd('/')
}
