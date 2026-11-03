package app.zura

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ZuraApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)

        // Socket lives only while the UI is visible (+30 s grace for quick app switches).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var pending: Job? = null
            override fun onStart(owner: LifecycleOwner) { pending?.cancel(); graph.relay.connect() }
            override fun onStop(owner: LifecycleOwner) {
                pending = graph.scope.launch { delay(30_000); graph.relay.disconnect(); graph.playback.release() }
            }
        })

        // Housekeeping after the first frame has long been drawn — never on the startup path.
        graph.scope.launch(Dispatchers.IO) {
            delay(4_000)
            runCatching { graph.janitor.sweep() }
            runCatching { graph.moments.expire() }
        }
    }
}

val android.content.Context.graph: AppGraph get() = (applicationContext as ZuraApp).graph
