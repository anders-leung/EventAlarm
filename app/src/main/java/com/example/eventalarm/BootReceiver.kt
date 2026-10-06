package com.example.eventalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * AlarmManager forgets every alarm on reboot, and a time-zone or clock change shifts what
 * "10 minutes before" means, so rebuild everything in those cases (and after an app update).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED -> {
                val pending = goAsync()
                Thread {
                    try {
                        SyncScheduler.start(context)
                        AlarmScheduler.sync(context)
                    } catch (e: Exception) {
                        Log.w("BootReceiver", "Resync failed", e)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }
}
