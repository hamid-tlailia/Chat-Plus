package app.zura

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.view.WindowManager
import androidx.compose.runtime.key
import androidx.lifecycle.lifecycleScope
import app.zura.account.AccountSession
import app.zura.account.AuthState
import app.zura.i18n.LocaleEngine
import app.zura.ui.auth.AuthScreen
import app.zura.ui.call.CallScreen
import app.zura.ui.contacts.PeopleScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import app.zura.share.DirectShare
import app.zura.ui.chat.ChatScreen
import app.zura.ui.chats.ChatListScreen
import app.zura.ui.components.zuraBackground
import app.zura.ui.media.MediaViewer
import app.zura.ui.moments.MomentViewer
import app.zura.ui.settings.SettingsScreen
import app.zura.ui.theme.ZuraPalettes
import app.zura.ui.theme.ZuraTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

sealed interface Route {
    data object Chats : Route
    data class Chat(val id: String) : Route
    data object Settings : Route
    data object Vault : Route
    data object Studio : Route
    data class Moments(val startOwner: String) : Route
    data class Media(val chatId: String, val messageId: String) : Route
    data object People : Route
    data class Profile(val userId: String) : Route
}

/** 30-line back stack instead of navigation-compose: no reflection, no serialization, ~0 KB. */
class Navigator(start: Route) {
    val stack = mutableStateListOf(start)
    val top: Route get() = stack.last()
    /** Direction of the last navigation; read by the transition (idempotent, unlike counting). */
    var forward = true
        private set
    /** Jump without the slide (opening a chat from a notification should feel instant). */
    var instant = false
        private set
    fun push(r: Route) { if (top != r) { forward = true; instant = false; stack.add(r) } }
    fun pop(): Boolean = if (stack.size > 1) { forward = false; instant = false; stack.removeAt(stack.lastIndex); true } else false
    fun reset(vararg r: Route, instant: Boolean = false) { forward = true; this.instant = instant; stack.clear(); stack.addAll(r) }
}

class MainActivity : ComponentActivity() {

    private val deepLink = MutableStateFlow<String?>(null)

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(LocaleEngine.wrap(newBase))

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val g = graph
        val dark = ZuraPalettes.of(g.settings.theme.value).isDark
        enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(0) else SystemBarStyle.light(0, 0),
            navigationBarStyle = if (dark) SystemBarStyle.dark(0) else SystemBarStyle.light(0, 0),
        )
        super.onCreate(savedInstanceState)
        deepLink.value = intent.getStringExtra(EXTRA_CHAT)
        deepLink.value?.let(::prewarm)
        handleAnswer(intent)
        handleAuthLink(intent)

        // Block screenshots / screen recording when asked. With app lock on, also keep chats out of
        // the recents thumbnail (API 33+ can do that without blocking screenshots altogether).
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(g.settings.secureScreen, g.appLock.enabled, g.settings.chatSecure) { s, l, chat -> Triple(s, l, chat) }.collect { (secure, lock, chat) ->
                val flag = secure || chat || (lock && android.os.Build.VERSION.SDK_INT < 33)
                if (flag) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!lock && !secure)
            }
        }

        // Show above the keyguard and wake the screen ONLY while a call is ringing / in progress —
        // never for chats (a static manifest flag exposed the whole app over the lock screen).
        lifecycleScope.launch {
            g.accounts.state.flatMapLatest { a ->
                (a as? AuthState.SignedIn)?.session?.calls?.ui?.map { it.phase != app.zura.call.CallPhase.IDLE } ?: flowOf(false)
            }.distinctUntilChanged().collect { showOverLock(it) }
        }

        setContent {
            val theme by g.settings.theme.collectAsStateWithLifecycle()
            val auth by g.accounts.state.collectAsStateWithLifecycle()
            val bubbles by g.settings.bubbles.collectAsStateWithLifecycle()
            val custom by g.settings.customTheme.collectAsStateWithLifecycle()
            val useCustom by g.settings.useCustom.collectAsStateWithLifecycle()
            val files by g.settings.themeFiles.collectAsStateWithLifecycle()
            val active = custom.takeIf { useCustom }
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val font = remember(active != null, files) { if (active != null) app.zura.ui.theme.CustomTheme.fontFamily(ctx) else null }
            val wall by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, active != null, files) {
                value = if (active == null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    app.zura.ui.theme.CustomTheme.wallFile(ctx).takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
                }
            }
            // Status / navigation bar icons follow the theme live (dark theme → light icons).
            val view = androidx.compose.ui.platform.LocalView.current
            val isDark = ZuraPalettes.of(theme).isDark
            androidx.compose.runtime.LaunchedEffect(isDark) {
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !isDark
                    isAppearanceLightNavigationBars = !isDark
                }
            }
            ZuraTheme(theme, bubbles, active, font, wall) {
                Box(Modifier.fillMaxSize().zuraBackground()) {
                    when (val a = auth) {
                        AuthState.Loading -> SplashScreen()
                        AuthState.SignedOut -> AuthScreen()
                        is AuthState.SignedIn -> key(a.session.uid) { SignedIn(a.session) }
                    }
                    // Sheets (confirmations, pickers, walkie-talkie) draw here, above everything.
                    app.zura.ui.components.OverlayLayer()
                }
            }
        }
    }

    @Composable
    private fun SignedIn(session: AccountSession) {
        // Opened from a message notification: start directly on that chat (no list frame, no slide).
        val nav = remember {
            Navigator(Route.Chats).also { n -> deepLink.value?.let { n.reset(Route.Chats, Route.Chat(it), instant = true); deepLink.value = null } }
        }
        val link by deepLink.collectAsStateWithLifecycle()
        LaunchedEffect(link) { link?.let { nav.reset(Route.Chats, Route.Chat(it), instant = true); deepLink.value = null } }
        LaunchedEffect(session) {
            session.chats.chats.debounce(2_000).collect { DirectShare.publish(this@MainActivity, it) }
        }
        val call by session.calls.ui.collectAsStateWithLifecycle()
        val locked by graph.appLock.locked.collectAsStateWithLifecycle()
        BackHandler(enabled = nav.stack.size > 1 && call.phase == app.zura.call.CallPhase.IDLE) { nav.pop() }
        Box(Modifier.fillMaxSize()) {
            // Locked: chats are not even composed — nothing to leak.
            if (locked) app.zura.security.LockScreen(graph.appLock)
            else {
                NavHost(nav)
                app.zura.push.CallReadinessPrompt()
            }
            // Live walkie-talkie audio from a contact, wherever I am in the app.
            if (!locked) app.zura.ui.chat.WalkieIndicator(Modifier.align(androidx.compose.ui.Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp))
            // Calls ring and can be answered above the lock (like WhatsApp).
            CallScreen(session.calls)
        }
    }

    /**
     * "Answer" from the call notification: we're in the foreground now, so the microphone may
     * start. Waits briefly for that exact call (a just-woken app may still be setting it up).
     */
    private fun handleAnswer(i: Intent?) {
        if (i?.action != app.zura.call.CallService.ACTION_ANSWER) return
        val forCall = i.getStringExtra(app.zura.call.CallService.EXTRA_CALL_ID)
        i.action = null // a recreated activity must not answer twice
        DiagLog.record("call", detail = "answer from notification → app opened (call ${forCall?.take(8) ?: "pre-ring"})")
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return // the call screen asks
        graph.scope.launch {
            val calls = kotlinx.coroutines.withTimeoutOrNull(8_000) {
                while (true) {
                    val c = (graph.accounts.state.value as? AuthState.SignedIn)?.session?.calls
                    val ui = c?.ui?.value
                    if (c != null && ui != null && ui.phase == app.zura.call.CallPhase.INCOMING && (forCall == null || ui.callId == forCall)) return@withTimeoutOrNull c
                    kotlinx.coroutines.delay(100)
                }
                @Suppress("UNREACHABLE_CODE") null
            }
            calls?.accept() ?: DiagLog.record("call", detail = "answer: that call is no longer ringing")
        }
    }

    /** Opened from a message notification: decrypt that chat now, while the lock / splash is showing. */
    private fun prewarm(chatId: String) {
        // Cold start (app was swiped away): the account is still opening — wait for it instead of
        // giving up, so the chat's history is decrypted while the account / splash finishes.
        graph.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val s = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                (graph.accounts.state.first { it is AuthState.SignedIn } as AuthState.SignedIn).session
            } ?: return@launch
            val t = android.os.SystemClock.elapsedRealtime()
            runCatching { if (s.vault.exists(chatId)) s.vault.chat(chatId) }
            DiagLog.record("open chat", detail = "history ready in ${android.os.SystemClock.elapsedRealtime() - t} ms (${s.vault.chat(chatId).messages.value.size} messages)")
        }
    }

    private fun showOverLock(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(on); setTurnScreenOn(on) }
        else {
            @Suppress("DEPRECATION")
            val f = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (on) window.addFlags(f) else window.clearFlags(f)
        }
    }

    override fun onResume() {
        super.onResume()
        // Signed in and no call → any call notification still showing is a leftover: clear it.
        val st = graph.accounts.state.value
        if (st is AuthState.SignedIn) app.zura.call.CallService.clearStale(this, st.session.calls.ui.value.inCall)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_CHAT)?.let { deepLink.value = it; prewarm(it) }
        handleAnswer(intent)
        handleAuthLink(intent)
    }

    /** Email-confirmation link → sign in directly. */
    private fun handleAuthLink(intent: Intent?) {
        val uri = intent?.data?.takeIf { it.scheme == "zura" && it.host == "auth" } ?: return
        lifecycleScope.launch {
            val r = graph.accounts.handleAuthLink(uri)
            if (r is app.zura.account.AuthResult.Error) android.widget.Toast.makeText(this@MainActivity, r.message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    companion object { const val EXTRA_CHAT = "zura.chat" }
}

@Composable
internal fun NavHost(nav: Navigator) {
    val ctxGraph = androidx.compose.ui.platform.LocalContext.current.graph
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    AnimatedContent(
        targetState = nav.top,
        transitionSpec = {
            if (nav.instant) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
            else app.zura.ui.components.pageTransition(nav.forward, rtl)
        },
        label = "nav",
    ) { route ->
      // Each page is opaque, so the sliding page cleanly covers the one beneath it.
      Box(Modifier.fillMaxSize().zuraBackground()) {
        when (route) {
            Route.Chats -> ChatListScreen(
                onOpen = { nav.push(Route.Chat(it)) },
                onSettings = { nav.push(Route.Settings) },
                onMoments = { nav.push(Route.Moments(it)) },
                onPeople = { nav.push(Route.People) },
                onProfile = { nav.push(Route.Profile(it)) },
            )
            Route.People -> PeopleScreen(onBack = { nav.pop() }, onOpenChat = { nav.pop(); nav.push(Route.Chat(it)) }, onOpenProfile = { nav.push(Route.Profile(it)) })
            is Route.Profile -> app.zura.ui.profile.ProfileScreen(route.userId, onBack = { nav.pop() }, onOpenChat = { chatId ->
                // Came from that chat → just go back to it; otherwise open it.
                val below = nav.stack.getOrNull(nav.stack.lastIndex - 1)
                nav.pop(); if (below != Route.Chat(chatId)) nav.push(Route.Chat(chatId))
            })
            is Route.Chat -> ChatScreen(route.id, onBack = { nav.pop() }, onOpenMedia = { nav.push(Route.Media(route.id, it)) }, onOpenProfile = { nav.push(Route.Profile(it)) })
            Route.Settings -> SettingsScreen(
                onBack = { nav.pop() },
                onProfile = { ctxGraph.session.value?.let { nav.push(Route.Profile(it.uid)) } },
                onVault = { nav.push(Route.Vault) },
                onStudio = { nav.push(Route.Studio) },
            )
            Route.Studio -> app.zura.ui.theme.ThemeStudioScreen(onBack = { nav.pop() })
            Route.Vault -> app.zura.ui.vault.MediaVaultScreen(onBack = { nav.pop() }, onOpenMedia = { chatId, id -> nav.push(Route.Media(chatId, id)) })
            is Route.Moments -> MomentViewer(route.startOwner, onClose = { nav.pop() })
            is Route.Media -> MediaViewer(route.chatId, route.messageId, onClose = { nav.pop() })
        }
      }
    }
}

/** Shown for the moment the saved account is being opened (never a blank page). */
@Composable
private fun SplashScreen() {
    val c = app.zura.ui.theme.Zura.colors
    Column(Modifier.fillMaxSize(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        androidx.compose.material3.Text("Zura", style = app.zura.ui.theme.Zura.type.title, color = c.text)
        androidx.compose.foundation.layout.Spacer(Modifier.padding(10.dp))
        androidx.compose.material3.CircularProgressIndicator(Modifier.padding(4.dp), color = c.accent, strokeWidth = 2.dp)
    }
}
