package com.studyflow.app

import org.json.JSONObject
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID

data class Subject(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val emoji: String = "📚",
    val color: Long = 0xFF5C6BC0,
    val dailyGoalMin: Int = 0,
    val archived: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("emoji", emoji)
        .put("color", color).put("goal", dailyGoalMin).put("archived", archived)

    companion object {
        fun fromJson(o: JSONObject) = Subject(
            id = o.getString("id"),
            name = o.getString("name"),
            emoji = o.optString("emoji", "📚"),
            color = o.optLong("color", 0xFF5C6BC0),
            dailyGoalMin = o.optInt("goal", 0),
            archived = o.optBoolean("archived", false),
        )
    }
}

data class Session(
    val id: String = UUID.randomUUID().toString(),
    val subjectId: String,
    val start: Long,
    val durSec: Long,
    val note: String = "",
    val mode: Int = 0, // 0 = stopwatch / manual, 1 = pomodoro
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("subjectId", subjectId).put("start", start)
        .put("durSec", durSec).put("note", note).put("mode", mode)

    companion object {
        fun fromJson(o: JSONObject) = Session(
            id = o.getString("id"),
            subjectId = o.getString("subjectId"),
            start = o.getLong("start"),
            durSec = o.getLong("durSec"),
            note = o.optString("note", ""),
            mode = o.optInt("mode", 0),
        )
    }
}

data class TimerState(
    val running: Boolean = false,
    val mode: Int = 1,      // 0 = stopwatch, 1 = pomodoro
    val phase: Int = 0,     // 0 = focus, 1 = short break, 2 = long break
    val baseMs: Long = 0,   // time accumulated before the current run segment
    val segStart: Long = 0, // wall-clock start of the current run segment
    val subjectId: String = "",
    val cycles: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("running", running).put("mode", mode).put("phase", phase)
        .put("baseMs", baseMs).put("segStart", segStart)
        .put("subjectId", subjectId).put("cycles", cycles)

    companion object {
        fun fromJson(o: JSONObject) = TimerState(
            running = o.optBoolean("running", false),
            mode = o.optInt("mode", 1),
            phase = o.optInt("phase", 0),
            baseMs = o.optLong("baseMs", 0),
            segStart = o.optLong("segStart", 0),
            subjectId = o.optString("subjectId", ""),
            cycles = o.optInt("cycles", 0),
        )
    }
}

data class Settings(
    // Appearance
    val themeMode: Int = 0, // 0 system, 1 light, 2 dark
    val accent: Long = 0xFF5C6BC0,
    val dynamic: Boolean = false,
    val amoled: Boolean = false,
    val fontScale: Float = 1f,
    val corner: Int = 20,
    val ringWidth: Int = 18,
    val subjectColorRing: Boolean = true,
    // Timer
    val defaultMode: Int = 1,
    val focusMin: Int = 25,
    val shortMin: Int = 5,
    val longMin: Int = 15,
    val cycles: Int = 4,
    val autoStart: Boolean = false,
    val minSaveSec: Int = 10,
    // Goals
    val dailyGoal: Int = 120,
    val weeklyGoal: Int = 600,
    // Behaviour
    val vibrate: Boolean = true,
    val notify: Boolean = true,
    val keepOn: Boolean = true,
    val askNote: Boolean = false,
    val showSeconds: Boolean = true,
    val use24h: Boolean = true,
    val weekStart: Int = WeekFields.of(Locale.getDefault()).firstDayOfWeek.value,
    // Tabs
    val showLog: Boolean = true,
    val showStats: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("themeMode", themeMode).put("accent", accent).put("dynamic", dynamic)
        .put("amoled", amoled).put("fontScale", fontScale.toDouble()).put("corner", corner)
        .put("ringWidth", ringWidth).put("subjectColorRing", subjectColorRing)
        .put("defaultMode", defaultMode).put("focusMin", focusMin).put("shortMin", shortMin)
        .put("longMin", longMin).put("cycles", cycles).put("autoStart", autoStart)
        .put("minSaveSec", minSaveSec).put("dailyGoal", dailyGoal).put("weeklyGoal", weeklyGoal)
        .put("vibrate", vibrate).put("notify", notify).put("keepOn", keepOn)
        .put("askNote", askNote).put("showSeconds", showSeconds).put("use24h", use24h)
        .put("weekStart", weekStart).put("showLog", showLog).put("showStats", showStats)

    companion object {
        fun fromJson(o: JSONObject): Settings {
            val d = Settings()
            return Settings(
                themeMode = o.optInt("themeMode", d.themeMode),
                accent = o.optLong("accent", d.accent),
                dynamic = o.optBoolean("dynamic", d.dynamic),
                amoled = o.optBoolean("amoled", d.amoled),
                fontScale = o.optDouble("fontScale", d.fontScale.toDouble()).toFloat(),
                corner = o.optInt("corner", d.corner),
                ringWidth = o.optInt("ringWidth", d.ringWidth),
                subjectColorRing = o.optBoolean("subjectColorRing", d.subjectColorRing),
                defaultMode = o.optInt("defaultMode", d.defaultMode),
                focusMin = o.optInt("focusMin", d.focusMin),
                shortMin = o.optInt("shortMin", d.shortMin),
                longMin = o.optInt("longMin", d.longMin),
                cycles = o.optInt("cycles", d.cycles),
                autoStart = o.optBoolean("autoStart", d.autoStart),
                minSaveSec = o.optInt("minSaveSec", d.minSaveSec),
                dailyGoal = o.optInt("dailyGoal", d.dailyGoal),
                weeklyGoal = o.optInt("weeklyGoal", d.weeklyGoal),
                vibrate = o.optBoolean("vibrate", d.vibrate),
                notify = o.optBoolean("notify", d.notify),
                keepOn = o.optBoolean("keepOn", d.keepOn),
                askNote = o.optBoolean("askNote", d.askNote),
                showSeconds = o.optBoolean("showSeconds", d.showSeconds),
                use24h = o.optBoolean("use24h", d.use24h),
                weekStart = o.optInt("weekStart", d.weekStart),
                showLog = o.optBoolean("showLog", d.showLog),
                showStats = o.optBoolean("showStats", d.showStats),
            )
        }
    }
}
