package app.zura.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import app.zura.MainActivity
import app.zura.R
import app.zura.graph

/**
 * Keeps a call alive (mic/camera access, process priority) while the user leaves the app, and
 * shows the system call notification: ongoing (with hang-up) or incoming (answer/decline,
 * full-screen on a locked phone).
 */
class CallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    /** Foreground-service types currently granted (null = not in the foreground yet). */
    private var runningType: Int? = null

    override fun onDestroy() { runningType = null; super.onDestroy() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = graph.session.value
        when (intent?.action) {
            ACTION_HANGUP -> { session?.calls?.hangup(); return START_NOT_STICKY }
            ACTION_DECLINE -> { session?.calls?.decline(); return START_NOT_STICKY }
            ACTION_ANSWER -> {
                session?.calls?.accept()
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        val ui = session?.calls?.ui?.value
        if (ui == null || !ui.inCall) {
            // No call any more (ended, or the process was restarted and lost it): never leave a
            // dead ringing notification behind.
            getSystemService(NotificationManager::class.java).cancel(ID)
            stopSelf()
            return START_NOT_STICKY
        }
        val n = build(this, ui)
        val type = if (Build.VERSION.SDK_INT >= 30) {
            // Ringing: "phone call" type — allowed to start from the background (a push), unlike the
            // microphone type, whose refusal used to leave the call without a service: Android then
            // killed the app, the call screen never showed and Answer/Decline did nothing.
            // Microphone / camera are added once the call is answered (the app is in front by then).
            var t = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0
            if (ui.phase != CallPhase.INCOMING) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (ui.video && ui.phase != CallPhase.INCOMING) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (ui.sharingScreen) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            t
        } else 0
        val nm = getSystemService(NotificationManager::class.java)
        // Same types as now → only refresh the notification. Re-calling startForeground from the
        // background with microphone/camera is refused by Android, and the old fallback then
        // DOWNGRADED the running service — the call could lose its mic after a reconnect.
        if (runningType == type) { nm.notify(ID, n); return START_NOT_STICKY }
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, type) else startForeground(ID, n)
            runningType = type
            if (ui.sharingScreen) projectionReady = true
        }.recoverCatching {
            app.zura.DiagLog.record("call fgs", it)
            if (runningType != null) nm.notify(ID, n) // already running: keep the types it has
            else {
                // First start refused (e.g. camera permission denied): run with the basic type.
                val basic = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0
                if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, basic) else startForeground(ID, n)
                runningType = basic
            }
        }.onFailure {
            app.zura.DiagLog.record("call fgs basic", it)
            nm.notify(ID, n)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 7001
        /** Set once the service runs with the mediaProjection type (required before screen capture). */
        @Volatile var projectionReady = false
        private const val CH_INCOMING = "calls_incoming"
        private const val CH_ONGOING = "calls_ongoing"
        private const val CH_RING = "calls_ring"
        const val ACTION_HANGUP = "zura.call.HANGUP"
        const val ACTION_ANSWER = "zura.call.ANSWER"
        const val ACTION_DECLINE = "zura.call.DECLINE"

        fun start(context: Context, ui: CallUi) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CallService::class.java)) }
                .onFailure {
                    // Background-start restrictions: still ring/show the call via a plain notification.
                    runCatching { context.getSystemService(NotificationManager::class.java).notify(ID, build(context, ui)) }
                }
        }

        /** App opened with no call in progress: remove a ringing notification a killed process left behind. */
        fun clearStale(context: Context, inCall: Boolean) {
            if (!inCall) runCatching { context.getSystemService(NotificationManager::class.java).cancel(ID) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
            context.getSystemService(NotificationManager::class.java).cancel(ID)
        }

        fun build(c: Context, ui: CallUi): Notification {
            ensureChannels(c)
            val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val person = Person.Builder().setName(ui.peerName.ifBlank { "Zura" }).setImportant(true).build()
            fun action(a: String) = PendingIntent.getService(c, a.hashCode(), Intent(c, CallService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
            val incoming = ui.phase == CallPhase.INCOMING
            val style = if (incoming) NotificationCompat.CallStyle.forIncomingCall(person, action(ACTION_DECLINE), action(ACTION_ANSWER))
            else NotificationCompat.CallStyle.forOngoingCall(person, action(ACTION_HANGUP))
            // App in background (woken by push): the notification itself rings, looping until answered.
            val ringing = incoming && !app.zura.push.Push.appVisible
            val ringCh = if (ringing) runCatching { (c.applicationContext as app.zura.ZuraApp).graph.sounds.ringChannel(c, ui.peerId, CH_RING) }.getOrDefault(CH_RING) else CH_RING
            return NotificationCompat.Builder(c, if (ringing) ringCh else if (incoming) CH_INCOMING else CH_ONGOING)
                .setSmallIcon(R.drawable.ic_stat_zura)
                .setStyle(style)
                .setContentTitle(ui.peerName)
                .setContentText(c.getString(if (ui.video) R.string.video_call else R.string.voice_call))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setContentIntent(open)
                .apply {
                    if (incoming) { setFullScreenIntent(open, true); priority = NotificationCompat.PRIORITY_MAX }
                    ui.connectedAt?.let { setWhen(it); setUsesChronometer(true) }
                    if (ringing) setTimeoutAfter(45_000)
                }
                .build()
                .apply { if (ringing) flags = flags or Notification.FLAG_INSISTENT }
        }

        private fun ensureChannels(c: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_INCOMING, c.getString(R.string.incoming_calls), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // the app plays the ringtone itself (stops instantly on answer)
                enableVibration(false)
            })
            nm.createNotificationChannel(NotificationChannel(CH_ONGOING, c.getString(R.string.ongoing_calls), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_RING, c.getString(R.string.incoming_calls_ring), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 700, 900)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
    }
}
