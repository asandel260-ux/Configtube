package com.studyflow.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("studyflow", Context.MODE_PRIVATE)

    private fun arr(key: String): JSONArray =
        try { JSONArray(p.getString(key, "[]")) } catch (e: Exception) { JSONArray() }

    fun loadSubjects(): List<Subject> =
        arr("subjects").let { a -> (0 until a.length()).map { Subject.fromJson(a.getJSONObject(it)) } }

    fun saveSubjects(l: List<Subject>) {
        p.edit().putString("subjects", JSONArray(l.map { it.toJson() }).toString()).apply()
    }

    fun loadSessions(): List<Session> =
        arr("sessions").let { a -> (0 until a.length()).map { Session.fromJson(a.getJSONObject(it)) } }

    fun saveSessions(l: List<Session>) {
        p.edit().putString("sessions", JSONArray(l.map { it.toJson() }).toString()).apply()
    }

    fun loadSettings(): Settings =
        try { p.getString("settings", null)?.let { Settings.fromJson(JSONObject(it)) } ?: Settings() }
        catch (e: Exception) { Settings() }

    fun saveSettings(s: Settings) {
        p.edit().putString("settings", s.toJson().toString()).apply()
    }

    fun loadTimer(defaultMode: Int): TimerState =
        try { p.getString("timer", null)?.let { TimerState.fromJson(JSONObject(it)) } ?: TimerState(mode = defaultMode) }
        catch (e: Exception) { TimerState(mode = defaultMode) }

    fun saveTimer(t: TimerState) {
        p.edit().putString("timer", t.toJson().toString()).apply()
    }

    fun clear() {
        p.edit().clear().apply()
    }
}
