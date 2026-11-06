package app.zura.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class MsgStatus { PENDING, SENT, DELIVERED, READ, FAILED }

@Serializable
/** EVENT = a small system line in the chat ("took a screenshot"); its text is the event code. */
enum class MsgKind { TEXT, IMAGE, VIDEO, VOICE, FILE, CALL, EVENT }

/**
 * Pointer to an encrypted file inside a chat's media vault.
 *
 * [key] is the per-file data-encryption key (Base64). It lives ONLY inside the encrypted
 * message record, so erasing the record crypto-shreds the file even if flash blocks survive.
 * [preview] is a ~1–2 KB blurred JPEG (Base64) rendered instantly before the vault file is touched.
 * [waveform] is 48 amplitude buckets (0–255), Base64, for voice notes.
 */
@Immutable
@Serializable
data class MediaRef(
    val fileName: String,
    val key: String,
    val mime: String,
    val size: Long,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    val waveform: String? = null,
    val preview: String? = null,
    val originalName: String? = null,
    val remoteBlobId: String? = null,
) {
    /** Bubble shown instantly while the real file is still being compressed/encrypted. */
    val isPlaceholder: Boolean get() = key.isEmpty()
}

@Immutable
@Serializable
data class Message(
    val id: String,
    val chatId: String,
    val senderId: String,
    val sentAt: Long,
    val kind: MsgKind,
    val text: String? = null,
    val media: MediaRef? = null,
    val status: MsgStatus = MsgStatus.PENDING,
    val editedAt: Long? = null,
    /** userId -> emoji. One reaction per user, last write wins. */
    val reactions: Map<String, String> = emptyMap(),
    val replyTo: String? = null,
    /** Rich card for the first link in [text]; built on the sender's phone, travels E2EE. */
    val link: LinkPreview? = null,
    /** Disappearing: ms to live after sending, [TTL_AFTER_READ] = gone once read and the chat is left. */
    val ttlMs: Long? = null,
    /** This message reacts / replies to a moment (story): a small preview of it travels along. */
    val moment: MomentQuote? = null,
    /** My own moment: who has seen it (userId → when). Told end-to-end encrypted by each viewer. */
    val seenBy: Map<String, Long> = emptyMap(),
)

@Serializable
data class MomentQuote(val preview: String? = null, val caption: String? = null, val video: Boolean = false)

/** Message timer value meaning "disappears after it has been read". */
const val TTL_AFTER_READ = -1L

/** Title / site / small thumbnail of a shared link (image = base64 JPEG, a few KB). */
@Serializable
data class LinkPreview(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val site: String? = null,
    val image: String? = null,
)

@Immutable
@Serializable
data class ChatMeta(
    val id: String,
    val title: String,
    val peerIds: List<String>,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val preview: String? = null,
    val previewKind: MsgKind = MsgKind.TEXT,
    /** The last message is mine → shown as "You: …" in the chat list. */
    val previewMine: Boolean = false,
    /** Status of that last message when it's mine (sent / delivered / seen) — ticks or their photo in the list. */
    val previewStatus: MsgStatus? = null,
    val unread: Int = 0,
    val pinned: Boolean = false,
    val avatarHue: Float = 0f,
    /** Storage path of the contact's avatar (avatars bucket). */
    val avatarPath: String? = null,
    /** The contact's E2EE identity key changed since it was first seen — shown as a security notice. */
    val keyChanged: Boolean = false,
    /** The contact's last-seen time, received end-to-end encrypted (null = unknown / hidden). */
    val peerSeenAt: Long? = null,
    /** Disappearing-messages timer for new messages in this chat (null = off). */
    val disappearMs: Long? = null,
    /** Opens only after biometric / device-credential check; hidden from the main list. */
    val locked: Boolean = false,
)
