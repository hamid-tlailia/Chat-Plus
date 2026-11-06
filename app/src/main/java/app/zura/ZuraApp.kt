package app.zura

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

open class ZuraApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        DiagLog.init(this)
        // Zura as a self-managed calling app (Android Telecom): registered once, cheap, idempotent.
        app.zura.call.Telecom.register(this)
        // Decrypted copies handed to other apps are short-lived: wipe any left from last time.
        Thread { runCatching { app.zura.core.storage.SecureEraser.eraseTree(java.io.File(cacheDir, "export")) } }.start()
        graph = AppGraph(this)
        graph.themeClock.start()
        app.zura.push.Push.init(this)
        // Resume the remembered account immediately (also when woken by a push in the background).
        graph.accounts
        val lifecycle = ProcessLifecycleOwner.get().lifecycle

        // Realtime connection lives while the UI is visible (+30 s grace for quick app switches),
        // and for the whole duration of a call.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var pending: Job? = null
            override fun onStart(owner: LifecycleOwner) { pending?.cancel(); graph.appLock.onForeground(); graph.session.value?.onForeground() }
            override fun onStop(owner: LifecycleOwner) {
                graph.appLock.onBackground()
                graph.session.value?.onHidden()
                // In a video call the camera pauses right away (the call itself keeps running).
                graph.session.value?.calls?.let { c -> if (c.ui.value.inCall) c.onAppBackground() }
                pending = graph.scope.launch { delay(30_000); graph.session.value?.onBackground() }
            }
        })
        // Phone unlocked → chat keys work again: pull anything that waited on the server.
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                graph.session.value?.let { s ->
                    s.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        DiagLog.record("lock", detail = "unlocked (${i.action?.substringAfterLast('.')}) → open chat storage")
                        runCatching { s.chats.onUnlocked() } // chat list + deferred call logs
                        runCatching { s.transport.syncInbox() } // messages that waited for the keys
                    }
                }
            }
        }, android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_USER_PRESENT) // lock screen dismissed
            addAction(android.content.Intent.ACTION_USER_UNLOCKED) // first unlock after boot
        })

        // Network back (Wi-Fi ↔ mobile, out of a dead zone): reconnect at once, not after the back-off.
        runCatching {
            getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { graph.session.value?.transport?.kick() }
            })
        }

        // A new sign-in while the app is visible connects immediately.
        graph.scope.launch {
            graph.accounts.session.collect { s ->
                if (s != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) s.onForeground()
            }
        }
    }
}

val android.content.Context.graph: AppGraph get() = (applicationContext as ZuraApp).graph
