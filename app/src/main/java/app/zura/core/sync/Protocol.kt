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
        val link: app.zura.core.model.LinkPreview? = null,
        val ttlMs: Long? = null,
        val moment: app.zura.core.model.MomentQuote? = null,
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

    /** Walkie-talkie: 250 ms of μ-law audio (base64), streamed live; [end] closes the burst. */
    @Serializable @SerialName("walkie")
    data class Walkie(val chatId: String, val from: String, val seq: Int, val audio: String, val end: Boolean = false) : Frame

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
    /** [at] = sender clock (0 from older versions): a non-restart offer older than ~45 s is stale. */
    data class CallOffer(val callId: String, val from: String, val video: Boolean, val sdp: String, val restart: Boolean = false, val at: Long = 0) : Frame

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
    data class CallMedia(val callId: String, val from: String, val videoOn: Boolean, val muted: Boolean, val screen: Boolean = false) : Frame

    /** Viewer → owner: "I saw your moment" (not sent while hiding read receipts from them). */
    @Serializable @SerialName("moment_seen")
    data class MomentSeen(val id: String, val from: String, val at: Long) : Frame

    /** Viewer → owner: an emoji on the moment (null = removed). Not a chat message: it shows by their name. */
    @Serializable @SerialName("moment_react")
    data class MomentReact(val id: String, val from: String, val emoji: String?, val at: Long) : Frame

    /** Owner → contacts: the moment was deleted — remove it. */
    @Serializable @SerialName("moment_del")
    data class MomentDelete(val id: String, val from: String) : Frame

    // ── Chat-history restore (a phone lost a chat's history: the contact sends their copy back,
    //    end-to-end encrypted like everything else, and only after they approve).

    /** "I lost this chat's history on my phone — please send me your copy." */
    @Serializable @SerialName("hist_req")
    data class HistoryRequest(val chatId: String, val from: String, val at: Long) : Frame

    /** One batch of the chat, oldest first; [last] closes the restore. */
    @Serializable @SerialName("hist")
    data class HistoryChunk(val chatId: String, val from: String, val index: Int, val last: Boolean, val messages: List<app.zura.core.model.Message>) : Frame

    /** Requester → provider: done (the provider deletes the media it re-uploaded). Provider → requester: [declined]. */
    @Serializable @SerialName("hist_done")
    data class HistoryDone(val chatId: String, val from: String, val declined: Boolean = false) : Frame
}

val WireJson = Json {
    classDiscriminator = "t"
    ignoreUnknownKeys = true
    explicitNulls = false
}
