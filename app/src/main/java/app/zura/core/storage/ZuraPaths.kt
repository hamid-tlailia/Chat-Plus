package app.zura.core.storage

import java.io.File

/**
 * Sandbox layout. Everything lives under the app's private `filesDir` / `noBackupFilesDir`,
 * invisible to other apps and excluded from cloud backup (see data_extraction_rules.xml).
 *
 *   files/zura/
 *     chats/<chatId>/
 *       chat.key          wrapped 256-bit chat key (Keystore-wrapped)
 *       meta.zm           encrypted ChatMeta snapshot (atomic rewrite)
 *       log.zl            append-only encrypted message log (1 sealed record per line)
 *       media/<id>.zv     segmented-AES-GCM media (each with its own key, stored in the log)
 *     chats/moments/      Moments reuse the chat engine (same crypto + purge), expired after 24 h
 *   no_backup/tmp/        plaintext scratch (recorder output, transcoder output) — wiped on boot
 *   cache/export/         short-lived decrypted copies granted to other apps — wiped on boot
 */
class ZuraPaths(filesDir: File, noBackupDir: File, cacheDir: File) {
    val root = File(filesDir, "zura")
    val chats = File(root, "chats")
    val tmp = File(noBackupDir, "tmp")
    val export = File(cacheDir, "export")

    init {
        listOf(chats, tmp, export).forEach { it.mkdirs() }
    }

    fun chatDir(chatId: String) = File(chats, safe(chatId))
    fun mediaDir(chatId: String) = File(chatDir(chatId), "media")
    // createTempFile requires a prefix of at least 3 characters (a 1-char prefix crashed voice recording).
    fun newTemp(ext: String): File = File.createTempFile("zura_", ".$ext", tmp)

    companion object {
        /** IDs come from the network: never let one escape its directory. */
        fun safe(id: String): String {
            require(id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
                "Illegal id"
            }
            return id
        }
    }
}
