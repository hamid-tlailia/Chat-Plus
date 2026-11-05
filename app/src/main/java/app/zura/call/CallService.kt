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
import kotlinx.coroutines.launch
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

    override fun onDestroy() { runningType = null; Ringer.stop(); super.onDestroy() }

    /** The signed-in account, read straight from the auth state (no derived flow that may lag). */
    private fun activeSession() = (graph.accounts.state.value as? app.zura.account.AuthState.SignedIn)?.session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = activeSession()
        when (intent?.action) {
            ACTION_PRE_RING -> if (session?.calls?.ui?.value?.inCall != true) { preRing(intent.getStringExtra(EXTRA_NAME), intent.getStringExtra(EXTRA_FROM)); return START_NOT_STICKY }
            ACTION_CANCEL_PRE -> if (session?.calls?.ui?.value?.inCall != true) {
                preRingUntil = 0
                Ringer.stop()
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                getSystemService(NotificationManager::class.java).cancel(ID)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_HANGUP, ACTION_DECLINE, ACTION_ANSWER -> {
                val action = intent.action
                val forCall = intent.getStringExtra(EXTRA_CALL_ID)
                app.zura.DiagLog.record("call", detail = "notification ${action?.substringAfterLast('.')} for ${forCall?.take(8) ?: "pre-ring"} (current=${session?.calls?.ui?.value?.callId?.take(8)})")
                // A freshly woken app may still be opening the account / decrypting the invite:
                // wait a moment for THAT call (by id) instead of ignoring the tap.
                graph.scope.launch {
                    val calls = kotlinx.coroutines.withTimeoutOrNull(8_000) {
                        while (true) {
                            val c = activeSession()?.calls
                            val ui = c?.ui?.value
                            if (c != null && ui != null && ui.inCall && (forCall == null || ui.callId == forCall)) return@withTimeoutOrNull c
                            kotlinx.coroutines.delay(100)
                        }
                        @Suppress("UNREACHABLE_CODE") null
                    }
                    if (calls == null) {
                        // Stale button (that call is over, or never arrived): just clean up.
                        app.zura.DiagLog.record("call", detail = "notification button for a call that is gone → cleared")
                        clearStale(this@CallService, activeSession()?.calls?.ui?.value?.inCall == true)
                        return@launch
                    }
                    when (action) {
                        ACTION_HANGUP -> calls.hangup()
                        ACTION_DECLINE -> calls.decline()
                        else -> calls.accept() // (Answer normally goes through the activity, see build())
                    }
                }
                return START_NOT_STICKY
            }
        }
        val ui = session?.calls?.ui?.value
        if ((ui == null || !ui.inCall) && System.currentTimeMillis() < preRingUntil) return START_NOT_STICKY // provisional ring still up
        if (ui == null || !ui.inCall) {
            // No call any more (ended, or the process was restarted and lost it): never leave a
            // dead ringing notification behind.
            Ringer.stop()
            getSystemService(NotificationManager::class.java).cancel(ID)
            stopSelf()
            return START_NOT_STICKY
        }
        // Ring while it's ringing — in the foreground AND the background (the notification is silent).
        if (ui.phase == CallPhase.INCOMING) Ringer.start(this, ui.peerId) else Ringer.stop()
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

    /**
     * Ring NOW, before anything else: a call push woke a killed app. Android allows starting this
     * only shortly after the push — opening the account and decrypting the invite come after.
     * Replaced by the real call notification within a second; gone after 20 s if no call shows up.
     */
    private fun preRing(name: String?, from: String?) {
        preRingUntil = System.currentTimeMillis() + 20_000
        val ui = CallUi(phase = CallPhase.INCOMING, peerName = name ?: "Zura", peerId = from)
        runCatching {
            val n = build(this, ui)
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0)
            else startForeground(ID, n)
            runningType = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0
            Ringer.start(this, from)
        }.onFailure {
            // Not allowed to run in the foreground: a plain notification that rings by itself.
            app.zura.DiagLog.record("pre-ring", it)
            runCatching { getSystemService(NotificationManager::class.java).notify(ID, build(this, ui, channelRings = true)) }
        }
        android.os.Handler(mainLooper).postDelayed({
            if (activeSession()?.calls?.ui?.value?.inCall != true) {
                preRingUntil = 0
                Ringer.stop()
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                getSystemService(NotificationManager::class.java).cancel(ID)
                stopSelf()
            }
        }, 20_000)
    }

    companion object {
        private const val ID = 7001
        const val ACTION_PRE_RING = "zura.call.PRE_RING"
        const val EXTRA_CALL_ID = "zura.call_id"
        const val EXTRA_NAME = "zura.caller_name"
        const val EXTRA_FROM = "zura.caller_id"
        const val ACTION_CANCEL_PRE = "zura.call.CANCEL_PRE"

        /** The pushed call turned out invalid (blocked, expired, undecryptable): stop the provisional ring. */
        fun cancelPreRing(context: Context) {
            if (preRingUntil == 0L) return
            runCatching { context.startService(Intent(context, CallService::class.java).setAction(ACTION_CANCEL_PRE)) }
        }
        /** While a provisional ring is up, "no call yet" is expected — don't clear it. */
        @Volatile var preRingUntil = 0L

        /** Caller names cached outside the account (uid → name): the provisional ring shows who calls. */
        fun rememberNames(context: Context, names: Map<String, String>) {
            context.getSharedPreferences("zura_callers", Context.MODE_PRIVATE).edit().apply { clear(); names.forEach { (k, v) -> putString(k, v) } }.apply()
        }

        fun preRing(context: Context, from: String? = null) {
            val name = from?.let { context.getSharedPreferences("zura_callers", Context.MODE_PRIVATE).getString(it, null) }
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CallService::class.java).setAction(ACTION_PRE_RING).putExtra(EXTRA_NAME, name).putExtra(EXTRA_FROM, from)) }
                .onFailure { app.zura.DiagLog.record("pre-ring start", it) }
        }
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
                    // Background-start restrictions: still ring/show the call via a plain notification
                    // (no service to play the ringtone → the notification channel rings).
                    app.zura.DiagLog.record("call fgs start", it)
                    runCatching { context.getSystemService(NotificationManager::class.java).notify(ID, build(context, ui, channelRings = ui.phase == CallPhase.INCOMING)) }
                }
        }

        /** App opened with no call in progress: remove a ringing notification a killed process left behind. */
        fun clearStale(context: Context, inCall: Boolean) {
            if (!inCall && System.currentTimeMillis() >= preRingUntil) runCatching { context.getSystemService(NotificationManager::class.java).cancel(ID) }
        }

        fun stop(context: Context) {
            Ringer.stop()
            context.stopService(Intent(context, CallService::class.java))
            context.getSystemService(NotificationManager::class.java).cancel(ID)
        }

        /** [channelRings]: only when no service runs to play the ringtone ([Ringer]) — the notification rings itself. */
        fun build(c: Context, ui: CallUi, channelRings: Boolean = false): Notification {
            ensureChannels(c)
            val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val person = Person.Builder().setName(ui.peerName.ifBlank { "Zura" }).setImportant(true).build()
            // Every button carries ITS call id: a stale notification can't act on another call.
            fun action(a: String) = PendingIntent.getService(
                c, (a + ui.callId).hashCode(),
                Intent(c, CallService::class.java).setAction(a).putExtra(EXTRA_CALL_ID, ui.callId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            // Answer opens the app DIRECTLY (allowed from a notification). A service starting the
            // activity is blocked on Android 12+ ("trampoline"), and the microphone can't start
            // from the background — answering that way used to do nothing / have no audio.
            val answer = PendingIntent.getActivity(
                c, ("answer" + ui.callId).hashCode(),
                Intent(c, MainActivity::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_CALL_ID, ui.callId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val incoming = ui.phase == CallPhase.INCOMING
            val style = if (incoming) NotificationCompat.CallStyle.forIncomingCall(person, action(ACTION_DECLINE), answer)
            else NotificationCompat.CallStyle.forOngoingCall(person, action(ACTION_HANGUP))
            // Normally silent: [Ringer] rings (foreground and background alike). Only without a
            // running service does the notification ring by itself, looping until answered.
            val ringing = incoming && channelRings
            val ringCh = if (ringing) runCatching { (c.applicationContext as app.zura.ZuraApp).graph.sounds.ringChannel(c, ui.peerId, CH_RING) }.getOrDefault(CH_RING) else CH_RING
            return NotificationCompat.Builder(c, if (ringing) ringCh else if (incoming) CH_INCOMING else CH_ONGOING)
                .setSmallIcon(R.drawable.ic_stat_zura)
                .setStyle(style)
                .setContentTitle(ui.peerName)
                .setContentText(c.getString(if (ui.video) R.string.video_call else R.string.voice_call))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                // The provisional ring is replaced by the real call notification: alert ONCE (the
                // replacement used to ring a second time).
                .setOnlyAlertOnce(true)
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
