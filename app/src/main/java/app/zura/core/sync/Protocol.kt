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

    @Serializable @SerialName("presence")
    data class Presence(val userId: String, val online: Boolean, val lastSeen: Long) : Frame

    @Serializable @SerialName("moment")
    data class MomentPost(val id: String, val from: String, val fromName: String, val createdAt: Long, val media: MediaRef, val caption: String? = null) : Frame

    @Serializable @SerialName("ping")
    data object Ping : Frame
}

val WireJson = Json {
    classDiscriminator = "t"
    ignoreUnknownKeys = true
    explicitNulls = false
}
