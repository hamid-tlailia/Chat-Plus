package app.zura.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class MsgStatus { PENDING, SENT, DELIVERED, READ, FAILED }

@Serializable
enum class MsgKind { TEXT, IMAGE, VIDEO, VOICE, FILE, CALL }

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
)

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
    val unread: Int = 0,
    val pinned: Boolean = false,
    val avatarHue: Float = 0f,
    /** Storage path of the contact's avatar (avatars bucket). */
    val avatarPath: String? = null,
    /** The contact's E2EE identity key changed since it was first seen — shown as a security notice. */
    val keyChanged: Boolean = false,
)
