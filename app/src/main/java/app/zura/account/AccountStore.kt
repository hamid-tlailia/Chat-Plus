package app.zura.account

import app.zura.core.crypto.KeyWrapper
import app.zura.core.crypto.NamespacedKeyWrapper
import app.zura.core.crypto.SecureFile
import app.zura.core.storage.SecureEraser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Device-level registry of accounts used on this phone. Everything here is encrypted at rest
 * (hardware-backed keys); nothing is readable from a backup, a rooted file browser or another app.
 *
 *   files/zura/accounts.zs                     list of SavedAccount
 *   files/zura/accounts/<uid>/session.zs       Supabase refresh session (only if "remember me")
 *   files/zura/accounts/<uid>/avatar.zs        own avatar, for the account picker
 *   files/zura/accounts/<uid>/…                that account's chats, media, identity key
 */
class AccountStore(private val root: File, private val keys: KeyWrapper) {

    private val json = Json { ignoreUnknownKeys = true }
    private val index = SecureFile(File(root, "accounts.zs"), keys, "zacct_index")
    private val _accounts = MutableStateFlow(load())
    val accounts: StateFlow<List<SavedAccount>> = _accounts.asStateFlow()

    fun dir(uid: String) = File(File(root, "accounts"), uid.replace("-", "")).apply { mkdirs() }
    fun keysFor(uid: String) = NamespacedKeyWrapper(keys, "a${uid.replace("-", "").take(12)}_")

    fun session(uid: String) = SecureFile(File(dir(uid), "session.zs"), keysFor(uid), "zsess")
    fun avatar(uid: String) = SecureFile(File(dir(uid), "avatar.zs"), keysFor(uid), "zav")
    fun pendingAvatar(uid: String) = SecureFile(File(dir(uid), "avatar_pending.zs"), keysFor(uid), "zavp")

    fun get(uid: String) = _accounts.value.firstOrNull { it.uid == uid }

    @Synchronized
    fun upsert(a: SavedAccount) {
        save(listOf(a) + _accounts.value.filterNot { it.uid == a.uid })
    }

    /** Remove an account from this device: crypto-shred all of its keys and erase all of its files. */
    @Synchronized
    fun forget(uid: String) {
        keysFor(uid).destroyAll()
        SecureEraser.eraseTree(dir(uid))
        save(_accounts.value.filterNot { it.uid == uid })
    }

    private fun load(): List<SavedAccount> = index.readText()
        ?.let { runCatching { json.decodeFromString(ListSerializer(SavedAccount.serializer()), it) }.getOrNull() }
        ?.sortedByDescending { it.lastUsed }
        .orEmpty()

    private fun save(list: List<SavedAccount>) {
        val sorted = list.sortedByDescending { it.lastUsed }
        index.writeText(json.encodeToString(ListSerializer(SavedAccount.serializer()), sorted))
        _accounts.value = sorted
    }
}
