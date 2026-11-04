package app.zura.call

import android.content.Context
import app.zura.core.sync.Frame
import app.zura.data.ChatRepository
import app.zura.data.ContactsRepository
import app.zura.net.Backend
import app.zura.net.SupabaseTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 1:1 voice & video calls over WebRTC (peer-to-peer, DTLS-SRTP encrypted media; signalling is
 * end-to-end encrypted over Supabase Realtime, so even the signalling server can't MITM the call).
 *
 * Reliability features (what makes calls feel "WhatsApp-solid"):
 *  • TURN relay credentials from our edge function (Cloudflare) → connects through strict NATs.
 *  • Continual ICE gathering + automatic ICE restart on DISCONNECTED/FAILED and on every network
 *    handover (Wi-Fi ⇄ mobile) — the call survives; UI shows "Reconnecting…" meanwhile.
 *  • Link quality from RTCStats every 2 s (RTT, packet loss) → Good/Fair/Poor indicator; on Poor
 *    video is stepped down (bitrate/resolution/fps), restored when the link recovers. Opus audio
 *    adapts on its own and is always prioritised.
 *  • Hardware AEC / NS / AGC, hardware H.264/VP8 codecs.
 */
class CallManager(
    private val context: Context,
    private val transport: SupabaseTransport,
    private val contacts: ContactsRepository,
    private val chats: ChatRepository,
    private val backend: Backend,
    private val me: String,
    private val scope: CoroutineScope,
) {
    private val rtc = Executors.newSingleThreadExecutor { Thread(it, "zura-call") }.asCoroutineDispatcher()
    private val audio = CallAudio(context)
    val network = NetworkMonitor(context)

    private val _ui = MutableStateFlow(CallUi())
    val ui: StateFlow<CallUi> = _ui.asStateFlow()

    private val _local = MutableStateFlow<VideoTrack?>(null)
    val localVideo: StateFlow<VideoTrack?> = _local.asStateFlow()
    private val _remote = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remote.asStateFlow()

    val egl: EglBase by lazy { EglBase.create() }
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSender: RtpSender? = null
    private var pendingOffer: Frame.CallOffer? = null
    private val pendingIce = ArrayList<IceCandidate>()
    private var remoteSet = false
    private var timers = ArrayList<Job>()
    private var reconnectJob: Job? = null
    private var statsJob: Job? = null
    private var degraded = false

    init {
        scope.launch { network.type.collect { t -> if (!previewing) _ui.update { it.copy(net = t) } } }
        scope.launch {
            network.handover.collect {
                val s = _ui.value
                if (s.phase == CallPhase.ACTIVE || s.phase == CallPhase.RECONNECTING || s.phase == CallPhase.CONNECTING) requestRestart()
            }
        }
    }

    // ================================================================= public API (UI)

    fun startCall(peerId: String, video: Boolean) = scope.launch(rtc) {
        if (_ui.value.inCall) return@launch
        val p = contacts.profile(peerId)
        // Blocked (either way): never ring. Re-check the server quickly — the block may be new.
        kotlinx.coroutines.withTimeoutOrNull(1_500) { contacts.refreshBlockedMe() }
        if (contacts.isBlocked(peerId)) {
            _ui.value = CallUi(
                phase = CallPhase.ENDED, peerId = peerId, peerName = p?.displayName ?: "", peerAvatar = p?.avatarPath,
                peerHue = (peerId.hashCode() and 0xFFFF) % 360f, outgoing = true, video = video, endReason = "blocked",
                net = network.type.value,
            )
            return@launch
        }
        val callId = UUID.randomUUID().toString()
        _ui.value = CallUi(
            phase = CallPhase.OUTGOING, callId = callId, peerId = peerId, peerName = p?.displayName ?: "",
            peerAvatar = p?.avatarPath, peerHue = (peerId.hashCode() and 0xFFFF) % 360f, outgoing = true,
            // The callee's camera starts ON in a video call — don't wait for a CallMedia frame to show
            // it (the remote video stayed hidden until the callee toggled their camera).
            video = video, cameraOn = video, speaker = video, remoteVideoOn = video, net = network.type.value,
        )
        CallService.start(context, _ui.value)
        audio.startCallMode(speaker = video)
        audio.startRingback()
        createPeer(video, outgoing = true)
        val offer = pc!!.awaitCreate(offer = true, iceRestart = false)
        pc!!.awaitSetLocal(offer)
        val frame = Frame.CallOffer(callId, me, video, offer.description)
        transport.signal(peerId, frame)
        // Their phone may be asleep / app closed: wake it with an E2EE-sealed invite over FCM,
        // and keep re-offering every 2.5 s until it rings (the woken app needs ~1–3 s to connect).
        scope.launch {
            transport.sealFor(peerId, Frame.CallInvite(callId, me, video, System.currentTimeMillis()))
                ?.let { backend.push(peerId, "call", mapOf("e" to it)) }
        }
        timers += scope.launch(rtc) {
            repeat(16) {
                delay(2_500)
                if (_ui.value.callId != callId || _ui.value.phase != CallPhase.OUTGOING) return@launch
                transport.signal(peerId, frame)
            }
        }
        // No answer within 45 s → missed call.
        timers += scope.launch(rtc) { delay(45_000); if (_ui.value.phase in setOf(CallPhase.OUTGOING, CallPhase.RINGING)) end("timeout", notify = true) }
    }

    fun accept() = scope.launch(rtc) {
        if (_ui.value.phase != CallPhase.INCOMING) return@launch
        // Tell the caller right away (a tiny frame, sent in parallel): their screen flips from
        // "Ringing" to "Connecting" instantly instead of waiting for TURN + camera + SDP answer.
        scope.launch(rtc) { announceMedia() }
        val offer = pendingOffer
        if (offer == null) {
            // Woken by push and the caller's offer hasn't arrived yet: answer as soon as it does.
            acceptWhenOffer = true
            audio.stopTones()
            _ui.update { it.copy(phase = CallPhase.CONNECTING) }
            CallService.start(context, _ui.value) // now with the microphone type (app is in front)
            return@launch
        }
        answer(offer)
    }

    private var acceptWhenOffer = false

    private suspend fun answer(offer: Frame.CallOffer) {
        acceptWhenOffer = false
        audio.stopTones()
        _ui.update { it.copy(phase = CallPhase.CONNECTING, speaker = offer.video) }
        CallService.start(context, _ui.value)
        audio.startCallMode(speaker = offer.video)
        createPeer(offer.video, outgoing = false)
        applyRemote(SessionDescription(SessionDescription.Type.OFFER, offer.sdp))
        if (!offer.video) {
            // Voice call: the caller offered an idle video slot — make it two-way so I can share my screen too.
            pc!!.transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }?.let {
                it.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                videoSender = it.sender
            }
        }
        val answer = pc!!.awaitCreate(offer = false, iceRestart = false)
        pc!!.awaitSetLocal(answer)
        transport.signal(offer.from, Frame.CallAnswer(offer.callId, me, answer.description))
        pendingOffer = null
    }

    // ================================================================= push (phone was asleep / app closed)

    /** An FCM call invite arrived: start ringing now, connect Realtime so the SDP offer can follow. */
    suspend fun onPushInvite(inv: Frame.CallInvite) = withContext(rtc) {
        val s = _ui.value
        if (s.callId == inv.callId) return@withContext
        if (s.inCall) { transport.signal(inv.from, Frame.CallEnd(inv.callId, me, "busy")); return@withContext }
        if (contacts.contacts.value.none { it.profile.id == inv.from }) runCatching { contacts.refresh() }
        val p = contacts.profile(inv.from) ?: return@withContext
        if (contacts.isBlocked(inv.from)) return@withContext // I blocked them: stay silent
        pendingOffer = null; acceptWhenOffer = false
        pendingIce.clear(); remoteSet = false
        _ui.value = CallUi(
            phase = CallPhase.INCOMING, callId = inv.callId, peerId = inv.from, peerName = p.displayName,
            peerAvatar = p.avatarPath, peerHue = (inv.from.hashCode() and 0xFFFF) % 360f, outgoing = false,
            video = inv.video, cameraOn = inv.video, speaker = inv.video, remoteVideoOn = inv.video, net = network.type.value,
        )
        CallService.start(context, _ui.value)
        if (app.zura.push.Push.appVisible) audio.startRingtone(inv.from) // in background the notification itself rings
        transport.start()
        transport.signal(inv.from, Frame.CallRinging(inv.callId, me))
        val id = inv.callId
        timers += scope.launch(rtc) { delay(45_000); if (_ui.value.callId == id && _ui.value.phase == CallPhase.INCOMING) end("timeout", notify = false) }
    }

    suspend fun onPushCancelled(callId: String, from: String) = withContext(rtc) {
        val s = _ui.value
        if (s.callId == callId && s.peerId == from && (s.phase == CallPhase.INCOMING || s.phase == CallPhase.CONNECTING)) end("hangup", notify = false)
    }

    fun decline() = scope.launch(rtc) { if (_ui.value.phase == CallPhase.INCOMING) end("declined", notify = true) }
    fun hangup() = scope.launch(rtc) { if (_ui.value.inCall) end("hangup", notify = true) }

    fun toggleMute() = scope.launch(rtc) {
        val muted = !_ui.value.muted
        audioTrack?.setEnabled(!muted)
        _ui.update { it.copy(muted = muted) }
        announceMedia()
    }

    fun toggleSpeaker() = scope.launch(rtc) {
        val on = !_ui.value.speaker
        audio.setSpeaker(on)
        audio.proximity(!on && !_ui.value.video && _ui.value.phase == CallPhase.ACTIVE)
        _ui.update { it.copy(speaker = on) }
    }

    fun toggleCamera() = scope.launch(rtc) {
        if (!_ui.value.video) return@launch
        val on = !_ui.value.cameraOn
        _local.value?.setEnabled(on)
        if (on) capturer?.startCapture(1280, 720, 30) else runCatching { capturer?.stopCapture() }
        _ui.update { it.copy(cameraOn = on) }
        announceMedia()
    }

    fun switchCamera() = scope.launch(rtc) {
        capturer?.switchCamera(null)
        _ui.update { it.copy(frontCamera = !it.frontCamera) }
    }

    @androidx.annotation.VisibleForTesting
    internal fun previewForTest(ui: CallUi) { previewing = true; _ui.value = ui }
    private var previewing = false

    /** After ENDED is shown briefly, return to idle so the UI can dismiss. */
    fun dismiss() = scope.launch(rtc) { if (_ui.value.phase == CallPhase.ENDED) _ui.value = CallUi(net = network.type.value) }

    // ================================================================= signalling (incoming)

    suspend fun handle(from: String, f: Frame) = withContext(rtc) {
        val s = _ui.value
        when (f) {
            is Frame.CallOffer -> {
                if (f.from != from || contacts.contacts.value.none { it.profile.id == from }) return@withContext
                if (contacts.isBlocked(from)) return@withContext // blocked: never ring, even if a signal slips through
                if (f.restart) {
                    if (s.callId == f.callId && pc != null) {
                        applyRemote(SessionDescription(SessionDescription.Type.OFFER, f.sdp))
                        val answer = pc!!.awaitCreate(offer = false, iceRestart = false)
                        pc!!.awaitSetLocal(answer)
                        transport.signal(from, Frame.CallAnswer(f.callId, me, answer.description))
                    }
                    return@withContext
                }
                // Same call announced earlier by push: attach the offer (and answer now if the user already tapped Answer).
                if (s.callId == f.callId) {
                    if (pendingOffer == null && (s.phase == CallPhase.INCOMING || s.phase == CallPhase.CONNECTING) && pc == null) {
                        pendingOffer = f
                        transport.signal(from, Frame.CallRinging(f.callId, me))
                        if (acceptWhenOffer) answer(f)
                    }
                    return@withContext
                }
                if (s.inCall) { transport.signal(from, Frame.CallEnd(f.callId, me, "busy")); return@withContext }
                val p = contacts.profile(from)
                pendingOffer = f
                pendingIce.clear(); remoteSet = false
                _ui.value = CallUi(
                    phase = CallPhase.INCOMING, callId = f.callId, peerId = from, peerName = p?.displayName ?: "",
                    peerAvatar = p?.avatarPath, peerHue = (from.hashCode() and 0xFFFF) % 360f, outgoing = false,
                    video = f.video, cameraOn = f.video, speaker = f.video, remoteVideoOn = f.video, net = network.type.value,
                )
                CallService.start(context, _ui.value)
                audio.startRingtone(from)
                transport.signal(from, Frame.CallRinging(f.callId, me))
                timers += scope.launch(rtc) { delay(45_000); if (_ui.value.phase == CallPhase.INCOMING) end("timeout", notify = false) }
            }
            is Frame.CallRinging -> if (f.callId == s.callId && s.phase == CallPhase.OUTGOING) _ui.update { it.copy(phase = CallPhase.RINGING) }
            is Frame.CallAnswer -> if (f.callId == s.callId && from == s.peerId) {
                audio.stopTones()
                if (s.phase == CallPhase.OUTGOING || s.phase == CallPhase.RINGING) _ui.update { it.copy(phase = CallPhase.CONNECTING) }
                applyRemote(SessionDescription(SessionDescription.Type.ANSWER, f.sdp))
            }
            is Frame.CallIce -> if (f.callId == s.callId && from == s.peerId) {
                val c = IceCandidate(f.mid, f.index, f.candidate)
                if (remoteSet) pc?.addIceCandidate(c) else pendingIce += c
            }
            is Frame.CallRestartRequest -> if (f.callId == s.callId && s.outgoing) restartIce()
            is Frame.CallMedia -> if (f.callId == s.callId && from == s.peerId) {
                _ui.update { it.copy(remoteScreen = f.screen) }
                // Sent by the callee the moment they tap Accept.
                if (s.phase == CallPhase.OUTGOING || s.phase == CallPhase.RINGING) {
                    audio.stopTones()
                    // Accepted but media never came up → don't sit on "Connecting" forever.
                    val id = s.callId
                    timers += scope.launch(rtc) { delay(30_000); if (_ui.value.callId == id && _ui.value.phase == CallPhase.CONNECTING) end("failed", notify = true) }
                }
                _ui.update {
                    it.copy(
                        remoteVideoOn = f.videoOn, remoteMuted = f.muted,
                        phase = if (it.phase == CallPhase.OUTGOING || it.phase == CallPhase.RINGING) CallPhase.CONNECTING else it.phase,
                    )
                }
            }
            is Frame.CallEnd -> if (f.callId == s.callId && from == s.peerId) end(f.reason, notify = false)
            else -> Unit
        }
    }

    // ================================================================= peer connection

    private suspend fun createPeer(video: Boolean, outgoing: Boolean) {
        val ice = iceServers()
        val config = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            iceCandidatePoolSize = 2
        }
        pc = factory.createPeerConnection(config, observer) ?: error("peer connection")
        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("a0", audioSource).also { pc!!.addTrack(it, listOf("s0")) }
        if (video) startCamera()
        else if (outgoing) {
            // An idle two-way video slot in voice calls: screen sharing can start later without renegotiation.
            videoSender = pc!!.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf("s0")),
            ).sender
        }
        remoteSet = false
    }

    private fun startCamera() {
        val enumerator = Camera2Enumerator(context)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: enumerator.deviceNames.firstOrNull() ?: return
        val cap = enumerator.createCapturer(name, null)
        val helper = SurfaceTextureHelper.create("zura-cam", egl.eglBaseContext)
        val src = factory.createVideoSource(false)
        cap.initialize(helper, context, src.capturerObserver)
        cap.startCapture(1280, 720, 30)
        val track = factory.createVideoTrack("v0", src)
        videoSender = pc!!.addTrack(track, listOf("s0"))
        videoSender?.let { s ->
            val p = s.parameters
            p.degradationPreference = RtpParameters.DegradationPreference.BALANCED
            p.encodings.firstOrNull()?.maxBitrateBps = 1_500_000
            s.parameters = p
        }
        capturer = cap; surfaceHelper = helper; videoSource = src
        _local.value = track
    }

    private suspend fun applyRemote(desc: SessionDescription) {
        pc?.awaitSetRemote(desc) ?: return
        remoteSet = true
        pendingIce.forEach { pc?.addIceCandidate(it) }
        pendingIce.clear()
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            val s = _ui.value
            val peer = s.peerId ?: return
            val id = s.callId ?: return
            scope.launch { transport.signal(peer, Frame.CallIce(id, me, c.sdpMid, c.sdpMLineIndex, c.sdp)) }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            scope.launch(rtc) {
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        reconnectJob?.cancel(); reconnectJob = null
                        audio.stopTones()
                        _ui.update { it.copy(phase = CallPhase.ACTIVE, connectedAt = it.connectedAt ?: System.currentTimeMillis()) }
                        audio.proximity(!_ui.value.video && !_ui.value.speaker)
                        CallService.start(context, _ui.value)
                        startStats()
                        // Tell the other side our real camera/mic state as soon as media flows.
                        announceMedia()
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED -> onLinkLost(immediate = false)
                    PeerConnection.PeerConnectionState.FAILED -> onLinkLost(immediate = true)
                    else -> Unit
                }
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            (transceiver.receiver.track() as? VideoTrack)?.let { _remote.value = it }
        }

        override fun onSignalingChange(s: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(b: Boolean) = Unit
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) { pc?.removeIceCandidates(c) }
        override fun onAddStream(s: MediaStream) = Unit
        override fun onRemoveStream(s: MediaStream) = Unit
        override fun onDataChannel(d: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    // ================================================================= resilience

    private fun onLinkLost(immediate: Boolean) {
        val s = _ui.value
        if (s.phase != CallPhase.ACTIVE && s.phase != CallPhase.CONNECTING && s.phase != CallPhase.RECONNECTING) return
        if (s.phase == CallPhase.ACTIVE) _ui.update { it.copy(phase = CallPhase.RECONNECTING, quality = NetQuality.POOR) }
        if (reconnectJob?.isActive == true && !immediate) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch(rtc) {
            if (!immediate) delay(1_500) // brief blips often self-heal without a restart
            var attempt = 0
            val deadline = System.currentTimeMillis() + 30_000
            while (isActive && System.currentTimeMillis() < deadline) {
                requestRestart()
                attempt++
                delay(minOf(2_000L * attempt, 6_000L))
                if (_ui.value.phase == CallPhase.ACTIVE) return@launch
            }
            if (_ui.value.phase == CallPhase.RECONNECTING || _ui.value.phase == CallPhase.CONNECTING) end("failed", notify = true)
        }
    }

    /** The caller owns the offer, so the caller restarts; the callee asks the caller to. */
    private fun requestRestart() = scope.launch(rtc) {
        val s = _ui.value
        if (s.outgoing) restartIce()
        else s.peerId?.let { transport.signal(it, Frame.CallRestartRequest(s.callId ?: return@launch, me)) }
    }

    private suspend fun restartIce() {
        val s = _ui.value
        val p = pc ?: return
        val peer = s.peerId ?: return
        val offer = runCatching { p.awaitCreate(offer = true, iceRestart = true) }.getOrNull() ?: return
        runCatching { p.awaitSetLocal(offer) }.getOrNull() ?: return
        transport.signal(peer, Frame.CallOffer(s.callId!!, me, s.video, offer.description, restart = true))
    }

    private var lastLost = 0L
    private var lastRecv = 0L
    private var goodStreak = 0
    private var poorStreak = 0

    private fun startStats() {
        if (statsJob?.isActive == true) return
        statsJob = scope.launch(rtc) {
            while (isActive) {
                delay(2_000)
                val p = pc ?: break
                val report = suspendCancellableCoroutine { k -> p.getStats { r -> k.resume(r) } }
                var rtt: Double? = null
                var lost = 0L
                var recv = 0L
                var relayed = false
                val stats = report.statsMap.values
                for (st in stats) {
                    val m = st.members
                    when (st.type) {
                        "candidate-pair" -> if (m["state"] == "succeeded" && m["nominated"] == true) {
                            rtt = (m["currentRoundTripTime"] as? Number)?.toDouble()
                            val local = stats.firstOrNull { it.id == m["localCandidateId"] }
                            relayed = local?.members?.get("candidateType") == "relay"
                        }
                        "inbound-rtp" -> if (m["kind"] == "audio") {
                            lost += (m["packetsLost"] as? Number)?.toLong() ?: 0
                            recv += (m["packetsReceived"] as? Number)?.toLong() ?: 0
                        }
                    }
                }
                val dLost = (lost - lastLost).coerceAtLeast(0)
                val dRecv = (recv - lastRecv).coerceAtLeast(0)
                lastLost = lost; lastRecv = recv
                val loss = if (dLost + dRecv > 0) dLost * 100f / (dLost + dRecv) else if (_ui.value.phase == CallPhase.ACTIVE) 100f else 0f
                val rttMs = rtt?.let { (it * 1000).toInt() }
                val q = when {
                    dRecv == 0L -> NetQuality.POOR
                    (rttMs ?: 0) < 300 && loss < 2f -> NetQuality.GOOD
                    (rttMs ?: 0) < 600 && loss < 8f -> NetQuality.FAIR
                    else -> NetQuality.POOR
                }
                _ui.update { it.copy(quality = q, rttMs = rttMs, lossPct = loss, relayed = relayed) }
                adaptVideo(q)
            }
        }
    }

    /** Step video down on a poor link (keeps audio clean), back up after 10 s of good link. */
    private fun adaptVideo(q: NetQuality) {
        val sender = videoSender ?: return
        if (q == NetQuality.POOR) { poorStreak++; goodStreak = 0 } else if (q == NetQuality.GOOD) { goodStreak++; poorStreak = 0 }
        val target = when {
            !degraded && poorStreak >= 2 -> true
            degraded && goodStreak >= 5 -> false
            else -> return
        }
        val params = sender.parameters
        params.encodings.firstOrNull()?.apply {
            maxBitrateBps = if (target) 300_000 else 1_500_000
            scaleResolutionDownBy = if (target) 2.0 else 1.0
            maxFramerate = if (target) 15 else 30
        }
        sender.parameters = params
        degraded = target
    }

    private suspend fun announceMedia() {
        val s = _ui.value
        val peer = s.peerId ?: return
        transport.signal(peer, Frame.CallMedia(s.callId ?: return, me, s.cameraOn && !s.sharingScreen && !bgPaused, s.muted, s.sharingScreen))
    }

    // ================================================================= teardown

    private suspend fun end(reason: String, notify: Boolean) {
        val s = _ui.value
        if (!s.inCall) return
        if (notify) s.peerId?.let { peer ->
            s.callId?.let { id ->
                transport.signal(peer, Frame.CallEnd(id, me, reason))
                // They may still be ringing from a push with no socket yet: stop it via push too.
                if (s.outgoing && s.connectedAt == null) scope.launch { backend.push(peer, "call_end", mapOf("c" to id)) }
            }
        }
        timers.forEach { it.cancel() }; timers.clear()
        reconnectJob?.cancel(); statsJob?.cancel()
        audio.stopTones()
        if (s.phase == CallPhase.ACTIVE || s.phase == CallPhase.RECONNECTING) audio.beepEnd()
        audio.stopCallMode()
        release()
        CallService.stop(context)
        // Call history: the caller writes one entry, mirrored E2EE to the callee's chat.
        if (s.outgoing && s.peerId != null) {
            val dur = s.connectedAt?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0
            val missed = s.connectedAt == null
            val summary = "${if (s.video) "video" else "voice"};dur=$dur;missed=${if (missed) 1 else 0};reason=$reason"
            scope.launch { runCatching { chats.logCall(ContactsRepository.dmChatId(me, s.peerId), summary) } }
        }
        _ui.value = s.copy(phase = CallPhase.ENDED, endReason = reason)
        scope.launch(rtc) { delay(1_600); if (_ui.value.phase == CallPhase.ENDED && _ui.value.callId == s.callId) _ui.value = CallUi(net = network.type.value) }
    }

    // ================================================================= app in background

    /** Camera paused because the user left the app (not because they turned it off). */
    @Volatile private var bgPaused = false

    /**
     * Left the app during a video call: audio keeps flowing (foreground service); the camera
     * pauses — the other side sees my avatar instead of a frozen frame — unless I'm sharing my screen.
     */
    fun onAppBackground() = scope.launch(rtc) {
        val s = _ui.value
        if (!s.inCall || !s.video || !s.cameraOn || s.sharingScreen || bgPaused) return@launch
        bgPaused = true
        runCatching { capturer?.stopCapture() }
        announceMedia()
    }

    /** Back in the app: camera on again and the other side's video comes back automatically. */
    fun onAppForeground() = scope.launch(rtc) {
        if (!bgPaused) return@launch
        bgPaused = false
        val s = _ui.value
        if (s.inCall && s.video && s.cameraOn && !s.sharingScreen) runCatching { capturer?.startCapture(1280, 720, 30) }
        announceMedia()
    }

    // ================================================================= screen sharing

    private var screenCapturer: org.webrtc.ScreenCapturerAndroid? = null
    private var screenHelper: SurfaceTextureHelper? = null
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null

    /** [permission] = the result Intent of the system "start recording / casting" consent screen. */
    fun startScreenShare(permission: android.content.Intent) = scope.launch(rtc) {
        val s = _ui.value
        val sender = videoSender ?: return@launch
        if (!s.inCall || s.sharingScreen) return@launch
        // Android 14: a mediaProjection foreground service must be running before capture starts.
        _ui.update { it.copy(sharingScreen = true) }
        CallService.projectionReady = false
        CallService.start(context, _ui.value)
        val ready = kotlinx.coroutines.withTimeoutOrNull(3_000) { while (!CallService.projectionReady) delay(50); true } == true
        if (!ready && android.os.Build.VERSION.SDK_INT >= 34) { _ui.update { it.copy(sharingScreen = false) }; return@launch }
        runCatching {
            runCatching { capturer?.stopCapture() } // camera rests while the screen is shown
            val dm = context.resources.displayMetrics
            val scale = 1280f / maxOf(dm.widthPixels, dm.heightPixels)
            val w = (dm.widthPixels * minOf(1f, scale)).toInt() / 2 * 2
            val h = (dm.heightPixels * minOf(1f, scale)).toInt() / 2 * 2
            val cap = org.webrtc.ScreenCapturerAndroid(permission, object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() { stopScreenShare() }
            })
            val helper = SurfaceTextureHelper.create("zura-screen", egl.eglBaseContext)
            val src = factory.createVideoSource(true)
            cap.initialize(helper, context, src.capturerObserver)
            cap.startCapture(w, h, 15)
            val track = factory.createVideoTrack("screen0", src)
            sender.setTrack(track, false)
            sender.parameters = sender.parameters.apply {
                degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
                encodings.firstOrNull()?.maxBitrateBps = 1_200_000
            }
            screenCapturer = cap; screenHelper = helper; screenSource = src; screenTrack = track
            announceMedia()
        }.onFailure {
            app.zura.DiagLog.record("screen share", it)
            stopScreenShareNow()
        }
    }

    fun stopScreenShare() = scope.launch(rtc) { stopScreenShareNow() }

    private suspend fun stopScreenShareNow() {
        disposeScreen()
        val s = _ui.value
        // Back to the camera (video call with camera on) or to an idle slot.
        videoSender?.setTrack(if (s.video) _local.value else null, false)
        videoSender?.let { sd ->
            sd.parameters = sd.parameters.apply {
                degradationPreference = RtpParameters.DegradationPreference.BALANCED
                encodings.firstOrNull()?.maxBitrateBps = 1_500_000
            }
        }
        if (s.video && s.cameraOn) runCatching { capturer?.startCapture(1280, 720, 30) }
        _ui.update { it.copy(sharingScreen = false) }
        if (s.inCall) { CallService.start(context, _ui.value); announceMedia() }
    }

    private fun disposeScreen() {
        runCatching { screenCapturer?.stopCapture() }
        screenCapturer?.dispose(); screenCapturer = null
        screenTrack?.dispose(); screenTrack = null
        screenSource?.dispose(); screenSource = null
        screenHelper?.dispose(); screenHelper = null
    }

    private fun release() {
        bgPaused = false
        disposeScreen()
        runCatching { capturer?.stopCapture() }
        capturer?.dispose(); capturer = null
        surfaceHelper?.dispose(); surfaceHelper = null
        _local.value = null; _remote.value = null
        videoSource?.dispose(); videoSource = null
        audioTrack = null
        audioSource?.dispose(); audioSource = null
        pc?.close(); pc?.dispose(); pc = null
        videoSender = null
        pendingOffer = null; pendingIce.clear(); remoteSet = false; acceptWhenOffer = false
        degraded = false; goodStreak = 0; poorStreak = 0; lastLost = 0; lastRecv = 0
    }

    // ================================================================= ICE servers (TURN)

    private suspend fun iceServers(): List<PeerConnection.IceServer> {
        val fallback = listOf(PeerConnection.IceServer.builder(listOf("stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302")).createIceServer())
        val res = withTimeoutOrNull(3_000) { runCatching { backend.function("turn-credentials") }.getOrNull() } ?: return fallback
        return runCatching {
            res.jsonObject["iceServers"]!!.jsonArray.map { e ->
                val o = e.jsonObject
                val urls = when (val u = o["urls"]) {
                    is JsonArray -> u.map { it.jsonPrimitive.content }
                    is JsonPrimitive -> listOf(u.content)
                    else -> emptyList()
                }
                PeerConnection.IceServer.builder(urls).apply {
                    (o["username"] as? JsonPrimitive)?.content?.let(::setUsername)
                    (o["credential"] as? JsonPrimitive)?.content?.let(::setPassword)
                }.createIceServer()
            }.ifEmpty { fallback }
        }.getOrDefault(fallback)
    }
}

// ===================================================================== coroutine adapters for SDP

private suspend fun PeerConnection.awaitCreate(offer: Boolean, iceRestart: Boolean): SessionDescription =
    suspendCancellableCoroutine { k ->
        val c = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true")
            if (iceRestart) mandatory += MediaConstraints.KeyValuePair("IceRestart", "true")
        }
        val obs = object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) = k.resume(d)
            override fun onCreateFailure(e: String?) = k.resumeWithException(IllegalStateException(e))
            override fun onSetSuccess() = Unit
            override fun onSetFailure(e: String?) = Unit
        }
        if (offer) createOffer(obs, c) else createAnswer(obs, c)
    }

private suspend fun PeerConnection.awaitSetLocal(d: SessionDescription) = suspendCancellableCoroutine { k ->
    setLocalDescription(setObserver(k), d)
}

private suspend fun PeerConnection.awaitSetRemote(d: SessionDescription) = suspendCancellableCoroutine { k ->
    setRemoteDescription(setObserver(k), d)
}

private fun setObserver(k: kotlinx.coroutines.CancellableContinuation<Unit>) = object : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription) = Unit
    override fun onCreateFailure(e: String?) = Unit
    override fun onSetSuccess() { if (k.isActive) k.resume(Unit) }
    override fun onSetFailure(e: String?) { if (k.isActive) k.resumeWithException(IllegalStateException(e)) }
}

