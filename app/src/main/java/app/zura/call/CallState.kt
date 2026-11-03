package app.zura.call

import androidx.compose.runtime.Immutable

enum class CallPhase { IDLE, OUTGOING, RINGING, INCOMING, CONNECTING, ACTIVE, RECONNECTING, ENDED }

/** Live link quality computed from WebRTC stats every 2 s. */
enum class NetQuality { GOOD, FAIR, POOR, UNKNOWN }

/** Device-level connectivity, for the "No internet / Wi-Fi / Mobile" chip. */
enum class NetType { WIFI, CELLULAR, OTHER, OFFLINE }

@Immutable
data class CallUi(
    val phase: CallPhase = CallPhase.IDLE,
    val callId: String? = null,
    val peerId: String? = null,
    val peerName: String = "",
    val peerAvatar: String? = null,
    val peerHue: Float = 0f,
    val outgoing: Boolean = true,
    val video: Boolean = false,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val cameraOn: Boolean = false,
    val frontCamera: Boolean = true,
    val remoteVideoOn: Boolean = false,
    val remoteMuted: Boolean = false,
    val connectedAt: Long? = null,
    val quality: NetQuality = NetQuality.UNKNOWN,
    val rttMs: Int? = null,
    val lossPct: Float? = null,
    val net: NetType = NetType.OTHER,
    val relayed: Boolean = false,
    /** hangup | declined | busy | timeout | failed | no_permission */
    val endReason: String? = null,
) {
    val inCall get() = phase != CallPhase.IDLE && phase != CallPhase.ENDED
}
