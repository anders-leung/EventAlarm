package com.example.eventalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/**
 * Full-screen alarm shown over the lock screen while the alarm is ringing.
 * Offers "Dismiss", and (for the main alarm of an event) "remind me again N minutes before".
 */
class AlarmActivity : AppCompatActivity() {

    companion object {
        const val ACTION_STOPPED = "com.example.eventalarm.ALARM_STOPPED"
    }

    private var eventTitle = ""
    private var eventBegin = 0L
    private var eventId = 0L

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        eventTitle = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "Upcoming event"
        eventBegin = intent.getLongExtra(AlarmScheduler.EXTRA_BEGIN, 0L)
        eventId = intent.getLongExtra(AlarmScheduler.EXTRA_EVENT_ID, 0L)
        val isFollowUp = intent.getBooleanExtra(AlarmScheduler.EXTRA_FOLLOW_UP, false)
        val pad = dp(24)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = eventTitle
            textSize = 30f
            gravity = Gravity.CENTER
        }, matchWidth())

        root.addView(TextView(this).apply {
            text = if (eventBegin > 0) "Starts at ${Format.time(this@AlarmActivity, eventBegin)}" else ""
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad)
        }, matchWidth())

        root.addView(MaterialButton(this).apply {
            text = "Dismiss"
            textSize = 20f
            setOnClickListener {
                startService(
                    Intent(this@AlarmActivity, AlarmRingService::class.java)
                        .setAction(AlarmRingService.ACTION_DISMISS)
                )
                finish()
            }
        }, matchWidth())

        // Only the first alarm of an event offers a follow-up, and only if there is still time.
        if (!isFollowUp && eventId != 0L && AlarmScheduler.canRemindAgain(eventBegin, 1)) {
            addRemindSection(root)
        }

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })

        // Close this screen when the service stops (timeout, or Dismiss from the notification).
        ContextCompat.registerReceiver(
            this, stopReceiver, IntentFilter(ACTION_STOPPED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun addRemindSection(root: LinearLayout) {
        val prefs = Prefs(this)

        root.addView(TextView(this).apply {
            text = "Dismiss and remind me again before it starts:"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(8))
        }, matchWidth())

        // One-tap presets, hiding any that would already be in the past.
        val presets = prefs.followUpChoices.filter { AlarmScheduler.canRemindAgain(eventBegin, it) }
        if (presets.isNotEmpty()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (minutes in presets) {
                val button = MaterialButton(
                    this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = "$minutes min"
                    setOnClickListener { remindAgain(minutes) }
                }
                row.addView(button, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            root.addView(row, matchWidth())
        }

        // Any other value.
        val custom = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "minutes"
            setText(prefs.followUpMinutes.toString())
            gravity = Gravity.CENTER
            minWidth = dp(90)
        }
        val customRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(custom)
            addView(TextView(this@AlarmActivity).apply {
                text = "min before"
                textSize = 16f
                setPadding(dp(8), 0, dp(8), 0)
            })
            addView(MaterialButton(this@AlarmActivity).apply {
                text = "Remind me"
                setOnClickListener {
                    val minutes = custom.text.toString().toIntOrNull()
                    if (minutes == null || minutes < 1) {
                        Toast.makeText(
                            this@AlarmActivity, "Enter a number of minutes", Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        remindAgain(minutes)
                    }
                }
            })
        }
        root.addView(customRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }

    private fun remindAgain(minutes: Int) {
        if (!AlarmScheduler.canRemindAgain(eventBegin, minutes)) {
            Toast.makeText(
                this, "Too close to the start for a $minutes-minute reminder", Toast.LENGTH_LONG
            ).show()
            return
        }
        // The service schedules the follow-up, silences this alarm and closes this screen.
        startService(
            Intent(this, AlarmRingService::class.java).apply {
                action = AlarmRingService.ACTION_REMIND_AGAIN
                putExtra(AlarmScheduler.EXTRA_TITLE, eventTitle)
                putExtra(AlarmScheduler.EXTRA_BEGIN, eventBegin)
                putExtra(AlarmScheduler.EXTRA_EVENT_ID, eventId)
                putExtra(AlarmScheduler.EXTRA_MINUTES, minutes)
            }
        )
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        unregisterReceiver(stopReceiver)
        super.onDestroy()
    }
}
