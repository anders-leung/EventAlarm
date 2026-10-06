package com.example.eventalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log

data class SyncResult(
    val scheduled: Int = 0,
    val removed: Int = 0,
    val failed: Int = 0,
    val message: String? = null
) {
    fun describe(): String = message ?: buildString {
        append("Synced: $scheduled alarm(s) scheduled")
        if (removed > 0) append(", $removed stale alarm(s) cleaned up")
        if (failed > 0) append(", $failed could not be scheduled (check the exact-alarm permission)")
        append('.')
    }
}

/**
 * Owns every alarm this app registers.
 *
 * Alarms are registered with AlarmManager.setAlarmClock(), which is exempt from Doze and
 * shows up as the system's "next alarm". Each alarm is identified by a PendingIntent whose
 * data URI contains the alarm key, so it can be updated or cancelled later.
 *
 * Two kinds of alarm exist:
 *  - main alarms, one per upcoming event, created by [sync] (lead time before the start);
 *  - follow-up alarms, created when you tap "remind me again" on a ringing alarm. They are
 *    tied to the event instance and are cleaned up by [sync] if the event goes away or moves.
 */
object AlarmScheduler {

    const val ACTION_ALARM = "com.example.eventalarm.ACTION_ALARM"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BEGIN = "begin"
    const val EXTRA_EVENT_ID = "event_id"
    const val EXTRA_FOLLOW_UP = "follow_up"
    const val EXTRA_MINUTES = "minutes"

    private const val TAG = "AlarmScheduler"
    private const val MAX_ALARMS = 200
    private const val TEST_KEY = "test"
    private const val TEST_EVENT_ID = -1L
    private const val FOLLOW_UP_MARGIN_MS = 5_000L

    /**
     * Reconciles registered alarms with the calendar:
     *  - schedules (or re-schedules) an alarm for every upcoming event,
     *  - cancels alarms for events that were deleted, cancelled, declined, moved or renamed,
     *  - keeps pending follow-up reminders whose event still exists, cancels the rest,
     *  - drops records of alarms that have already fired.
     * Safe to call as often as you like; it is idempotent.
     */
    @Synchronized
    fun sync(context: Context): SyncResult {
        val app = context.applicationContext
        val prefs = Prefs(app)

        if (!prefs.enabled) {
            val n = cancelAll(app)
            return SyncResult(removed = n, message = "Alarms are turned off.")
        }
        if (!CalendarReader.hasPermission(app)) {
            return SyncResult(message = "Calendar permission is missing.")
        }

        val now = System.currentTimeMillis()
        val leadMs = prefs.leadMinutes.coerceIn(0, 24 * 60) * 60_000L
        val horizonMs = prefs.lookaheadDays.coerceIn(1, 30) * 24L * 60L * 60_000L

        val events = CalendarReader.readUpcoming(
            app, now, now + leadMs + horizonMs, prefs.excludedCalendarIds
        ) ?: return SyncResult(message = "Couldn't read the calendar; existing alarms were kept.")

        // Every live event instance, including ones whose main alarm already fired.
        val liveEvents = events.associateBy { "${it.eventId}-${it.begin}" }

        // What main alarms should exist right now.
        val desired = LinkedHashMap<String, ScheduledAlarm>()
        for (e in events.sortedBy { it.begin }) {
            val triggerAt = e.begin - leadMs
            if (triggerAt <= now) continue // too late to ring before this event
            val key = "${e.eventId}-${e.begin}"
            desired[key] = ScheduledAlarm(key, triggerAt, e.begin, e.title, e.eventId)
            if (desired.size >= MAX_ALARMS) break
        }

        // Cleanup: anything we registered before that is no longer wanted.
        val existing = prefs.loadAlarms()
        var removed = 0
        val keptFollowUps = ArrayList<ScheduledAlarm>()
        for ((key, old) in existing) {
            val pending = old.triggerAt > now
            if (old.followUp) {
                val live = liveEvents["${old.eventId}-${old.eventBegin}"]
                val parentExists = old.eventId == TEST_EVENT_ID || live != null
                if (pending && parentExists) {
                    keptFollowUps.add(old.copy(title = live?.title ?: old.title))
                } else if (pending) {
                    cancel(app, key)
                    removed++
                }
                // not pending: it already fired, just forget it
            } else if (key !in desired && pending) {
                cancel(app, key)
                removed++
            }
        }

        // Schedule / refresh. Re-registering an identical alarm just replaces it, which also
        // restores everything after a reboot (AlarmManager forgets alarms on reboot).
        val scheduled = ArrayList<ScheduledAlarm>()
        var failed = 0
        for (alarm in desired.values + keptFollowUps) {
            if (schedule(app, alarm)) scheduled.add(alarm) else failed++
        }
        prefs.saveAlarms(scheduled)
        return SyncResult(scheduled = scheduled.size, removed = removed, failed = failed)
    }

    /** True if an alarm [minutesBefore] the event start would still be in the future. */
    fun canRemindAgain(eventBegin: Long, minutesBefore: Int): Boolean =
        minutesBefore >= 1 &&
            eventBegin - minutesBefore * 60_000L > System.currentTimeMillis() + FOLLOW_UP_MARGIN_MS

    /**
     * Schedules a second alarm [minutesBefore] the event start (e.g. after dismissing the
     * 30-minute alarm, "remind me again 10 minutes before"). Returns the time it will ring,
     * or null if that moment has already passed or alarms can't be scheduled.
     */
    @Synchronized
    fun scheduleFollowUp(
        context: Context,
        eventId: Long,
        eventBegin: Long,
        title: String,
        minutesBefore: Int
    ): Long? {
        val app = context.applicationContext
        if (!canRemindAgain(eventBegin, minutesBefore)) return null
        val triggerAt = eventBegin - minutesBefore * 60_000L
        val alarm = ScheduledAlarm(
            key = "fu-$eventId-$eventBegin",
            triggerAt = triggerAt,
            eventBegin = eventBegin,
            title = title,
            eventId = eventId,
            followUp = true
        )
        if (!schedule(app, alarm)) return null
        val prefs = Prefs(app)
        val all = prefs.loadAlarms().toMutableMap()
        all[alarm.key] = alarm // one follow-up per event instance; a new one replaces the old
        prefs.saveAlarms(all.values)
        return triggerAt
    }

    /** Cancels every alarm this app registered and forgets them. Returns how many. */
    @Synchronized
    fun cancelAll(context: Context): Int {
        val app = context.applicationContext
        val prefs = Prefs(app)
        val existing = prefs.loadAlarms()
        for (key in existing.keys) cancel(app, key)
        prefs.saveAlarms(emptyList())
        return existing.size
    }

    /** Upcoming alarms we know about, soonest first. */
    fun upcoming(context: Context): List<ScheduledAlarm> {
        val now = System.currentTimeMillis()
        return Prefs(context).loadAlarms().values
            .filter { it.triggerAt > now }
            .sortedBy { it.triggerAt }
    }

    /**
     * Rings in [delaySeconds] for a pretend event that starts 30 minutes after that, so you
     * can try the vibrate-mode behaviour and the "remind me again" buttons
     * (enter 29 minutes to get the reminder about a minute later).
     */
    fun scheduleTest(context: Context, delaySeconds: Long = 10): Boolean {
        val at = System.currentTimeMillis() + delaySeconds * 1000
        val begin = at + 30 * 60_000L
        return schedule(
            context.applicationContext,
            ScheduledAlarm(TEST_KEY, at, begin, "Test alarm", TEST_EVENT_ID)
        )
    }

    fun canScheduleExact(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager(context).canScheduleExactAlarms()
        } else {
            true
        }

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    // PendingIntent identity ignores extras but includes the data URI, so the URI is what
    // makes each alarm unique and cancellable.
    private fun buildIntent(context: Context, key: String, alarm: ScheduledAlarm? = null): Intent =
        Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_ALARM
            data = Uri.parse("eventalarm://alarm/$key")
            if (alarm != null) {
                putExtra(EXTRA_TITLE, alarm.title)
                putExtra(EXTRA_BEGIN, alarm.eventBegin)
                putExtra(EXTRA_EVENT_ID, alarm.eventId)
                putExtra(EXTRA_FOLLOW_UP, alarm.followUp)
            }
        }

    private fun schedule(context: Context, alarm: ScheduledAlarm): Boolean {
        val operation = PendingIntent.getBroadcast(
            context,
            0,
            buildIntent(context, alarm.key, alarm),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val showIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return try {
            alarmManager(context).setAlarmClock(
                AlarmManager.AlarmClockInfo(alarm.triggerAt, showIntent),
                operation
            )
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to schedule exact alarms", e)
            false
        }
    }

    private fun cancel(context: Context, key: String) {
        val pi = PendingIntent.getBroadcast(
            context,
            0,
            buildIntent(context, key),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            alarmManager(context).cancel(pi)
            pi.cancel()
        }
    }
}
