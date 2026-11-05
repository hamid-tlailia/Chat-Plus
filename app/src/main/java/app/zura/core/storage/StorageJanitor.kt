package app.zura.core.storage

import app.zura.core.crypto.KeyWrapper

/**
 * Runs once per cold start on a background thread (never blocks first frame). Restores the
 * invariant "no byte on disk without a live owner":
 *  1. wipe plaintext scratch (tmp/) and decrypted exports (cache/export) left by a crash
 *  2. delete media ciphertext no live message references (crash between purge steps)
 *  3. destroy Keystore KEKs of chats that no longer exist on disk
 *
 * It never touches the keys of a chat that still exists: a chat re-keys itself (and destroys its
 * own old key) — sweeping "old" keys from here raced a re-key in progress and destroyed the NEW
 * key, leaving that chat's history undecryptable. It also only checks media of chats already
 * open, instead of opening (decrypting) every chat at start-up.
 */
class StorageJanitor(private val vault: VaultStore, private val wrapper: KeyWrapper) {

    data class Report(val bytesFreed: Long, val orphanFiles: Int, val orphanKeys: Int)

    fun sweep(): Report {
        val p = vault.paths
        var freed = SecureEraser.eraseTree(p.tmp) + SecureEraser.eraseTree(p.export)
        p.tmp.mkdirs(); p.export.mkdirs()

        var orphans = 0
        for (store in vault.openStores()) {
            val live = store.liveMediaFiles()
            store.mediaDir.listFiles()?.forEach { f ->
                if (f.name !in live) { freed += SecureEraser.erase(f); orphans++ }
            }
        }

        var keys = 0
        wrapper.aliases().filter { a ->
            // Only chat-log keys ("zc_<chat>_<gen>") of chats whose folder is gone; the chat-list
            // index key must never be swept.
            if (!a.startsWith(ChatStore.ALIAS_PREFIX) || a.startsWith(VaultStore.INDEX_ALIAS)) return@filter false
            val chatId = a.removePrefix(ChatStore.ALIAS_PREFIX).substringBeforeLast('_', "")
            chatId.isNotEmpty() && !p.chatDir(chatId).exists()
        }.forEach {
            wrapper.destroy(it); keys++
        }

        return Report(freed, orphans, keys)
    }
}
