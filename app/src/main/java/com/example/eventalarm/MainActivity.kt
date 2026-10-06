package com.example.eventalarm

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs

    private lateinit var setupContainer: LinearLayout
    private lateinit var calendarsContainer: LinearLayout
    private lateinit var alarmsText: TextView
    private lateinit var statusText: TextView
    private lateinit var leadInput: EditText
    private lateinit var lookaheadInput: EditText
    private lateinit var followUpInput: EditText
    private lateinit var choicesInput: EditText

    private val calendarPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) runSync() else refresh()
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        root.addView(text("Event Alarms", 26f, bold = true))
        root.addView(text("Rings on the alarm volume before your calendar events.", 14f))

        root.addView(header("Setup"))
        setupContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(setupContainer)

        root.addView(header("Settings"))
        val enabledSwitch = SwitchCompat(this).apply {
            text = "Alarms enabled"
            isChecked = prefs.enabled
            setOnCheckedChangeListener { _, checked ->
                prefs.enabled = checked
                runSync()
            }
        }
        root.addView(enabledSwitch)

        leadInput = numberInput(prefs.leadMinutes.toString())
        lookaheadInput = numberInput(prefs.lookaheadDays.toString())
        root.addView(labeled("Minutes before event start", leadInput))
        root.addView(labeled("Look ahead (days, max 30)", lookaheadInput))

        followUpInput = numberInput(prefs.followUpMinutes.toString())
        choicesInput = textInput(prefs.followUpChoices.joinToString(", "))
        root.addView(text("\"Remind me again\" (shown when an alarm rings)", 14f, bold = true))
        root.addView(labeled("Default, minutes before (notification button)", followUpInput))
        root.addView(labeled("Quick choices on alarm screen (up to 4)", choicesInput))

        root.addView(button("Apply and sync now") {
            prefs.leadMinutes = leadInput.text.toString().toIntOrNull()?.coerceIn(0, 1440) ?: 10
            prefs.lookaheadDays = lookaheadInput.text.toString().toIntOrNull()?.coerceIn(1, 30) ?: 7
            prefs.followUpMinutes = followUpInput.text.toString().toIntOrNull()?.coerceIn(1, 1440) ?: 10
            prefs.followUpChoices = Prefs.parseChoices(choicesInput.text.toString())
                .ifEmpty { Prefs.parseChoices(Prefs.DEFAULT_CHOICES) }
            leadInput.setText(prefs.leadMinutes.toString())
            lookaheadInput.setText(prefs.lookaheadDays.toString())
            followUpInput.setText(prefs.followUpMinutes.toString())
            choicesInput.setText(prefs.followUpChoices.joinToString(", "))
            runSync()
        })
        root.addView(button("Test alarm in 10 seconds") {
            if (AlarmScheduler.scheduleTest(this)) {
                Toast.makeText(
                    this,
                    "Test alarm set. Try vibrate mode, and the remind-again buttons (29 min = rings ~1 min later).",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this, "Couldn't schedule: allow exact alarms first.", Toast.LENGTH_LONG).show()
            }
        })

        statusText = text("", 13f)
        root.addView(statusText)

        root.addView(header("Calendars"))
        calendarsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(calendarsContainer)

        root.addView(header("Scheduled alarms"))
        alarmsText = text("", 14f)
        root.addView(alarmsText)
    }

    override fun onResume() {
        super.onResume()
        SyncScheduler.start(this)
        if (CalendarReader.hasPermission(this)) runSync() else refresh()
    }

    // ---- sync + rendering ----

    private fun runSync() {
        statusText.text = "Syncing…"
        io.execute {
            val result = AlarmScheduler.sync(applicationContext)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                statusText.text = result.describe()
                refresh()
            }
        }
    }

    private fun refresh() {
        renderSetup()
        io.execute {
            val calendars = CalendarReader.listCalendars(applicationContext)
            val alarms = AlarmScheduler.upcoming(applicationContext)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                renderCalendars(calendars)
                renderAlarms(alarms)
            }
        }
    }

    private fun renderSetup() {
        setupContainer.removeAllViews()

        addSetupRow("Calendar access", CalendarReader.hasPermission(this)) {
            calendarPermission.launch(Manifest.permission.READ_CALENDAR)
        }

        val notificationsOk =
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) &&
                NotificationManagerCompat.from(this).areNotificationsEnabled()
        addSetupRow("Notifications", notificationsOk) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS, packageExtra = true)
            }
        }

        addSetupRow("Exact alarms", AlarmScheduler.canScheduleExact(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            }
        }

        val nm = getSystemService(NotificationManager::class.java)
        val fullScreenOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            nm.canUseFullScreenIntent()
        } else {
            true
        }
        addSetupRow("Full-screen alarm over lock screen", fullScreenOk) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                openSettings(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
            }
        }

        val pm = getSystemService(PowerManager::class.java)
        addSetupRow("Battery: unrestricted", pm.isIgnoringBatteryOptimizations(packageName)) {
            openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        }
    }

    private fun addSetupRow(label: String, ok: Boolean, fix: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            text((if (ok) "✓  " else "✗  ") + label, 15f),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (!ok) {
            row.addView(button("Fix") { fix() })
        }
        setupContainer.addView(row)
    }

    private fun renderCalendars(calendars: List<CalendarInfo>) {
        calendarsContainer.removeAllViews()
        if (calendars.isEmpty()) {
            calendarsContainer.addView(
                text(
                    if (CalendarReader.hasPermission(this)) "No calendars found. Is Google Calendar signed in and syncing?"
                    else "Grant calendar access to see your calendars.",
                    14f
                )
            )
            return
        }
        val excluded = prefs.excludedCalendarIds
        for (cal in calendars) {
            val box = MaterialCheckBox(this).apply {
                val hidden = if (cal.visible) "" else "  (hidden in Calendar app)"
                text = "${cal.name}\n${cal.account}$hidden"
                isChecked = cal.id !in excluded
                setOnCheckedChangeListener { _, checked ->
                    val updated = prefs.excludedCalendarIds.toMutableSet()
                    if (checked) updated.remove(cal.id) else updated.add(cal.id)
                    prefs.excludedCalendarIds = updated
                    runSync()
                }
            }
            calendarsContainer.addView(box)
        }
    }

    private fun renderAlarms(alarms: List<ScheduledAlarm>) {
        alarmsText.text = if (alarms.isEmpty()) {
            "Nothing scheduled. Events starting within your lead time are skipped, as are all-day events."
        } else {
            alarms.joinToString("\n\n") {
                val kind = if (it.followUp) "Reminder: " else ""
                "${Format.dateTime(this, it.triggerAt)}\n$kind${it.title} (starts ${Format.time(this, it.eventBegin)})"
            }
        }
    }

    // ---- small UI helpers ----

    private fun openSettings(action: String, packageExtra: Boolean = false) {
        val intent = Intent(action)
        if (packageExtra) {
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            intent.data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun text(content: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = content
            textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun header(content: String): TextView =
        text(content, 18f, bold = true).apply { setPadding(0, dp(20), 0, dp(4)) }

    private fun button(label: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun numberInput(initial: String): EditText =
        EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initial)
            minWidth = dp(80)
        }

    private fun textInput(initial: String): EditText =
        EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(initial)
            minWidth = dp(100)
        }

    private fun labeled(label: String, input: EditText): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(label, 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(input)
        }
}
