package com.studyflow.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun Long.day(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

fun List<Session>.secOn(d: LocalDate): Long = filter { it.start.day() == d }.sumOf { it.durSec }

fun fmtDur(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m.toString().padStart(2, '0')}m"
        m > 0 -> "${m}m"
        else -> "${sec}s"
    }
}

fun clock(ms: Long, showSec: Boolean): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val mm = m.toString().padStart(2, '0')
    val ss = s.toString().padStart(2, '0')
    return when {
        h > 0 && showSec -> "$h:$mm:$ss"
        h > 0 -> "$h:$mm"
        showSec -> "$mm:$ss"
        else -> "${(total + 59) / 60}m"
    }
}

fun streak(sessions: List<Session>): Int {
    val days = sessions.map { it.start.day() }.toSet()
    var d = LocalDate.now()
    if (d !in days) d = d.minusDays(1)
    var c = 0
    while (d in days) {
        c++
        d = d.minusDays(1)
    }
    return c
}
