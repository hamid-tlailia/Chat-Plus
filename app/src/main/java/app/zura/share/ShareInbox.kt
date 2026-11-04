package app.zura.share

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** A photo / video / file waiting in the composer for an optional caption before it is sent. */
data class Attachment(val uri: Uri, val mime: String, val name: String?, val temp: File? = null) {
    /** Photos (not GIFs) and videos are compressed and shown inline; everything else is a file. */
    val visual: Boolean get() = (mime.startsWith("image/") && mime != "image/gif") || mime.startsWith("video/")
    val video: Boolean get() = mime.startsWith("video/")
}

/**
 * Hand-off from the system share sheet to a chat: instead of sending at once, the shared items
 * (and any shared text) are placed in that chat's composer so the user can add a caption.
 */
object ShareInbox {
    data class Parcel(val attachments: List<Attachment>, val text: String?)

    private val _pending = MutableStateFlow<Map<String, Parcel>>(emptyMap())
    val pending: StateFlow<Map<String, Parcel>> = _pending.asStateFlow()

    fun put(chatId: String, attachments: List<Attachment>, text: String?) {
        _pending.value = _pending.value + (chatId to Parcel(attachments, text))
    }

    fun take(chatId: String): Parcel? {
        val p = _pending.value[chatId] ?: return null
        _pending.value = _pending.value - chatId
        return p
    }
}
