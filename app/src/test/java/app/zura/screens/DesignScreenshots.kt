package app.zura.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zura.AppGraph
import app.zura.account.AccountSession
import app.zura.account.Contact
import app.zura.account.Invite
import app.zura.account.Profile
import app.zura.account.SavedAccount
import app.zura.call.CallPhase
import app.zura.call.CallUi
import app.zura.call.NetQuality
import app.zura.call.NetType
import app.zura.core.crypto.SoftwareKeyWrapper
import app.zura.core.model.Message
import app.zura.core.model.MsgKind
import app.zura.core.model.MsgStatus
import app.zura.data.ContactsRepository
import app.zura.data.MomentsRepository
import app.zura.graph
import app.zura.media.Previews
import app.zura.ui.auth.AuthScreen
import app.zura.ui.call.CallScreen
import app.zura.ui.chat.ChatScreen
import app.zura.ui.chats.ChatListScreen
import app.zura.ui.components.zuraBackground
import app.zura.ui.contacts.PeopleScreen
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
import kotlin.math.abs
import kotlin.math.sin

/** Renders the real screens with seeded demo data → PNGs under design/screenshots. No network. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "w400dp-h860dp-xhdpi", application = TestZuraApp::class)
class DesignScreenshots {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = "../design/screenshots"
    private val min = 60_000L

    private val ME = "a1b2c3d4-0000-4000-8000-000000000001"
    private val SARA = "5a7a0000-0000-4000-8000-000000000002"
    private val YAS = "7a550000-0000-4000-8000-000000000003"
    private val LINA = "11aa0000-0000-4000-8000-000000000004"
    private val OMAR = "0aa70000-0000-4000-8000-000000000005"
    private val NOUR = "a0a00000-0000-4000-8000-000000000006"
    private val KARIM = "ca410000-0000-4000-8000-000000000007"

    init { AppGraph.keyOverride = SoftwareKeyWrapper() }

    private fun t(ar: Boolean, en: String, arText: String) = if (ar) arText else en

    private fun profiles(ar: Boolean) = mapOf(
        ME to Profile(ME, "hamid", t(ar, "Hamid", "حامد"), "av/me"),
        SARA to Profile(SARA, "sara.b", t(ar, "Sara Benali", "سارة بن علي"), "av/sara"),
        YAS to Profile(YAS, "yassine", t(ar, "Yassine", "ياسين"), "av/yas"),
        LINA to Profile(LINA, "lina_h", t(ar, "Lina Haddad", "لينا حداد"), "av/lina"),
        OMAR to Profile(OMAR, "omar", t(ar, "Omar", "عمر"), null),
        NOUR to Profile(NOUR, "nour", t(ar, "Nour El Houda", "نور الهدى"), "av/nour"),
        KARIM to Profile(KARIM, "karim.dev", t(ar, "Karim", "كريم"), null),
    )

    /** Builds a signed-in session with contacts, invitations, chats and moments — all local. */
    private fun signIn(ar: Boolean): AccountSession = runBlocking {
        val g = rule.activity.graph
        val p = profiles(ar)
        listOf("me" to 30f, "sara" to 330f, "yas" to 200f, "lina" to 150f, "nour" to 280f).forEach { (k, hue) ->
            g.avatars.putForTest("av/$k", portrait(hue).asImageBitmap())
        }
        val s = AccountSession(rule.activity.application, ME, "hamid@example.com", g.backend, g.accountStore)
        s.transport.setOnlineForTest(true)
        s.contacts.seedForTest(
            p[ME]!!,
            listOf(SARA, YAS, LINA, OMAR).map { Contact(p[it]!!, false) },
            listOf(
                Invite("i1", true, p[NOUR]!!, t(ar, "Hi! We met at the design meetup 🙂", "مرحباً! التقينا في لقاء التصميم 🙂"), "2026-11-03T10:00:00Z"),
                Invite("i2", true, p[KARIM]!!, null, "2026-11-02T10:00:00Z"),
            ),
        )
        s.chats.ensureNotesChat(t(ar, "Saved notes", "الملاحظات المحفوظة"))
        val now = System.currentTimeMillis()
        val v = s.vault
        fun meta(peer: String, upd: Long, preview: String?, kind: MsgKind = MsgKind.TEXT, unread: Int = 0) = runBlocking {
            val st = v.chat(ContactsRepository.dmChatId(ME, peer))
            st.writeMeta(st.meta.value!!.copy(updatedAt = upd, preview = preview, previewKind = kind, unread = unread))
        }
        meta(YAS, now - 14 * min, t(ar, "See you at 8 🙌", "نلتقي الساعة 8 🙌"), unread = 3)
        meta(LINA, now - 26 * 60 * min, null, MsgKind.VOICE)
        meta(OMAR, now - 3 * 24 * 60 * min, "video;dur=0;missed=1;reason=timeout", MsgKind.CALL)

        val chat = ContactsRepository.dmChatId(ME, SARA)
        val photo = gradientJpeg(1200, 900)
        val img = v.importMedia(chat, ByteArrayInputStream(photo.second), "image/jpeg", 1200, 900, preview = photo.first)
        val wave = Previews.encodeWaveform(List(80) { i -> 0.25f + 0.6f * abs(sin(i * 0.37f) * sin(i * 0.11f + 1f)) })
        val voice = v.importMedia(chat, ByteArrayInputStream(ByteArray(2000)), "audio/ogg", durationMs = 23_000, waveform = wave)
        val base = now - 3 * 60 * min
        fun msg(id: String, from: String, at: Long, text: String?, kind: MsgKind = MsgKind.TEXT, st: MsgStatus = MsgStatus.READ,
                reply: String? = null, react: Map<String, String> = emptyMap(), media: app.zura.core.model.MediaRef? = null, edited: Long? = null) =
            Message(id, chat, from, at, kind, text, media, st, edited, react, reply)
        val store = v.chat(chat)
        store.upsertAll(listOf(
            msg("m1", SARA, base, t(ar, "Good morning ☀️", "صباح الخير ☀️")),
            msg("m2", SARA, base + 20_000, t(ar, "Look at this view from the terrace", "انظر إلى هذا المنظر من الشرفة")),
            msg("m3", SARA, base + 40_000, null, MsgKind.IMAGE, media = img, react = mapOf(ME to "❤️")),
            msg("m4", ME, base + 5 * min, t(ar, "Wow, that light is unreal", "واو، هذا الضوء مذهل"), reply = "m3"),
            msg("m5", ME, base + 5 * min + 15_000, t(ar, "Coffee there on Friday?", "قهوة هناك يوم الجمعة؟"), edited = base + 6 * min),
            msg("c1", ME, base + 20 * min, "voice;dur=151;missed=0;reason=hangup", MsgKind.CALL),
            msg("m6", SARA, base + 40 * min, null, MsgKind.VOICE, media = voice),
            msg("m7", SARA, now - 3 * min, t(ar, "Friday works. 10am?", "الجمعة مناسبة. العاشرة صباحاً؟"), react = mapOf(ME to "👍", SARA to "👍")),
            msg("m8", ME, now - 2 * min, t(ar, "Perfect, see you then", "ممتاز، أراك حينها"), st = MsgStatus.DELIVERED),
            msg("m9", ME, now - 60_000, "Merci 🙏", st = MsgStatus.SENT),
        ))
        store.writeMeta(store.meta.value!!.copy(updatedAt = now - 60_000, preview = "Merci 🙏"))

        val mo = v.chat(MomentsRepository.STORE)
        if (mo.meta.value == null) mo.writeMeta(app.zura.core.model.ChatMeta(MomentsRepository.STORE, MomentsRepository.STORE, emptyList(), now))
        listOf(Triple(SARA, 330f, 2), Triple(YAS, 200f, 1), Triple(LINA, 150f, 1), Triple(OMAR, 90f, 1)).forEachIndexed { i, (owner, hue, n) ->
            repeat(n) { k ->
                val ph = gradientJpeg(900, 1600, hue)
                val ref = v.importMedia(MomentsRepository.STORE, ByteArrayInputStream(ph.second), "image/jpeg", 900, 1600, preview = ph.first)
                mo.upsert(Message("mo$i$k", MomentsRepository.STORE, owner, now - (i + 1) * 40 * min + k, MsgKind.IMAGE,
                    if (k == 0 && i == 0) t(ar, "Sunrise run 🌅", "جري مع شروق الشمس 🌅") else null, ref,
                    if (i == 3) MsgStatus.READ else MsgStatus.DELIVERED))
            }
        }
        g.accounts.signInForTest(s)
        s
    }

    private fun savedAccounts() {
        val g = rule.activity.graph
        g.accountStore.upsert(SavedAccount(ME, "hamid@example.com", "hamid", "Hamid", System.currentTimeMillis(), remember = true))
        g.accountStore.upsert(SavedAccount(SARA, "sara@example.com", "sara.b", "Sara Benali", System.currentTimeMillis() - 86_400_000, remember = false))
        g.accountStore.avatar(ME).write(jpeg(portrait(30f)))
        g.accountStore.avatar(SARA).write(jpeg(portrait(330f)))
    }

    // ───────────────────────────────────────────── fake imagery

    private fun portrait(hue: Float): Bitmap {
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 256f, 256f,
            android.graphics.Color.HSVToColor(floatArrayOf(hue % 360, 0.45f, 0.9f)),
            android.graphics.Color.HSVToColor(floatArrayOf((hue + 40) % 360, 0.6f, 0.5f)), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 256f, 256f, p)
        p.shader = RadialGradient(128f, 100f, 60f, 0xFFF3D9C6.toInt(), 0xFFD9A98B.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(128f, 104f, 52f, p)                       // head
        p.shader = null; p.color = android.graphics.Color.HSVToColor(floatArrayOf((hue + 180) % 360, 0.35f, 0.35f))
        c.drawOval(48f, 170f, 208f, 330f, p)                   // shoulders
        return bmp
    }

    private fun jpeg(b: Bitmap) = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()

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
        return Previews.encode(bmp) to jpeg(bmp)
    }

    // ───────────────────────────────────────────── harness

    private fun shoot(
        name: String, ar: Boolean, theme: ZuraThemeId, signedIn: Boolean = true, after: () -> Unit = {},
        manualClock: Boolean = false, setup: (AccountSession?) -> Unit = {}, screen: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        if (ar) RuntimeEnvironment.setQualifiers("+ar-ldrtl")
        val s = if (signedIn) signIn(ar) else { savedAccounts(); null }
        setup(s)
        rule.activity.graph.settings.setTheme(theme)
        if (manualClock) rule.mainClock.autoAdvance = false
        rule.setContent { ZuraTheme(theme) { Box(Modifier.fillMaxSize().zuraBackground()) { screen() } } }
        if (manualClock) repeat(8) { Thread.sleep(250); rule.mainClock.advanceTimeBy(250) } else settle()
        after()
        if (!manualClock) settle()
        rule.onRoot().captureRoboImage("$out/$name.png")
    }

    private fun settle() = repeat(6) { Thread.sleep(250); rule.mainClock.advanceTimeBy(400); rule.waitForIdle() }

    private val sara get() = ContactsRepository.dmChatId(ME, SARA)

    private fun callUi(phase: CallPhase, video: Boolean, ar: Boolean, quality: NetQuality = NetQuality.GOOD, net: NetType = NetType.WIFI) = CallUi(
        phase = phase, callId = "c", peerId = SARA, peerName = t(ar, "Sara Benali", "سارة بن علي"), peerAvatar = "av/sara", peerHue = 330f,
        outgoing = phase != CallPhase.INCOMING, video = video, cameraOn = video, speaker = video,
        connectedAt = if (phase == CallPhase.ACTIVE || phase == CallPhase.RECONNECTING) System.currentTimeMillis() - 151_000 else null,
        quality = quality, rttMs = if (quality == NetQuality.POOR) 640 else 48, lossPct = 0.4f, net = net,
    )

    /** A reaction arriving while the chat is open must show at once (it used to need an app restart). */
    @Test fun liveReactionShows() {
        val s = signIn(false)
        rule.setContent { ZuraTheme(ZuraThemeId.MIDNIGHT) { Box(Modifier.fillMaxSize().zuraBackground()) { ChatScreen(sara, {}, {}) } } }
        settle()
        val store = s.vault.chat(sara)
        runBlocking { store.upsert(store.get("m8")!!.let { it.copy(reactions = it.reactions + (SARA to "🦄")) }) }
        settle()
        rule.onNodeWithText("🦄", substring = true).assertExists()
        runBlocking { s.chats.react(sara, "m9", "🐙") }
        settle()
        rule.onNodeWithText("🐙", substring = true).assertExists()
    }

    @Test fun s01_chats_midnight_en() = shoot("01_chats_midnight_en", false, ZuraThemeId.MIDNIGHT) { ChatListScreen({}, {}, {}, {}) }
    @Test fun s02_chat_midnight_en() = shoot("02_chat_midnight_en", false, ZuraThemeId.MIDNIGHT) { ChatScreen(sara, {}, {}) }
    @Test fun s03_actions_midnight_en() = shoot("03_actions_midnight_en", false, ZuraThemeId.MIDNIGHT, after = {
        rule.onNodeWithText("Wow, that light is unreal").performTouchInput { longClick() }
    }) { ChatScreen(sara, {}, {}) }
    @Test fun s04_chats_velvet_ar() = shoot("04_chats_velvet_ar", true, ZuraThemeId.VELVET_GOLD) { ChatListScreen({}, {}, {}, {}) }
    @Test fun s05_chat_velvet_ar() = shoot("05_chat_velvet_ar", true, ZuraThemeId.VELVET_GOLD) { ChatScreen(sara, {}, {}) }
    @Test fun s06_chat_light_en() = shoot("06_chat_light_en", false, ZuraThemeId.LIGHT) { ChatScreen(sara, {}, {}) }
    @Test fun s07_chat_oled_en() = shoot("07_chat_oled_en", false, ZuraThemeId.OLED) { ChatScreen(sara, {}, {}) }
    @Test fun s08_settings_midnight_ar() = shoot("08_settings_midnight_ar", true, ZuraThemeId.MIDNIGHT) { SettingsScreen(onBack = {}) }
    @Test fun s09_moment_viewer() = shoot("09_moment_midnight_en", false, ZuraThemeId.MIDNIGHT, manualClock = true) { MomentViewer(SARA) {} }

    @Test fun s10_auth_accounts() = shoot("10_auth_accounts_midnight_en", false, ZuraThemeId.MIDNIGHT, signedIn = false) { AuthScreen() }
    @Test fun s11_auth_password_ar() = shoot("11_auth_password_velvet_ar", true, ZuraThemeId.VELVET_GOLD, signedIn = false) { AuthScreen(previewLockedUid = SARA) }
    @Test fun s12_register() = shoot("12_register_midnight_en", false, ZuraThemeId.MIDNIGHT, signedIn = false) { AuthScreen(previewRegister = true) }
    @Test fun s13_invites() = shoot("13_invitations_midnight_en", false, ZuraThemeId.MIDNIGHT) { PeopleScreen({}, {}, startOnInvites = true) }
    @Test fun s14_invites_ar() = shoot("14_contacts_light_ar", true, ZuraThemeId.LIGHT) { PeopleScreen({}, {}) }

    @Test fun s15_call_incoming() = shoot("15_call_incoming_video_en", false, ZuraThemeId.MIDNIGHT, manualClock = true,
        setup = { it!!.calls.previewForTest(callUi(CallPhase.INCOMING, video = true, ar = false)) }) { CallScreen(rule.activity.graph.s.calls) }
    @Test fun s16_call_active_ar() = shoot("16_call_active_voice_ar", true, ZuraThemeId.MIDNIGHT, manualClock = true,
        setup = { it!!.calls.previewForTest(callUi(CallPhase.ACTIVE, video = false, ar = true, net = NetType.CELLULAR)) }) { CallScreen(rule.activity.graph.s.calls) }
    @Test fun s18_lock_ar() = shoot("18_app_lock_velvet_ar", true, ZuraThemeId.VELVET_GOLD, setup = {
        org.robolectric.Shadows.shadowOf(rule.activity.getSystemService(android.app.KeyguardManager::class.java)).setIsDeviceSecure(true)
        rule.activity.graph.appLock.setEnabled(true); rule.activity.graph.appLock.lockNow()
    }) { app.zura.security.LockScreen(rule.activity.graph.appLock) }

    @Test fun s19_reply_photo_ocean() = shoot("19_reply_photo_ocean_en", false, ZuraThemeId.MIDNIGHT,
        setup = { rule.activity.graph.settings.setBubbles(app.zura.ui.theme.BubbleStyle.OCEAN) },
        after = {
            rule.runOnUiThread {
                val vm = androidx.lifecycle.ViewModelProvider(rule.activity.viewModelStore, androidx.lifecycle.ViewModelProvider.NewInstanceFactory())["${System.identityHashCode(rule.activity.graph.s)}:$sara", app.zura.ui.chat.ChatViewModel::class.java]
                vm.replyTo = rule.activity.graph.s.vault.chat(sara).get("m3")
            }
        }) {
        val b by rule.activity.graph.settings.bubbles.collectAsStateWithLifecycle()
        ZuraTheme(ZuraThemeId.MIDNIGHT, b) { ChatScreen(sara, {}, {}) }
    }

    @Test fun s20_bubbles_rose_light() = shoot("20_bubbles_rose_light_en", false, ZuraThemeId.LIGHT) {
        ZuraTheme(ZuraThemeId.LIGHT, app.zura.ui.theme.BubbleStyle.ROSE) { ChatScreen(sara, {}, {}) }
    }

    /** Mid-transition frames: settings opening / closing, English and Arabic. */
    private fun transition(name: String, ar: Boolean, open: Boolean) {
        if (ar) RuntimeEnvironment.setQualifiers("+ar-ldrtl")
        signIn(ar)
        val nav = app.zura.Navigator(app.zura.Route.Chats).apply { if (!open) push(app.zura.Route.Settings) }
        rule.mainClock.autoAdvance = false
        rule.setContent { ZuraTheme(ZuraThemeId.MIDNIGHT) { Box(Modifier.fillMaxSize().zuraBackground()) { app.zura.NavHost(nav) } } }
        repeat(6) { Thread.sleep(200); rule.mainClock.advanceTimeBy(400) }
        rule.runOnUiThread {
            if (open) nav.push(app.zura.Route.Settings) else nav.pop()
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        rule.mainClock.advanceTimeBy(90)
        rule.onRoot().captureRoboImage("$out/$name.png")
    }
    @Test fun t1_open_en() = transition("t1_settings_open_en", false, true)
    @Test fun t2_close_en() = transition("t2_settings_close_en", false, false)
    @Test fun t3_open_ar() = transition("t3_settings_open_ar", true, true)
    @Test fun t4_close_ar() = transition("t4_settings_close_ar", true, false)

    @Test fun s21_profile_me_light() = shoot("21_profile_me_light_en", false, ZuraThemeId.LIGHT) {
        app.zura.ui.profile.ProfileScreen(ME, {}, {})
    }
    @Test fun s22_profile_other_ar() = shoot("22_profile_contact_midnight_ar", true, ZuraThemeId.MIDNIGHT) {
        app.zura.ui.profile.ProfileScreen(SARA, {}, {})
    }
    @Test fun s23_call_light() = shoot("23_call_active_light_en", false, ZuraThemeId.LIGHT, manualClock = true,
        setup = { it!!.calls.previewForTest(callUi(CallPhase.ACTIVE, video = false, ar = false, net = NetType.WIFI)) }) { CallScreen(rule.activity.graph.s.calls) }
    @Test fun s24_call_incoming_light() = shoot("24_call_incoming_light_en", false, ZuraThemeId.LIGHT, manualClock = true,
        setup = { it!!.calls.previewForTest(callUi(CallPhase.INCOMING, video = false, ar = false)) }) { CallScreen(rule.activity.graph.s.calls) }

    @Test fun s17_call_reconnecting() = shoot("17_call_reconnecting_en", false, ZuraThemeId.MIDNIGHT, manualClock = true,
        setup = { it!!.calls.previewForTest(callUi(CallPhase.RECONNECTING, video = false, ar = false, quality = NetQuality.POOR, net = NetType.CELLULAR)) }) { CallScreen(rule.activity.graph.s.calls) }
}
