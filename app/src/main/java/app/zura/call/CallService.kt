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
        val ui = session?.calls?.ui?.value ?: return START_NOT_STICKY.also { stopSelf() }
        if (!ui.inCall) { stopSelf(); return START_NOT_STICKY }
        val n = build(ui)
        val type = if (Build.VERSION.SDK_INT >= 30) {
            var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (ui.video && ui.phase != CallPhase.INCOMING) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            t
        } else 0
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, type) else startForeground(ID, n)
        }.onFailure {
            // Missing runtime permission (e.g. camera denied): fall back to a plain notification.
            getSystemService(NotificationManager::class.java).notify(ID, n)
        }
        return START_NOT_STICKY
    }

    private fun build(ui: CallUi): Notification {
        ensureChannels(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val person = Person.Builder().setName(ui.peerName.ifBlank { "Zura" }).setImportant(true).build()
        fun action(a: String) = PendingIntent.getService(this, a.hashCode(), Intent(this, CallService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        val incoming = ui.phase == CallPhase.INCOMING
        val style = if (incoming) NotificationCompat.CallStyle.forIncomingCall(person, action(ACTION_DECLINE), action(ACTION_ANSWER))
        else NotificationCompat.CallStyle.forOngoingCall(person, action(ACTION_HANGUP))
        return NotificationCompat.Builder(this, if (incoming) CH_INCOMING else CH_ONGOING)
            .setSmallIcon(R.drawable.ic_launcher)
            .setStyle(style)
            .setContentTitle(ui.peerName)
            .setContentText(getString(if (ui.video) R.string.video_call else R.string.voice_call))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(open)
            .apply {
                if (incoming) { setFullScreenIntent(open, true); priority = NotificationCompat.PRIORITY_MAX }
                ui.connectedAt?.let { setWhen(it); setUsesChronometer(true) }
            }
            .build()
    }

    companion object {
        private const val ID = 7001
        private const val CH_INCOMING = "calls_incoming"
        private const val CH_ONGOING = "calls_ongoing"
        const val ACTION_HANGUP = "zura.call.HANGUP"
        const val ACTION_ANSWER = "zura.call.ANSWER"
        const val ACTION_DECLINE = "zura.call.DECLINE"

        fun start(context: Context, @Suppress("UNUSED_PARAMETER") ui: CallUi) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CallService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
            context.getSystemService(NotificationManager::class.java).cancel(ID)
        }

        private fun ensureChannels(c: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_INCOMING, c.getString(R.string.incoming_calls), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // the app plays the ringtone itself (stops instantly on answer)
                enableVibration(false)
            })
            nm.createNotificationChannel(NotificationChannel(CH_ONGOING, c.getString(R.string.ongoing_calls), NotificationManager.IMPORTANCE_LOW))
        }
    }
}
