package com.example.eventalarm

import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Keeps alarms in sync without the app running all the time.
 *
 * 1. Observer: WorkManager asks the system to watch the Calendar Provider. When Google
 *    Calendar's sync (or you, in any calendar app) changes an event, Android wakes this
 *    app, we sync, and queue the next watch. No polling, no foreground service.
 * 2. Periodic backup: every few hours, which also slides the look-ahead window forward and
 *    re-creates the observer if it ever went missing.
 */
object SyncScheduler {

    private const val OBSERVER = "calendar-observer"
    private const val PERIODIC = "calendar-periodic"

    fun start(context: Context) {
        val app = context.applicationContext
        val periodic = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(3, TimeUnit.HOURS).build()
        WorkManager.getInstance(app)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
        enqueueObserver(app, ExistingWorkPolicy.KEEP)
    }

    fun enqueueObserver(context: Context, policy: ExistingWorkPolicy) {
        // Root URI + descendants: the provider notifies on content://com.android.calendar
        // itself, so watching only .../events could miss notifications.
        val constraints = Constraints.Builder()
            .addContentUriTrigger(CalendarContract.CONTENT_URI, true)
            .setTriggerContentUpdateDelay(10, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(60, TimeUnit.SECONDS)
            .build()
        val request = OneTimeWorkRequestBuilder<ObserverWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(OBSERVER, policy, request)
    }
}

/** Runs when the calendar database changes. Content-URI triggers fire once, so it re-arms itself. */
class ObserverWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        // Queue the next watch first; as a child of this run it starts watching once we finish.
        SyncScheduler.enqueueObserver(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        try {
            AlarmScheduler.sync(applicationContext)
        } catch (e: Exception) {
            Log.w("ObserverWorker", "Sync failed", e)
        }
        return Result.success()
    }
}

/** Safety net: re-sync every few hours and make sure the observer exists. */
class PeriodicSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        try {
            AlarmScheduler.sync(applicationContext)
        } catch (e: Exception) {
            Log.w("PeriodicSyncWorker", "Sync failed", e)
        }
        SyncScheduler.enqueueObserver(applicationContext, ExistingWorkPolicy.KEEP)
        return Result.success()
    }
}
