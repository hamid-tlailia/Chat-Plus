package app.zura.account

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class Profile(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_path") val avatarPath: String? = null,
    @SerialName("identity_key") val identityKey: String? = null,
    @SerialName("last_seen") val lastSeen: String? = null,
)

@Immutable
@Serializable
data class SearchHit(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_path") val avatarPath: String? = null,
    /** self | contact | pending_out | pending_in | none */
    val relation: String,
)

@Serializable
data class InvitationRow(
    val id: String,
    @SerialName("from_id") val fromId: String,
    @SerialName("to_id") val toId: String,
    val message: String? = null,
    val status: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class ContactRow(
    @SerialName("owner_id") val ownerId: String,
    @SerialName("peer_id") val peerId: String,
    val blocked: Boolean = false,
)

@Serializable
data class InboxRow(
    val id: Long,
    @SerialName("recipient_id") val recipientId: String,
    @SerialName("sender_id") val senderId: String,
    val payload: String,
)

@Immutable
data class Invite(val id: String, val incoming: Boolean, val profile: Profile, val message: String?, val createdAt: String)

@Immutable
data class Contact(val profile: Profile, val blocked: Boolean)

/**
 * An account that has been used on this device. Shown on the sign-in screen as avatar + name.
 * [remember] = a refresh session is stored (encrypted) so one tap signs in without a password.
 */
@Immutable
@Serializable
data class SavedAccount(
    val uid: String,
    val email: String,
    val username: String,
    val displayName: String,
    val lastUsed: Long,
    val remember: Boolean,
)
