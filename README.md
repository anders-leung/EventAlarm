# Event Alarms

Personal Android app: every upcoming Google Calendar event gets an alarm N minutes before it starts (default 10). Alarms are created, moved and removed automatically as your calendar changes. No manual alarm creation.

## Build and run

1. Android Studio -> Open -> select this `EventAlarm` folder, let Gradle sync (needs JDK 17; Studio's bundled JDK is fine).
2. Plug in your phone (USB debugging on) and Run.
3. Open the app and work down the **Setup** list until every row shows a check mark (details below).

Tested design targets: minSdk 28, targetSdk/compileSdk 35. I could not compile in the environment where this was written, so if Gradle or the compiler complains about a version, let Studio's upgrade assistant bump the AGP/Kotlin versions in `build.gradle.kts`.

## How it gets calendar changes (no constant polling)

- Google Calendar syncs your account into Android's on-device **Calendar Provider**. This app reads that database; it never talks to Google.
- `SyncScheduler` uses a WorkManager **content-URI trigger** on the provider. When a sync (or any edit, in any calendar app) changes an event, Android wakes the app, it re-syncs its alarms, then re-arms the trigger. The app does not need to be open or running in between.
- A **periodic backup** sync runs every ~3 hours. It slides the look-ahead window forward and re-creates the trigger if it ever went missing.
- A **boot receiver** rebuilds everything after reboot, app update, clock change or time-zone change (AlarmManager forgets alarms on reboot).

Caveat: a change reaches the phone only when Google Calendar syncs it. Events you create on the phone are instant; events created elsewhere arrive when the sync adapter pushes them, usually within seconds to a minute.

## "Remind me again" (second reminder after you silence the first)

Typical use: set the main lead time to 30 minutes, then when that alarm rings, choose whether you want another one closer to the start.

- **On the alarm screen** (full-screen, also over the lock screen), below Dismiss:
  - one-tap quick choices, default `15 min | 10 min | 5 min` (up to 4, you choose the values in Settings);
  - a free field for any other value ("remind me again [ 12 ] min before" -> Remind me).
  Any choice dismisses the current alarm and schedules a new alarm at *event start minus that many minutes*. Choices that would already be in the past are hidden or rejected.
- **On the notification**: a "Remind me N min before" button using your default value, so it works without opening the alarm screen.
- Plain **Dismiss** does what it did before: no second reminder.
- The second alarm rings the same way (alarm volume, full screen) and does not offer another reminder, so it can't chain.
- Pending reminders appear in the **Scheduled alarms** list labelled "Reminder:".
- If you delete, cancel or decline the event, or it moves to another time, the pending reminder is cancelled at the next sync (same as the main alarm). A rename just updates its title.

Settings (Apply and sync now saves them): *Default, minutes before* and *Quick choices on alarm screen*.

To try it: tap **Test alarm in 10 seconds**. It pretends to be an event starting 30 minutes after it rings, so entering 29 in the custom field gives a reminder about a minute later.

## Cleanup behaviour (`AlarmScheduler.sync`)

Each sync diffs "alarms that should exist" against "alarms I registered earlier":

- event deleted, cancelled, or you declined it -> alarm cancelled
- event moved to a new time -> old alarm cancelled, new one scheduled (instances are keyed by event id + start time)
- event renamed -> alarm refreshed with the new title
- pending "remind me again" alarms follow the same rules (kept while the event is unchanged, cancelled if it is gone or moved)
- alarm already fired -> record dropped
- calendar switched off in the app, or master switch turned off -> its alarms cancelled
- if the calendar can't be read (provider error), existing alarms are kept rather than wiped

Skipped on purpose: all-day events (no start time), events that start sooner than the lead time (alarm time already passed), and calendars that are hidden in your Calendar app.

## Why it doesn't write into the Clock app

Android has no supported way for another app to list, update or delete alarms in the Clock app, and launching Clock from the background is restricted on modern Android. Alarms would pile up and could never be cleaned up reliably. Instead this app registers real alarm-clock alarms with Android (`AlarmManager.setAlarmClock`):

- they show as your system "next alarm" (status bar icon, lock screen, quick settings)
- they are exempt from Doze
- they ring through `AlarmRingService` on the **alarm audio stream**, so **vibrate and silent ringer modes do not mute them**
- they appear as a full-screen alarm over the lock screen with a Dismiss button

## Device settings checklist

The Setup list in the app checks these and has a Fix button for each:

1. **Calendar access** - required.
2. **Notifications** (Android 13+) - needed for the alarm screen and Dismiss button.
3. **Exact alarms** - auto-granted on Android 13+; on Android 12 the Fix button opens the special-access page.
4. **Full-screen alarm over lock screen** (Android 14+) - Settings -> Apps -> Special app access -> Full screen intents.
5. **Battery: unrestricted** - lets background sync run on schedule.

Also check manually:

- **Alarm volume** is above zero (Settings -> Sound -> Alarm volume). This is separate from the ringer.
- **Do Not Disturb**: alarms are allowed by default; make sure "Alarms" is still enabled in DND exceptions.
- **Do not Force Stop the app.** Force stop deletes its alarms and background jobs until you open it again. Swiping it out of recents is fine.
- **Samsung / Xiaomi / OnePlus / Oppo**: also remove the app from "sleeping/deep-sleeping apps" and enable auto-start if your phone has that option. See dontkillmyapp.com for your model.

## Verify it works

1. Tap **Test alarm in 10 seconds**, put the phone on vibrate, lock it. It should ring.
2. Create a calendar event starting about 20 minutes from now. Within a minute or so it should appear under **Scheduled alarms**.
3. Move or delete the event and watch the entry change or disappear.

## Files

| File | Role |
| --- | --- |
| `CalendarReader.kt` | Reads event instances (recurring events expanded) from the Calendar Provider |
| `AlarmScheduler.kt` | Sync + cleanup logic, registers/cancels alarms |
| `SyncScheduler.kt` | Content-URI observer and periodic backup (WorkManager) |
| `BootReceiver.kt` | Rebuilds alarms after reboot, update, clock/time-zone change |
| `AlarmReceiver.kt`, `AlarmRingService.kt`, `AlarmActivity.kt` | Ringing, alarm-stream audio, vibration, full-screen UI |
| `MainActivity.kt` | Setup checklist, settings, calendar picker, alarm list, test button |
| `Prefs.kt` | Settings and the record of registered alarms |

## Not included (easy additions)

Plain snooze (re-ring after N minutes), per-keyword filters, a different lead time per calendar, custom ringtone.
