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
        handleAuthLink(intent)

        // Block screenshots / screen recording when asked. With app lock on, also keep chats out of
        // the recents thumbnail (API 33+ can do that without blocking screenshots altogether).
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(g.settings.secureScreen, g.appLock.enabled) { s, l -> s to l }.collect { (secure, lock) ->
                val flag = secure || (lock && android.os.Build.VERSION.SDK_INT < 33)
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
            ZuraTheme(theme, bubbles) {
                Box(Modifier.fillMaxSize().zuraBackground()) {
                    when (val a = auth) {
                        AuthState.Loading -> Unit
                        AuthState.SignedOut -> AuthScreen()
                        is AuthState.SignedIn -> key(a.session.uid) { SignedIn(a.session) }
                    }
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
            // Calls ring and can be answered above the lock (like WhatsApp).
            CallScreen(session.calls)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_CHAT)?.let { deepLink.value = it }
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
            Route.Settings -> SettingsScreen(onBack = { nav.pop() }, onProfile = { ctxGraph.session.value?.let { nav.push(Route.Profile(it.uid)) } })
            is Route.Moments -> MomentViewer(route.startOwner, onClose = { nav.pop() })
            is Route.Media -> MediaViewer(route.chatId, route.messageId, onClose = { nav.pop() })
        }
      }
    }
}
