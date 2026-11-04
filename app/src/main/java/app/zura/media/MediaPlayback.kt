package app.zura.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import app.zura.core.model.MediaRef
import app.zura.core.storage.VaultStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One lazily-created ExoPlayer for the whole app (voice notes, videos, video moments).
 *
 * ExoPlayer must only ever be touched from the main thread — reading the position from a
 * background coroutine crashed the app the moment a voice note started. Every public method
 * therefore hops to the main looper, and progress is ticked with a main-thread Handler.
 */
@OptIn(UnstableApi::class)
class MediaPlayback(
    private val context: Context,
    private val vault: VaultStore,
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
) {
    data class State(
        val key: String? = null,
        val playing: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val speed: Float = 1f,
        val error: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            _state.value = _state.value.copy(positionMs = p.currentPosition, durationMs = p.duration.coerceAtLeast(_state.value.durationMs))
            if (p.isPlaying) main.postDelayed(this, 32)
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** Main thread only. */
    fun player(): ExoPlayer {
        check(Looper.myLooper() == Looper.getMainLooper()) { "player() must be called on the main thread" }
        return player ?: ExoPlayer.Builder(context).build().also { p ->
            player = p
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.value = _state.value.copy(playing = isPlaying, durationMs = p.duration.coerceAtLeast(_state.value.durationMs))
                    main.removeCallbacks(ticker)
                    if (isPlaying) main.post(ticker)
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        p.pause(); p.seekTo(0)
                        _state.value = _state.value.copy(positionMs = 0, playing = false)
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    // A damaged/unsupported file must never take the app down: just stop.
                    _state.value = _state.value.copy(playing = false, error = true)
                }
            })
        }
    }

    /** Toggle play/pause for [ref]; switching to another item stops the previous one. */
    fun toggle(chatId: String, messageId: String, ref: MediaRef) = onMain {
        runCatching {
            val p = player()
            if (_state.value.key == messageId && !_state.value.error) {
                if (p.isPlaying) p.pause() else p.play()
                return@runCatching
            }
            val factory = DataSource.Factory { EncryptedDataSource { vault.openReader(chatId, ref) } }
            val source = ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri("zvault://$chatId/${ref.fileName}"))
            p.setMediaSource(source)
            p.playbackParameters = PlaybackParameters(_state.value.speed)
            p.prepare()
            p.play()
            _state.value = State(key = messageId, playing = true, durationMs = ref.durationMs, speed = _state.value.speed)
        }.onFailure { _state.value = _state.value.copy(playing = false, error = true) }
    }

    fun seek(fraction: Float) = onMain {
        val p = player ?: return@onMain
        val d = p.duration.takeIf { it > 0 } ?: _state.value.durationMs
        p.seekTo((fraction.coerceIn(0f, 1f) * d).toLong())
        _state.value = _state.value.copy(positionMs = p.currentPosition)
    }

    fun cycleSpeed() = onMain {
        val next = when (_state.value.speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        player?.playbackParameters = PlaybackParameters(next)
        _state.value = _state.value.copy(speed = next)
    }

    fun play() = onMain { player?.play() }
    fun pause() = onMain { player?.pause() }

    fun stop() = onMain {
        main.removeCallbacks(ticker)
        player?.stop()
        _state.value = State(speed = _state.value.speed)
    }

    /** Safe from any thread (called when the app goes to the background). */
    fun release() = onMain {
        main.removeCallbacks(ticker)
        player?.release(); player = null
        _state.value = State()
    }
}
