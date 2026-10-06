package com.example.eventalarm

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * One alarm we have registered with AlarmManager.
 * A follow-up ("remind me again") alarm has followUp = true and shares eventId/eventBegin
 * with the main alarm for the same event instance.
 */
data class ScheduledAlarm(
    val key: String,
    val triggerAt: Long,
    val eventBegin: Long,
    val title: String,
    val eventId: Long = 0L,
    val followUp: Boolean = false
)

class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("event_alarm", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    var leadMinutes: Int
        get() = sp.getInt(KEY_LEAD, 10)
        set(value) = sp.edit().putInt(KEY_LEAD, value).apply()

    var lookaheadDays: Int
        get() = sp.getInt(KEY_LOOKAHEAD, 7)
        set(value) = sp.edit().putInt(KEY_LOOKAHEAD, value).apply()

    /** Default "remind me again N minutes before" used by the notification's action button. */
    var followUpMinutes: Int
        get() = sp.getInt(KEY_FOLLOW_UP, 10)
        set(value) = sp.edit().putInt(KEY_FOLLOW_UP, value).apply()

    /** One-tap choices shown on the alarm screen (minutes before the event). */
    var followUpChoices: List<Int>
        get() = parseChoices(sp.getString(KEY_CHOICES, null) ?: DEFAULT_CHOICES)
        set(value) = sp.edit().putString(KEY_CHOICES, value.joinToString(",")).apply()

    /** Calendars the user has switched off. New calendars are included by default. */
    var excludedCalendarIds: Set<Long>
        get() = (sp.getStringSet(KEY_EXCLUDED, emptySet()) ?: emptySet())
            .mapNotNull { it.toLongOrNull() }
            .toSet()
        set(value) = sp.edit()
            .putStringSet(KEY_EXCLUDED, value.map { it.toString() }.toSet())
            .apply()

    /** Alarms currently registered, keyed by "<eventId>-<instanceBegin>" (follow-ups: "fu-" + that). */
    fun loadAlarms(): Map<String, ScheduledAlarm> {
        val raw = sp.getString(KEY_ALARMS, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val out = LinkedHashMap<String, ScheduledAlarm>()
            for (key in obj.keys()) {
                val o = obj.getJSONObject(key)
                out[key] = ScheduledAlarm(
                    key = key,
                    triggerAt = o.getLong("t"),
                    eventBegin = o.getLong("b"),
                    title = o.optString("n", ""),
                    eventId = o.optLong("e", 0L),
                    followUp = o.optBoolean("f", false)
                )
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveAlarms(alarms: Collection<ScheduledAlarm>) {
        val obj = JSONObject()
        for (a in alarms) {
            obj.put(
                a.key,
                JSONObject()
                    .put("t", a.triggerAt)
                    .put("b", a.eventBegin)
                    .put("n", a.title)
                    .put("e", a.eventId)
                    .put("f", a.followUp)
            )
        }
        // commit(): callers are on background threads and we don't want to lose this on process death.
        sp.edit().putString(KEY_ALARMS, obj.toString()).commit()
    }

    companion object {
        const val DEFAULT_CHOICES = "15,10,5"
        const val MAX_CHOICES = 4

        /** Parses "15, 10 5" into [15, 10, 5]: 1..1440 minutes, no duplicates, at most 4. */
        fun parseChoices(raw: String): List<Int> =
            raw.split(',', ' ', ';')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..1440 }
                .distinct()
                .take(MAX_CHOICES)

        private const val KEY_ENABLED = "enabled"
        private const val KEY_LEAD = "lead_minutes"
        private const val KEY_LOOKAHEAD = "lookahead_days"
        private const val KEY_FOLLOW_UP = "follow_up_minutes"
        private const val KEY_CHOICES = "follow_up_choices"
        private const val KEY_EXCLUDED = "excluded_calendars"
        private const val KEY_ALARMS = "alarms"
    }
}
