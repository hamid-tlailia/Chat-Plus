package app.zura.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.zura.MainActivity
import app.zura.core.model.ChatMeta

/**
 * Publishes the most recent chats as sharing shortcuts, so they appear as one-tap targets in the
 * very first row of the Android sharesheet (Direct Share). Monogram icons are drawn in-process —
 * no avatar downloads, no image library.
 */
object DirectShare {
    const val CATEGORY = "app.zura.category.SHARE_TARGET"
    private const val MAX = 4

    fun publish(context: Context, chats: List<ChatMeta>) {
        val shortcuts = chats.asSequence().filter { it.peerIds.isNotEmpty() || it.pinned }.take(MAX).mapIndexed { rank, c ->
            ShortcutInfoCompat.Builder(context, c.id)
                .setShortLabel(c.title)
                .setLongLived(true)
                .setRank(rank)
                .setIcon(IconCompat.createWithAdaptiveBitmap(monogram(c.title, c.avatarHue)))
                .setPerson(Person.Builder().setName(c.title).setKey(c.id).build())
                .setCategories(setOf(CATEGORY))
                .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(MainActivity.EXTRA_CHAT, c.id))
                .build()
        }.toList()
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    private fun monogram(name: String, hue: Float): Bitmap {
        val size = 108 * 2
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.HSVToColor(floatArrayOf(hue % 360f, 0.45f, 0.7f)))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = size * 0.32f; textAlign = Paint.Align.CENTER }
        c.drawText(name.trim().take(1).uppercase(), size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
        return bmp
    }
}
