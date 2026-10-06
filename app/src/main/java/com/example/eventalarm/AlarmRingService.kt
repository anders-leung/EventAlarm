package com.example.eventalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Plays the alarm on the ALARM audio stream (like a Clock alarm), so it is audible even when
 * the ringer is on vibrate or silent, and shows a full-screen alarm UI over the lock screen.
 *
 * Also handles "remind me again N minutes before": schedules a follow-up alarm and silences
 * the current one.
 */
class AlarmRingService : Service() {

    companion object {
        const val ACTION_START = "com.example.eventalarm.START"
        const val ACTION_DISMISS = "com.example.eventalarm.DISMISS"
        const val ACTION_REMIND_AGAIN = "com.example.eventalarm.REMIND_AGAIN"
        private const val TAG = "AlarmRingService"
        private const val CHANNEL_ID = "event_alarms"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_RING_MS = 5 * 60 * 1000L
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var focusRequest: AudioFocusRequest? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stopRinging() }

    private val alarmAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> {
                stopRinging()
                return START_NOT_STICKY
            }
            ACTION_REMIND_AGAIN -> {
                remindAgain(intent)
                return START_NOT_STICKY
            }
        }
        val title = intent?.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "Upcoming event"
        val begin = intent?.getLongExtra(AlarmScheduler.EXTRA_BEGIN, 0L) ?: 0L
        val eventId = intent?.getLongExtra(AlarmScheduler.EXTRA_EVENT_ID, 0L) ?: 0L
        val followUp = intent?.getBooleanExtra(AlarmScheduler.EXTRA_FOLLOW_UP, false) ?: false
        startRinging(title, begin, eventId, followUp)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseResources()
        super.onDestroy()
    }

    private fun remindAgain(intent: Intent) {
        val minutes = intent.getIntExtra(AlarmScheduler.EXTRA_MINUTES, 0)
        val eventId = intent.getLongExtra(AlarmScheduler.EXTRA_EVENT_ID, 0L)
        val begin = intent.getLongExtra(AlarmScheduler.EXTRA_BEGIN, 0L)
        val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "Upcoming event"

        val at = AlarmScheduler.scheduleFollowUp(this, eventId, begin, title, minutes)
        if (at != null) {
            Toast.makeText(
                this,
                "Reminder set for ${Format.time(this, at)} ($minutes min before)",
                Toast.LENGTH_LONG
            ).show()
            stopRinging()
        } else {
            // Leave the alarm ringing so it isn't silently lost.
            Toast.makeText(
                this,
                "Too close to the start for a $minutes-minute reminder.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun startRinging(title: String, begin: Long, eventId: Long, followUp: Boolean) {
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(title, begin, eventId, followUp),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        if (player == null) {
            startSound()
            startVibration()
        }
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, MAX_RING_MS)
    }

    private fun stopRinging() {
        releaseResources()
        sendBroadcast(Intent(AlarmActivity.ACTION_STOPPED).setPackage(packageName))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseResources() {
        handler.removeCallbacks(timeout)
        player?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                // already stopped
            }
            it.release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun startSound() {
        val audioManager = getSystemService(AudioManager::class.java)
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(alarmAttributes)
            .build()
            .also { audioManager.requestAudioFocus(it) }

        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(alarmAttributes)
            mp.setDataSource(this, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            Log.e(TAG, "Could not play alarm sound", e)
            mp.release()
        }
    }

    private fun startVibration() {
        val v = getSystemService(Vibrator::class.java) ?: return
        vibrator = v
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0)
        @Suppress("DEPRECATION")
        v.vibrate(effect, alarmAttributes)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Event alarms", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Rings shortly before calendar events"
                setSound(null, null) // the service plays the sound itself, on the alarm stream
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        title: String,
        begin: Long,
        eventId: Long,
        followUp: Boolean
    ): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val alarmScreen = PendingIntent.getActivity(
            this,
            0,
            Intent(this, AlarmActivity::class.java).apply {
                putExtra(AlarmScheduler.EXTRA_TITLE, title)
                putExtra(AlarmScheduler.EXTRA_BEGIN, begin)
                putExtra(AlarmScheduler.EXTRA_EVENT_ID, eventId)
                putExtra(AlarmScheduler.EXTRA_FOLLOW_UP, followUp)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            flags
        )
        val dismiss = PendingIntent.getService(
            this,
            1,
            Intent(this, AlarmRingService::class.java).setAction(ACTION_DISMISS),
            flags
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(if (begin > 0) "Starts at ${Format.time(this, begin)}" else "Starting soon")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(alarmScreen)
            .setFullScreenIntent(alarmScreen, true)
            .addAction(0, "Dismiss", dismiss)

        // One-tap "remind me again" using your default (more choices are on the alarm screen).
        val defaultMinutes = Prefs(this).followUpMinutes
        if (!followUp && eventId != 0L && AlarmScheduler.canRemindAgain(begin, defaultMinutes)) {
            val remind = PendingIntent.getService(
                this,
                2,
                Intent(this, AlarmRingService::class.java).apply {
                    action = ACTION_REMIND_AGAIN
                    putExtra(AlarmScheduler.EXTRA_TITLE, title)
                    putExtra(AlarmScheduler.EXTRA_BEGIN, begin)
                    putExtra(AlarmScheduler.EXTRA_EVENT_ID, eventId)
                    putExtra(AlarmScheduler.EXTRA_MINUTES, defaultMinutes)
                },
                flags
            )
            builder.addAction(0, "Remind me ${defaultMinutes} min before", remind)
        }
        return builder.build()
    }
}
