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
        graph = AppGraph(this)
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
                pending = graph.scope.launch { delay(30_000); graph.session.value?.onBackground() }
            }
        })
        // Phone unlocked → chat keys work again: pull anything that waited on the server.
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                graph.session.value?.let { s -> s.scope.launch { runCatching { s.transport.syncInbox() } } }
            }
        }, android.content.IntentFilter(android.content.Intent.ACTION_USER_PRESENT))

        // A new sign-in while the app is visible connects immediately.
        graph.scope.launch {
            graph.accounts.session.collect { s ->
                if (s != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) s.onForeground()
            }
        }
    }
}

val android.content.Context.graph: AppGraph get() = (applicationContext as ZuraApp).graph
