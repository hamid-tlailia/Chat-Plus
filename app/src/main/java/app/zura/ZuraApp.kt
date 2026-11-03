package app.zura

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ZuraApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        val lifecycle = ProcessLifecycleOwner.get().lifecycle

        // Realtime connection lives while the UI is visible (+30 s grace for quick app switches),
        // and for the whole duration of a call.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var pending: Job? = null
            override fun onStart(owner: LifecycleOwner) { pending?.cancel(); graph.session.value?.onForeground() }
            override fun onStop(owner: LifecycleOwner) {
                pending = graph.scope.launch { delay(30_000); graph.session.value?.onBackground() }
            }
        })
        // A new sign-in while the app is visible connects immediately.
        graph.scope.launch {
            graph.accounts.session.collect { s ->
                if (s != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) s.onForeground()
            }
        }
    }
}

val android.content.Context.graph: AppGraph get() = (applicationContext as ZuraApp).graph
