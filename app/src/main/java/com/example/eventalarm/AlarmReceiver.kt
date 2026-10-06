package com.example.eventalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Fired by AlarmManager at the alarm time. Hands off to the ringing service. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_ALARM) return
        val service = Intent(context, AlarmRingService::class.java).apply {
            action = AlarmRingService.ACTION_START
            putExtra(AlarmScheduler.EXTRA_TITLE, intent.getStringExtra(AlarmScheduler.EXTRA_TITLE))
            putExtra(AlarmScheduler.EXTRA_BEGIN, intent.getLongExtra(AlarmScheduler.EXTRA_BEGIN, 0L))
            putExtra(AlarmScheduler.EXTRA_EVENT_ID, intent.getLongExtra(AlarmScheduler.EXTRA_EVENT_ID, 0L))
            putExtra(AlarmScheduler.EXTRA_FOLLOW_UP, intent.getBooleanExtra(AlarmScheduler.EXTRA_FOLLOW_UP, false))
        }
        ContextCompat.startForegroundService(context, service)
    }
}
