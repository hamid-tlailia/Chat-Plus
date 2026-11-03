package app.zura.core.storage

import app.zura.core.crypto.KeyWrapper

/**
 * Runs once per cold start on a background thread (never blocks first frame). Restores the
 * invariant "no byte on disk without a live owner":
 *  1. wipe plaintext scratch (tmp/) and decrypted exports (cache/export) left by a crash
 *  2. delete media ciphertext no live message references (crash between purge steps)
 *  3. destroy Keystore KEKs that belong to no live chat generation
 */
class StorageJanitor(private val vault: VaultStore, private val wrapper: KeyWrapper) {

    data class Report(val bytesFreed: Long, val orphanFiles: Int, val orphanKeys: Int)

    fun sweep(): Report {
        val p = vault.paths
        var freed = SecureEraser.eraseTree(p.tmp) + SecureEraser.eraseTree(p.export)
        p.tmp.mkdirs(); p.export.mkdirs()

        var orphans = 0
        val liveAliases = HashSet<String>()
        val protectedPrefixes = HashSet<String>()
        for (id in vault.chatIds.value) {
            val store = runCatching { vault.chat(id) }.getOrNull()
            if (store == null) {
                // Never destroy keys of a chat we failed to read — that would be irreversible.
                protectedPrefixes += "${ChatStore.ALIAS_PREFIX}${id}_"
                continue
            }
            liveAliases += store.currentKeyAlias
            val live = store.liveMediaFiles()
            store.mediaDir.listFiles()?.forEach { f ->
                if (f.name !in live) { freed += SecureEraser.erase(f); orphans++ }
            }
        }

        var keys = 0
        wrapper.aliases().filter { a ->
            a.startsWith(ChatStore.ALIAS_PREFIX) && a !in liveAliases && protectedPrefixes.none(a::startsWith)
        }.forEach {
            wrapper.destroy(it); keys++
        }

        return Report(freed, orphans, keys)
    }
}
