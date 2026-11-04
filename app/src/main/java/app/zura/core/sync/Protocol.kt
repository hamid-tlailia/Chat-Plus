package app.zura.core.sync

import app.zura.core.model.MediaRef
import app.zura.core.model.MsgKind
import app.zura.core.model.MsgStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire protocol: compact JSON frames over a single WebSocket, discriminated by "t".
 *
 * The relay is a dumb, stateless store-and-forward router: it holds a frame only until every
 * recipient ACKs it, then drops it. Media ciphertext is uploaded once (vault format, unchanged)
 * and fetched by recipients; the per-file key travels inside the [Frame.Msg] envelope.
 * Roadmap: wrap envelopes in an MLS / Signal double-ratchet session so the relay sees only
 * routing metadata (see docs/ARCHITECTURE.md §6).
 */
@Serializable
sealed interface Frame {
    @Serializable @SerialName("hello")
    data class Hello(val userId: String, val token: String, val since: Long) : Frame

    @Serializable @SerialName("msg")
    data class Msg(
        val id: String,
        val chatId: String,
        val from: String,
        val to: List<String>,
        val sentAt: Long,
        val kind: MsgKind,
        val text: String? = null,
        val media: MediaRef? = null,
        val replyTo: String? = null,
    ) : Frame

    /** Relay accepted & persisted the frame → sender shows a single tick. */
    @Serializable @SerialName("ack")
    data class Ack(val id: String, val seq: Long) : Frame

    /** Delivered / read receipts, batched per chat. */
    @Serializable @SerialName("rcpt")
    data class Receipt(val chatId: String, val from: String, val ids: List<String>, val status: MsgStatus) : Frame

    @Serializable @SerialName("typing")
    data class Typing(val chatId: String, val from: String, val active: Boolean, val recording: Boolean = false) : Frame

    @Serializable @SerialName("edit")
    data class Edit(val chatId: String, val from: String, val id: String, val text: String, val at: Long) : Frame

    /** Delete for everyone: each receiving device purges record + media locally. */
    @Serializable @SerialName("del")
    data class Delete(val chatId: String, val from: String, val ids: List<String>) : Frame

    @Serializable @SerialName("react")
    data class React(val chatId: String, val from: String, val id: String, val emoji: String?) : Frame

    /** "I blocked / unblocked you" — so the other side can show it instead of failing silently. */
    @Serializable @SerialName("block")
    data class Block(val from: String, val blocked: Boolean) : Frame

    @Serializable @SerialName("presence")
    data class Presence(val userId: String, val online: Boolean, val lastSeen: Long) : Frame

    @Serializable @SerialName("moment")
    data class MomentPost(val id: String, val from: String, val fromName: String, val createdAt: Long, val media: MediaRef, val caption: String? = null) : Frame

    @Serializable @SerialName("ping")
    data object Ping : Frame

    // ───────────── calls (ephemeral, E2EE over the peer's private realtime topic)

    /** Sent inside an FCM push to wake a sleeping phone; the full SDP offer follows over Realtime. */
    @Serializable @SerialName("call_invite")
    data class CallInvite(val callId: String, val from: String, val video: Boolean, val at: Long) : Frame

    @Serializable @SerialName("call_offer")
    data class CallOffer(val callId: String, val from: String, val video: Boolean, val sdp: String, val restart: Boolean = false) : Frame

    @Serializable @SerialName("call_answer")
    data class CallAnswer(val callId: String, val from: String, val sdp: String) : Frame

    @Serializable @SerialName("call_ice")
    data class CallIce(val callId: String, val from: String, val mid: String, val index: Int, val candidate: String) : Frame

    /** Callee's phone is ringing (so the caller can show "Ringing…" instead of "Calling…"). */
    @Serializable @SerialName("call_ringing")
    data class CallRinging(val callId: String, val from: String) : Frame

    /** Callee's network changed — asks the caller (who owns the offer) to restart ICE now. */
    @Serializable @SerialName("call_restart")
    data class CallRestartRequest(val callId: String, val from: String) : Frame

    /** reason: hangup | declined | busy | timeout | failed */
    @Serializable @SerialName("call_end")
    data class CallEnd(val callId: String, val from: String, val reason: String) : Frame

    @Serializable @SerialName("call_media")
    data class CallMedia(val callId: String, val from: String, val videoOn: Boolean, val muted: Boolean) : Frame
}

val WireJson = Json {
    classDiscriminator = "t"
    ignoreUnknownKeys = true
    explicitNulls = false
}
