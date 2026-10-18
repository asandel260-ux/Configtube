package com.studyflow.app

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

class MainVM(private val app: Application) : AndroidViewModel(app) {
    private val store = Store(app)

    var settings by mutableStateOf(store.loadSettings()); private set
    var subjects by mutableStateOf(store.loadSubjects()); private set
    var sessions by mutableStateOf(store.loadSessions()); private set
    var timer by mutableStateOf(store.loadTimer(settings.defaultMode)); private set
    var now by mutableLongStateOf(System.currentTimeMillis()); private set

    init {
        ensureSubject()
    }

    private fun ensureSubject() {
        if (subjects.isEmpty()) {
            subjects = listOf(Subject(name = "General", emoji = "📚"))
            store.saveSubjects(subjects)
        }
        if (subjects.none { it.id == timer.subjectId && !it.archived }) {
            val first = subjects.firstOrNull { !it.archived } ?: subjects.first()
            setTimer(timer.copy(subjectId = first.id))
        }
    }

    // ---------- Timer ----------

    fun targetFor(phase: Int): Long =
        (when (phase) { 0 -> settings.focusMin; 1 -> settings.shortMin; else -> settings.longMin }) * 60_000L

    fun targetMs(): Long = targetFor(timer.phase)

    fun elapsed(): Long = timer.baseMs + if (timer.running) (now - timer.segStart).coerceAtLeast(0) else 0

    fun tick() {
        now = System.currentTimeMillis()
        if (timer.running && timer.mode == 1 && elapsed() >= targetMs()) completePhase()
    }

    private fun setTimer(t: TimerState) {
        timer = t
        store.saveTimer(t)
        schedule(t)
    }

    private fun alarmIntent(phase: Int): PendingIntent {
        val i = Intent(app, AlarmReceiver::class.java)
            .putExtra("title", if (phase == 0) "Focus session complete 🎉" else "Break is over")
            .putExtra("text", if (phase == 0) "Time for a break." else "Ready to focus again?")
        return PendingIntent.getBroadcast(
            app, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun schedule(t: TimerState) {
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmIntent(t.phase)
        am.cancel(pi)
        if (t.running && t.mode == 1 && settings.notify) {
            val at = t.segStart + (targetFor(t.phase) - t.baseMs)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun buzz() {
        if (!settings.vibrate) return
        val v = app.getSystemService(Vibrator::class.java) ?: return
        v.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun start() {
        val n = System.currentTimeMillis()
        now = n
        setTimer(timer.copy(running = true, segStart = n))
    }

    fun pause() {
        val n = System.currentTimeMillis()
        now = n
        val e = timer.baseMs + (n - timer.segStart).coerceAtLeast(0)
        setTimer(timer.copy(running = false, baseMs = e))
    }

    fun reset() = setTimer(timer.copy(running = false, baseMs = 0))

    fun finish(note: String) {
        val n = System.currentTimeMillis()
        now = n
        val t = timer
        val e = t.baseMs + if (t.running) (n - t.segStart).coerceAtLeast(0) else 0
        if (t.phase == 0 && e / 1000 >= settings.minSaveSec && e > 0) {
            addSession(t.subjectId, n - e, e / 1000, note, t.mode)
        }
        setTimer(t.copy(running = false, baseMs = 0, phase = 0))
    }

    fun skipBreak() {
        if (timer.phase != 0) setTimer(timer.copy(running = false, baseMs = 0, phase = 0))
    }

    private fun completePhase() {
        val t = timer
        val target = targetMs()
        val end = t.segStart + (target - t.baseMs)
        var cycles = t.cycles
        val next: Int
        if (t.phase == 0) {
            addSession(t.subjectId, end - target, target / 1000, "", 1)
            cycles++
            next = if (cycles % settings.cycles == 0) 2 else 1
        } else {
            next = 0
        }
        buzz()
        setTimer(
            t.copy(
                phase = next, baseMs = 0, cycles = cycles,
                running = settings.autoStart,
                segStart = if (settings.autoStart) end else System.currentTimeMillis(),
            )
        )
    }

    fun setMode(m: Int) = setTimer(timer.copy(mode = m, phase = 0, baseMs = 0, running = false))

    fun selectSubject(id: String) = setTimer(timer.copy(subjectId = id))

    // ---------- Sessions ----------

    fun addSession(subjectId: String, start: Long, durSec: Long, note: String, mode: Int) {
        if (durSec <= 0) return
        sessions = (sessions + Session(subjectId = subjectId, start = start, durSec = durSec, note = note, mode = mode))
            .sortedByDescending { it.start }
        store.saveSessions(sessions)
    }

    fun deleteSession(id: String) {
        sessions = sessions.filterNot { it.id == id }
        store.saveSessions(sessions)
    }

    // ---------- Subjects ----------

    fun saveSubject(s: Subject) {
        subjects = if (subjects.any { it.id == s.id }) subjects.map { if (it.id == s.id) s else it } else subjects + s
        store.saveSubjects(subjects)
        ensureSubject()
    }

    fun deleteSubject(id: String) {
        if (subjects.size <= 1) return
        subjects = subjects.filterNot { it.id == id }
        store.saveSubjects(subjects)
        ensureSubject()
    }

    // ---------- Settings ----------

    fun update(f: (Settings) -> Settings) {
        settings = f(settings)
        store.saveSettings(settings)
        schedule(timer)
    }

    // ---------- Data ----------

    fun exportJson(): String = JSONObject()
        .put("version", 1)
        .put("subjects", JSONArray(subjects.map { it.toJson() }))
        .put("sessions", JSONArray(sessions.map { it.toJson() }))
        .put("settings", settings.toJson())
        .toString(2)

    fun exportCsv(): String {
        val names = subjects.associate { it.id to it.name }
        val sb = StringBuilder("date,start,subject,minutes,mode,note\n")
        fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        sessions.sortedBy { it.start }.forEach {
            val dt = Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault())
            sb.append("${dt.toLocalDate()},${dt.toLocalTime().withNano(0)},${esc(names[it.subjectId] ?: "Deleted")},")
                .append("${"%.1f".format(it.durSec / 60.0)},${if (it.mode == 1) "pomodoro" else "stopwatch"},${esc(it.note)}\n")
        }
        return sb.toString()
    }

    fun importJson(text: String): Boolean = try {
        val o = JSONObject(text)
        val subs = o.getJSONArray("subjects").let { a -> (0 until a.length()).map { Subject.fromJson(a.getJSONObject(it)) } }
        val sess = o.getJSONArray("sessions").let { a -> (0 until a.length()).map { Session.fromJson(a.getJSONObject(it)) } }
        val set = o.optJSONObject("settings")?.let { Settings.fromJson(it) } ?: settings
        subjects = subs
        sessions = sess.sortedByDescending { it.start }
        settings = set
        store.saveSubjects(subjects)
        store.saveSessions(sessions)
        store.saveSettings(settings)
        ensureSubject()
        true
    } catch (e: Exception) {
        false
    }

    fun resetAll() {
        store.clear()
        settings = Settings()
        subjects = emptyList()
        sessions = emptyList()
        timer = TimerState(mode = settings.defaultMode)
        store.saveTimer(timer)
        schedule(timer)
        ensureSubject()
    }
}
