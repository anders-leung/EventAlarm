package com.example.eventalarm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat

data class EventInstance(
    val eventId: Long,
    val begin: Long,
    val title: String,
    val calendarId: Long
)

data class CalendarInfo(
    val id: Long,
    val name: String,
    val account: String,
    val visible: Boolean
)

/**
 * Reads from the on-device Calendar Provider. Google Calendar's sync adapter keeps this
 * database up to date, so we never talk to Google directly.
 */
object CalendarReader {

    private const val TAG = "CalendarReader"

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun listCalendars(context: Context): List<CalendarInfo> {
        if (!hasPermission(context)) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.VISIBLE
        )
        val sort = CalendarContract.Calendars.ACCOUNT_NAME + " ASC, " +
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " ASC"
        val out = ArrayList<CalendarInfo>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection, null, null, sort
            )?.use { c ->
                while (c.moveToNext()) {
                    out.add(
                        CalendarInfo(
                            id = c.getLong(0),
                            name = c.getString(1) ?: "",
                            account = c.getString(2) ?: "",
                            visible = c.getInt(3) == 1
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No calendar permission", e)
        }
        return out
    }

    /**
     * Event instances (recurring events already expanded) that overlap [fromMillis, toMillis].
     * Returns null if the provider could not be read, so callers don't mistake a read
     * failure for "no events" and wipe their alarms.
     *
     * Skipped: all-day events (no real start time), cancelled events, events you declined,
     * and calendars you switched off in this app. The provider itself only returns
     * calendars that are visible in your Calendar app.
     */
    fun readUpcoming(
        context: Context,
        fromMillis: Long,
        toMillis: Long,
        excludedCalendars: Set<Long>
    ): List<EventInstance>? {
        if (!hasPermission(context)) return null
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,              // 0
            CalendarContract.Instances.BEGIN,                 // 1
            CalendarContract.Events.TITLE,                    // 2
            CalendarContract.Events.CALENDAR_ID,              // 3
            CalendarContract.Events.ALL_DAY,                  // 4
            CalendarContract.Events.STATUS,                   // 5
            CalendarContract.Events.SELF_ATTENDEE_STATUS      // 6
        )
        return try {
            val cursor = CalendarContract.Instances.query(
                context.contentResolver, projection, fromMillis, toMillis
            ) ?: return null
            val out = ArrayList<EventInstance>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(4) == 1) continue
                    val calendarId = c.getLong(3)
                    if (calendarId in excludedCalendars) continue
                    if (c.getInt(5) == CalendarContract.Events.STATUS_CANCELED) continue
                    if (c.getInt(6) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    val title = c.getString(2)?.takeIf { it.isNotBlank() } ?: "(No title)"
                    out.add(EventInstance(c.getLong(0), c.getLong(1), title, calendarId))
                }
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read calendar instances", e)
            null
        }
    }
}
