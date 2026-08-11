package dev.inkstone.app.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.inkstone.app.MainActivity
import dev.inkstone.app.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ⭐ M12 §9 — the foreground service that keeps a lecture recording.
 *
 * ## What it is actually for, which is two things
 *
 * **Android requires it.** Microphone capture from a process that is not visible needs a
 * foreground service typed `microphone`; without one the audio goes silent the moment the screen
 * dims, which is the whole failure §9 describes.
 *
 * **And its notification is the honesty floor.** *"Recording a room full of people"* is not
 * something an app should be able to do quietly. The notification is permanent and un-dismissable
 * while the service runs, and it carries a live elapsed timer — §10: the failure that hurts is
 * recording for fifty minutes and finding out you were not, and *"its mirror, believing you
 * stopped when you had not, is the privacy version of the same bug."*
 *
 * ## ⚠️ It does not own the recording
 *
 * [RecordingController] does. This service holds the notification and the process, and answers
 * back once it is genuinely in the foreground — see [RecordingController.onForegroundReady]. The
 * microphone opens **after** that, never with it: opening it first works while the app is visible
 * and fails the first time a lecture starts with the screen already off, which is not a case
 * anybody tests by hand.
 *
 * A started service, not a bound one: binding is scoped to a component, and the component here is
 * an Activity that is allowed to go away.
 */
@AndroidEntryPoint
class RecordingService : Service() {

    @Inject
    lateinit var controller: RecordingController

    @Inject
    lateinit var notifications: RecordingNotifications

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * ⚠️ `ForegroundServiceType` is suppressed, and **the manifest does declare it** —
     * `android:foregroundServiceType="microphone"` on this service, which the merged manifest
     * confirms. Lint's check resolves the `<service>` element from the enclosing class and does not
     * find it here; `@AndroidEntryPoint` rewrites this class to extend a generated
     * `Hilt_RecordingService`, which is the only thing unusual about it.
     *
     * Suppressing a lint error that is really right would be exactly the wrong move, so this one is
     * **checked on a device rather than argued about**: if the attribute were genuinely missing,
     * `startForeground` with a type throws `MissingForegroundServiceTypeException` on API 34+ the
     * first time record is pressed, which the emulator walk in `docs/M12-AUDIO.md` §15 exercises.
     */
    @Suppress("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ⚠️ Before anything else. `startForegroundService` gives five seconds to get here or the
        // app is killed, and typing it `MICROPHONE` is what API 34+ requires for the capture to be
        // allowed to continue once the app is not visible.
        startForeground(
            RecordingNotifications.NOTIFICATION_ID,
            notifications.recording(SystemClock.elapsedRealtime()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        controller.onForegroundReady()
        // START_NOT_STICKY: a process killed mid-lecture must **not** be restarted into a service
        // with no recording behind it. §9's answer to that case is orphan recovery on the next
        // launch, which finalises the Ogg already on disk and offers it — restarting the microphone
        // instead would leave a second, empty recording beside it and no way to tell them apart.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Idempotent, and deliberately not a stop of the recording: the controller stops the
        // service, not the other way round. If this is reached with a recording still live it is
        // because the system took the service away, and the recorder's own failure path reports it.
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, RecordingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}

/**
 * The recording notification, and the channel it lives on.
 *
 * Separate from the service so [RecordingNotifications.recording] can be built without one — the
 * service needs it inside `onStartCommand`'s five-second budget, which is not a place to be
 * creating a channel for the first time.
 */
@Singleton
class RecordingNotifications @Inject constructor(@ApplicationContext private val context: Context) {

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    /**
     * ⚠️ `IMPORTANCE_LOW`, which is the highest importance that makes **no sound**.
     *
     * A recording notification that pinged would ping in the silence at the start of every
     * lecture, which is how a feature about recording lectures gets turned off. It is still
     * un-dismissable and still always visible, which is the part that matters.
     */
    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.recording_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.recording_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * @param anchorElapsedRealtime when the audio started, on the monotonic clock.
     *
     * ⚠️ The timer is a **chronometer**, so it counts up on its own and the service never posts a
     * second notification. `setWhen` wants wall-clock, so the anchor is converted once — the only
     * place in this feature where elapsed time meets `System.currentTimeMillis`, and it is for
     * drawing a number rather than for ordering anything.
     */
    fun recording(anchorElapsedRealtime: Long): Notification {
        ensureChannel()
        val elapsed = SystemClock.elapsedRealtime() - anchorElapsedRealtime
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setContentTitle(context.getString(R.string.recording_notification_title))
            .setContentText(context.getString(R.string.recording_notification_text))
            .setWhen(System.currentTimeMillis() - elapsed)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val CHANNEL_ID: String = "inkstone.recording"
        const val NOTIFICATION_ID: Int = 4012
    }
}
