package app.zura.call

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import app.zura.DiagLog
import app.zura.R
import app.zura.account.AuthState
import app.zura.graph

/**
 * Zura as a SELF-MANAGED calling app in Android's Telecom framework.
 *
 * The system then knows a Zura call is ringing / active, like a phone call: higher priority for
 * the process, Bluetooth headset & car buttons answer / hang up, Do-Not-Disturb and a cellular
 * call coming in are arbitrated by the system, and other call apps see the line as busy.
 * Zura still draws its own UI (self-managed apps must); audio and media stay end-to-end
 * encrypted WebRTC — Telecom only carries the call STATE, never audio or content.
 *
 * Every step is optional: if a phone refuses (registration / incoming report fails), the call
 * simply continues on Zura's own ringing path as before.
 */
object Telecom {
    private const val ACCOUNT_ID = "zura"
    const val EXTRA_CALL_ID = "zura.call_id"
    const val EXTRA_NAME = "zura.name"
    const val EXTRA_VIDEO = "zura.video"

    @Volatile private var registered = false
    val isRegistered get() = registered
    /** The system-side connection of the current call (null = none / not using Telecom). */
    @Volatile var current: ZuraConnection? = null
        internal set
    /** Call id the current connection belongs to. */
    @Volatile private var currentCallId: String? = null

    private fun handle(c: Context) = PhoneAccountHandle(ComponentName(c, ZuraConnectionService::class.java), ACCOUNT_ID)
    private fun tm(c: Context) = c.getSystemService(TelecomManager::class.java)

    @Volatile var appContext: Context? = null
        private set

    /** Opt-in (Settings): some phones mishandle self-managed calls (ringing without UI, instant "missed"). */
    fun enabled(c: Context) = c.getSharedPreferences("zura", Context.MODE_PRIVATE).getBoolean(PREF, false)
    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences("zura", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply()
        if (on) register(c) else runCatching { tm(c).unregisterPhoneAccount(handle(c)); registered = false }
    }
    private const val PREF = "use_telecom"

    fun register(c: Context) {
        appContext = c.applicationContext
        if (!enabled(c)) {
            // Off (the default): also drop a registration left by 0.9.6.
            if (!registered) runCatching { tm(c).unregisterPhoneAccount(handle(c)) }
            return
        }
        if (registered) return
        runCatching {
            val account = PhoneAccount.builder(handle(c), c.getString(R.string.app_name))
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED or PhoneAccount.CAPABILITY_VIDEO_CALLING or PhoneAccount.CAPABILITY_SUPPORTS_VIDEO_CALLING)
                .addSupportedUriScheme(SCHEME)
                .build()
            tm(c).registerPhoneAccount(account)
            registered = true
        }.onFailure { DiagLog.record("telecom register", it) }
    }

    /** Keep the system's view of the call in step with Zura's call engine. */
    fun sync(c: Context, ui: CallUi) {
        if (!registered) return
        val id = ui.callId
        when {
            // A new call: tell the system.
            ui.inCall && id != null && id != currentCallId -> {
                currentCallId = id
                current = null
                runCatching {
                    val extras = Bundle().apply {
                        putString(EXTRA_CALL_ID, id); putString(EXTRA_NAME, ui.peerName); putBoolean(EXTRA_VIDEO, ui.video)
                    }
                    val address = Uri.fromParts(SCHEME, ui.peerId ?: "", null)
                    if (ui.phase == CallPhase.INCOMING) {
                        tm(c).addNewIncomingCall(handle(c), Bundle().apply {
                            putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, address)
                            putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, extras)
                            if (ui.video) putInt(TelecomManager.EXTRA_INCOMING_VIDEO_STATE, VideoProfile.STATE_BIDIRECTIONAL)
                        })
                    } else if (ui.outgoing) {
                        tm(c).placeCall(address, Bundle().apply {
                            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(c))
                            putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, extras)
                            if (ui.video) putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, VideoProfile.STATE_BIDIRECTIONAL)
                        })
                    }
                }.onFailure { DiagLog.record("telecom report", it) }
            }
            id == currentCallId && ui.inCall -> current?.let { conn ->
                when (ui.phase) {
                    CallPhase.CONNECTING, CallPhase.ACTIVE, CallPhase.RECONNECTING -> if (conn.state != Connection.STATE_ACTIVE) conn.setActive()
                    CallPhase.RINGING, CallPhase.OUTGOING -> if (conn.state == Connection.STATE_NEW || conn.state == Connection.STATE_INITIALIZING) conn.setDialing()
                    else -> Unit
                }
                conn.setCallerDisplayName(ui.peerName, TelecomManager.PRESENTATION_ALLOWED)
            }
            // The call ended (or went back to idle): close the system-side line.
            !ui.inCall && currentCallId != null -> {
                current?.let { conn ->
                    val cause = when (ui.endReason) {
                        "declined" -> DisconnectCause.REJECTED
                        "busy" -> DisconnectCause.BUSY
                        "timeout" -> DisconnectCause.MISSED
                        "failed" -> DisconnectCause.ERROR
                        else -> if (ui.phase == CallPhase.ENDED) DisconnectCause.REMOTE else DisconnectCause.LOCAL
                    }
                    runCatching { conn.setDisconnected(DisconnectCause(cause)); conn.destroy() }
                }
                current = null
                currentCallId = null
            }
        }
    }

    internal fun attach(conn: ZuraConnection, callId: String?) {
        if (callId != null && callId == currentCallId) current = conn
        else runCatching { conn.setDisconnected(DisconnectCause(DisconnectCause.CANCELED)); conn.destroy() } // stale
    }

    internal fun calls() = (appContext?.graph?.accounts?.state?.value as? AuthState.SignedIn)?.session?.calls

    /**
     * Loudspeaker for a call the system knows about: Telecom owns the audio route of a
     * self-managed call and can override the app's own choice, so the request goes to it.
     * False = no Telecom call (the app routes the audio itself).
     */
    fun route(speaker: Boolean): Boolean {
        val conn = current ?: return false
        return runCatching {
            val ep = if (Build.VERSION.SDK_INT >= 34) conn.endpoints.let { all ->
                if (speaker) all.firstOrNull { it.endpointType == android.telecom.CallEndpoint.TYPE_SPEAKER }
                else all.firstOrNull { it.endpointType == android.telecom.CallEndpoint.TYPE_BLUETOOTH }
                    ?: all.firstOrNull { it.endpointType == android.telecom.CallEndpoint.TYPE_WIRED_HEADSET }
                    ?: all.firstOrNull { it.endpointType == android.telecom.CallEndpoint.TYPE_EARPIECE }
            } else null
            if (Build.VERSION.SDK_INT >= 34 && ep != null) {
                conn.requestCallEndpointChange(ep, java.util.concurrent.Executors.newSingleThreadExecutor(),
                    object : android.os.OutcomeReceiver<Void, android.telecom.CallEndpointException> {
                        override fun onResult(result: Void?) = Unit
                        override fun onError(error: android.telecom.CallEndpointException) { DiagLog.record("telecom route", error) }
                    })
            } else {
                @Suppress("DEPRECATION")
                conn.setAudioRoute(if (speaker) android.telecom.CallAudioState.ROUTE_SPEAKER else android.telecom.CallAudioState.ROUTE_WIRED_OR_EARPIECE)
            }
            true
        }.onFailure { DiagLog.record("telecom route", it) }.getOrDefault(false)
    }

    const val SCHEME = "zura"
}

/** One Zura call as the system sees it. User actions from the system (headset, car…) → Zura. */
class ZuraConnection : Connection() {
    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        audioModeIsVoip = true
        connectionCapabilities = CAPABILITY_MUTE
    }

    // Self-managed apps show their own incoming-call UI: Zura's call notification + screen.
    override fun onShowIncomingCallUi() {
        val c = Telecom.appContext ?: return
        Telecom.calls()?.ui?.value?.let { ui -> runCatching { CallService.start(c, ui) } }
    }

    override fun onAnswer() { Telecom.calls()?.accept() }
    override fun onAnswer(videoState: Int) { Telecom.calls()?.accept() }
    override fun onReject() { Telecom.calls()?.decline() }
    override fun onReject(rejectReason: Int) { Telecom.calls()?.decline() }
    override fun onDisconnect() { Telecom.calls()?.let { if (it.ui.value.phase == CallPhase.INCOMING) it.decline() else it.hangup() } }
    override fun onAbort() { Telecom.calls()?.hangup() }
    // Held by the system (e.g. a cellular call answered): keep the Zura call; never end it from here.
    override fun onHold() = Unit

    /** Audio outputs the system offers for this call (Android 14+; see [Telecom.route]). */
    @Volatile var endpoints: List<android.telecom.CallEndpoint> = emptyList()
        private set
    override fun onAvailableCallEndpointsChanged(availableEndpoints: List<android.telecom.CallEndpoint>) { endpoints = availableEndpoints }
}

class ZuraConnectionService : ConnectionService() {
    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest?): Connection =
        build(request, incoming = true)

    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest?): Connection =
        build(request, incoming = false)

    private fun build(request: ConnectionRequest?, incoming: Boolean): Connection {
        val extras = if (incoming) request?.extras?.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS) ?: request?.extras
        else request?.extras?.getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS) ?: request?.extras
        val conn = ZuraConnection()
        conn.setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)
        conn.setCallerDisplayName(extras?.getString(Telecom.EXTRA_NAME) ?: getString(R.string.app_name), TelecomManager.PRESENTATION_ALLOWED)
        conn.videoState = if (extras?.getBoolean(Telecom.EXTRA_VIDEO) == true) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY
        if (incoming) conn.setRinging() else conn.setDialing()
        Telecom.attach(conn, extras?.getString(Telecom.EXTRA_CALL_ID))
        // Already answered / connected in Zura before the system caught up.
        Telecom.calls()?.ui?.value?.let { ui -> if (ui.phase == CallPhase.ACTIVE || ui.phase == CallPhase.CONNECTING) conn.setActive() }
        return conn
    }

    // The system refused (e.g. a cellular call is active, or the OEM blocks it): Zura's own ringing path carries on.
    override fun onCreateIncomingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        DiagLog.record("telecom", detail = "incoming connection refused by the system")
    }

    override fun onCreateOutgoingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        DiagLog.record("telecom", detail = "outgoing connection refused by the system")
    }
}
