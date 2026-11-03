package app.zura.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zura.AppGraph
import app.zura.core.crypto.SoftwareKeyWrapper
import app.zura.core.model.ChatMeta
import app.zura.core.model.Message
import app.zura.core.model.MsgKind
import app.zura.core.model.MsgStatus
import app.zura.data.MomentsRepository
import app.zura.graph
import app.zura.media.Previews
import app.zura.ui.chat.ChatScreen
import app.zura.ui.chats.ChatListScreen
import app.zura.ui.components.zuraBackground
import app.zura.ui.moments.MomentViewer
import app.zura.ui.settings.SettingsScreen
import app.zura.ui.theme.ZuraTheme
import app.zura.ui.theme.ZuraThemeId
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.sin

/** Renders the real screens with seeded demo data → PNGs under design/screenshots */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "w400dp-h860dp-xhdpi")
class DesignScreenshots {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = "../design/screenshots"
    private val min = 60_000L

    init { AppGraph.keyOverride = SoftwareKeyWrapper() }

    private fun seed(ar: Boolean) = runBlocking {
        val g = rule.activity.graph
        val me = g.settings.userId
        val now = System.currentTimeMillis()
        val v = g.vault
        g.chats.ensureNotesChat("Zura")

        fun t(en: String, arText: String) = if (ar) arText else en
        val sara = ChatMeta("sara01", t("Sara Benali", "سارة بن علي"), listOf("u_sara"), now - 9 * 86_400_000L, now - 60_000, "Merci 🙏", avatarHue = 320f)
        v.createChat(sara)
        listOf(
            ChatMeta("yassine", t("Yassine", "ياسين"), listOf("u_yas"), now - 5 * 86_400_000L, now - 14 * min, t("See you at 8 🙌", "نلتقي الساعة 8 🙌"), unread = 3, avatarHue = 200f),
            ChatMeta("family", t("Family", "العائلة"), listOf("u_mom", "u_dad"), now - 30 * 86_400_000L, now - 95 * min, null, MsgKind.IMAGE, unread = 12, avatarHue = 30f),
            ChatMeta("lina", t("Lina Haddad", "لينا حداد"), listOf("u_lina"), now - 40 * 86_400_000L, now - 26 * 60 * min, null, MsgKind.VOICE, avatarHue = 150f),
            ChatMeta("design", t("Design Team", "فريق التصميم"), listOf("u_a", "u_b"), now - 60 * 86_400_000L, now - 3 * 24 * 60 * min, t("Shipping the gold theme today ✨", "سنطلق السمة الذهبية اليوم ✨"), avatarHue = 260f),
            ChatMeta("omar", t("Omar", "عمر"), listOf("u_omar"), now - 90 * 86_400_000L, now - 6 * 24 * 60 * min, t("Thanks a lot!", "شكراً جزيلاً!"), avatarHue = 90f),
        ).forEach { v.createChat(it) }

        val photo = gradientJpeg(1200, 900)
        val img = v.importMedia("sara01", ByteArrayInputStream(photo.second), "image/jpeg", 1200, 900, preview = photo.first)
        val wave = Previews.encodeWaveform(List(80) { i -> (0.25f + 0.6f * kotlin.math.abs(sin(i * 0.37f) * sin(i * 0.11f + 1f))) })
        val voice = v.importMedia("sara01", ByteArrayInputStream(ByteArray(2000)), "audio/ogg", durationMs = 23_000, waveform = wave)
        val s = v.chat("sara01")
        val base = now - 3 * 60 * min
        fun msg(id: String, from: String, at: Long, text: String?, kind: MsgKind = MsgKind.TEXT, st: MsgStatus = MsgStatus.READ, reply: String? = null, react: Map<String, String> = emptyMap(), media: app.zura.core.model.MediaRef? = null, edited: Long? = null) =
            Message(id, "sara01", from, at, kind, text, media, st, edited, react, reply)
        s.upsertAll(listOf(
            msg("y1", "u_sara", now - 26 * 60 * min, t("Did you finish the slides?", "هل أنهيت الشرائح؟")),
            msg("y2", me, now - 26 * 60 * min + 30_000, t("Almost — sending tonight", "تقريباً — سأرسلها الليلة")),
            msg("m1", "u_sara", base, t("Good morning ☀️", "صباح الخير ☀️")),
            msg("m2", "u_sara", base + 20_000, t("Look at this view from the terrace", "انظر إلى هذا المنظر من الشرفة")),
            msg("m3", "u_sara", base + 40_000, null, MsgKind.IMAGE, media = img, react = mapOf(me to "❤️")),
            msg("m4", me, base + 5 * min, t("Wow, that light is unreal", "واو، هذا الضوء مذهل"), reply = "m3"),
            msg("m5", me, base + 5 * min + 15_000, t("Coffee there on Friday?", "قهوة هناك يوم الجمعة؟"), edited = base + 6 * min),
            msg("m6", "u_sara", base + 40 * min, null, MsgKind.VOICE, media = voice),
            msg("m7", "u_sara", now - 3 * min, t("Friday works. 10am?", "الجمعة مناسبة. العاشرة صباحاً؟"), react = mapOf(me to "👍", "u_x" to "👍")),
            msg("m8", me, now - 2 * min, t("Perfect, see you then", "ممتاز، أراك حينها"), st = MsgStatus.DELIVERED),
            msg("m9", me, now - 60_000, "Merci 🙏", st = MsgStatus.SENT),
        ))

        val mo = v.chat(MomentsRepository.STORE)
        listOf(
            Triple("u_sara", 330f, 2), Triple("u_yas", 200f, 1), Triple("u_lina", 150f, 1), Triple("u_omar", 90f, 1),
        ).forEachIndexed { i, (owner, hue, n) ->
            repeat(n) { k ->
                val ph = gradientJpeg(900, 1600, hue)
                val ref = v.importMedia(MomentsRepository.STORE, ByteArrayInputStream(ph.second), "image/jpeg", 900, 1600, preview = ph.first)
                mo.upsert(Message("mo$i$k", MomentsRepository.STORE, owner, now - (i + 1) * 40 * min + k, MsgKind.IMAGE,
                    if (k == 0 && i == 0) t("Sunrise run 🌅", "جري مع شروق الشمس 🌅") else null, ref,
                    if (i == 3) MsgStatus.READ else MsgStatus.DELIVERED))
            }
        }
    }

    /** Warm "sunset" gradient with soft sun disc, as a stand-in photo. */
    private fun gradientJpeg(w: Int, h: Int, hue: Float = 20f): Pair<String, ByteArray> {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val top = android.graphics.Color.HSVToColor(floatArrayOf((hue + 220) % 360, 0.55f, 0.35f))
        val mid = android.graphics.Color.HSVToColor(floatArrayOf((hue + 330) % 360, 0.55f, 0.75f))
        val bot = android.graphics.Color.HSVToColor(floatArrayOf(hue % 360, 0.65f, 0.95f))
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(top, mid, bot), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null; p.color = 0x55FFF3D6
        c.drawCircle(w * 0.62f, h * 0.62f, w * 0.16f, p)
        p.color = 0xFF1B1420.toInt()
        val path = android.graphics.Path().apply {
            moveTo(0f, h * 0.78f); lineTo(w * 0.2f, h * 0.68f); lineTo(w * 0.38f, h * 0.75f); lineTo(w * 0.6f, h * 0.64f)
            lineTo(w * 0.82f, h * 0.73f); lineTo(w.toFloat(), h * 0.66f); lineTo(w.toFloat(), h.toFloat()); lineTo(0f, h.toFloat()); close()
        }
        c.drawPath(path, p)
        val o = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 88, o)
        return Previews.encode(bmp) to o.toByteArray()
    }

    private fun shoot(name: String, ar: Boolean, theme: ZuraThemeId, after: () -> Unit = {}, manualClock: Boolean = false, screen: @androidx.compose.runtime.Composable () -> Unit) {
        if (ar) RuntimeEnvironment.setQualifiers("+ar-ldrtl")
        seed(ar)
        rule.activity.graph.settings.setTheme(theme)
        if (manualClock) rule.mainClock.autoAdvance = false
        rule.setContent { ZuraTheme(theme) { Box(Modifier.fillMaxSize().zuraBackground()) { screen() } } }
        if (manualClock) { repeat(8) { Thread.sleep(250); rule.mainClock.advanceTimeBy(250) } } else settle()
        after()
        if (!manualClock) settle()
        rule.onRoot().captureRoboImage("$out/$name.png")
    }

    private fun settle() {
        repeat(6) { Thread.sleep(250); rule.mainClock.advanceTimeBy(400); rule.waitForIdle() }
    }

    @Test fun s1_chats_midnight_en() = shoot("01_chats_midnight_en", false, ZuraThemeId.MIDNIGHT) { ChatListScreen({}, {}, {}) }
    @Test fun s2_chat_midnight_en() = shoot("02_chat_midnight_en", false, ZuraThemeId.MIDNIGHT) { ChatScreen("sara01", {}, {}) }
    @Test fun s3_actions_midnight_en() = shoot("03_actions_midnight_en", false, ZuraThemeId.MIDNIGHT, after = {
        rule.onNodeWithText("Wow, that light is unreal").performTouchInput { longClick() }
    }) { ChatScreen("sara01", {}, {}) }
    @Test fun s4_chats_velvet_ar() = shoot("04_chats_velvet_ar", true, ZuraThemeId.VELVET_GOLD) { ChatListScreen({}, {}, {}) }
    @Test fun s5_chat_velvet_ar() = shoot("05_chat_velvet_ar", true, ZuraThemeId.VELVET_GOLD) { ChatScreen("sara01", {}, {}) }
    @Test fun s6_chat_light_en() = shoot("06_chat_light_en", false, ZuraThemeId.LIGHT) { ChatScreen("sara01", {}, {}) }
    @Test fun s7_chat_oled_en() = shoot("07_chat_oled_en", false, ZuraThemeId.OLED) { ChatScreen("sara01", {}, {}) }
    @Test fun s8_settings_midnight_ar() = shoot("08_settings_midnight_ar", true, ZuraThemeId.MIDNIGHT) { SettingsScreen {} }
    @Test fun s9_moment_viewer() = shoot("09_moment_midnight_en", false, ZuraThemeId.MIDNIGHT, manualClock = true) { MomentViewer("u_sara") {} }
}
