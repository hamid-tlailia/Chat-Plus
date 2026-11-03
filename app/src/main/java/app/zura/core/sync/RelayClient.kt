package app.zura.core.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Single multiplexed WebSocket for every chat. Bidirectional, sub-100 ms on a warm connection.
 *
 * - Exponential backoff with full jitter (0.5 s → 30 s), reset on success.
 * - Frames queued while offline are flushed in order on reconnect; durable resend of unACKed
 *   messages is the repository's job (PENDING messages live in the encrypted log).
 * - Only connected while the app is foregrounded ([connect]/[disconnect] driven by process
 *   lifecycle); background delivery arrives via a high-priority FCM data push that wakes us.
 */
class RelayClient(
    private val url: String,
    private val scope: CoroutineScope,
    private val hello: () -> Frame.Hello,
) {
    enum class State { OFFLINE, CONNECTING, ONLINE }

    private val http = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _state = MutableStateFlow(State.OFFLINE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<Frame>(extraBufferCapacity = 256)
    val incoming: SharedFlow<Frame> = _incoming

    private val outbox = ConcurrentLinkedQueue<String>()
    @Volatile private var socket: WebSocket? = null
    private var loop: Job? = null
    @Volatile private var wanted = false

    fun connect() {
        wanted = true
        if (loop?.isActive == true) return
        loop = scope.launch {
            var attempt = 0
            while (isActive && wanted) {
                if (socket == null) {
                    _state.value = State.CONNECTING
                    open()
                }
                // Wait until this socket dies.
                while (isActive && socket != null) delay(250)
                if (!wanted) break
                attempt++
                val cap = minOf(30_000L, 500L shl minOf(attempt, 6))
                delay(Random.nextLong(cap / 2, cap + 1))
                if (_state.value == State.ONLINE) attempt = 0
            }
        }
    }

    fun disconnect() {
        wanted = false
        loop?.cancel()
        socket?.close(1000, "background")
        socket = null
        _state.value = State.OFFLINE
    }

    /** Fire-and-forget send; queued if offline. */
    fun send(frame: Frame) {
        val text = WireJson.encodeToString(Frame.serializer(), frame)
        val s = socket
        if (s == null || _state.value != State.ONLINE || !s.send(text)) outbox.add(text)
    }

    private fun open() {
        socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(WireJson.encodeToString(Frame.serializer(), hello()))
                _state.value = State.ONLINE
                while (true) {
                    val next = outbox.peek() ?: break
                    if (!webSocket.send(next)) break
                    outbox.poll()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { WireJson.decodeFromString(Frame.serializer(), text) }
                    .onSuccess { _incoming.tryEmit(it) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dead(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dead(webSocket)
        })
    }

    private fun dead(ws: WebSocket) {
        if (socket === ws) {
            socket = null
            _state.value = State.OFFLINE
        }
    }
}
