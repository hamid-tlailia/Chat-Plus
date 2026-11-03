package app.zura.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import app.zura.core.model.MediaRef
import app.zura.core.storage.VaultStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One lazily-created ExoPlayer for the whole app (voice notes, videos, video moments).
 * Created on first play, released when idle — zero cost at cold start.
 */
@OptIn(UnstableApi::class)
class MediaPlayback(private val context: Context, private val vault: VaultStore, private val scope: CoroutineScope) {

    data class State(val key: String? = null, val playing: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0, val speed: Float = 1f)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var player: ExoPlayer? = null
    private var ticker: Job? = null

    fun player(): ExoPlayer = player ?: ExoPlayer.Builder(context).build().also { p ->
        player = p
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(playing = isPlaying, durationMs = p.duration.coerceAtLeast(0))
                if (isPlaying) startTicker() else ticker?.cancel()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) { p.seekTo(0); p.pause(); _state.value = _state.value.copy(positionMs = 0, playing = false) }
            }
        })
    }

    /** Toggle play/pause for [ref]; switching to another item stops the previous one. */
    fun toggle(chatId: String, messageId: String, ref: MediaRef) {
        val p = player()
        if (_state.value.key == messageId) {
            if (p.isPlaying) p.pause() else p.play()
            return
        }
        val factory = DataSource.Factory { EncryptedDataSource { vault.openReader(chatId, ref) } }
        val source = ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri("zvault://$chatId/${ref.fileName}"))
        p.setMediaSource(source)
        p.playbackParameters = PlaybackParameters(_state.value.speed)
        p.prepare()
        p.play()
        _state.value = State(key = messageId, playing = true, durationMs = ref.durationMs, speed = _state.value.speed)
    }

    fun seek(fraction: Float) {
        val p = player ?: return
        p.seekTo((fraction.coerceIn(0f, 1f) * p.duration.coerceAtLeast(0)).toLong())
        _state.value = _state.value.copy(positionMs = p.currentPosition)
    }

    fun cycleSpeed() {
        val next = when (_state.value.speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        player?.playbackParameters = PlaybackParameters(next)
        _state.value = _state.value.copy(speed = next)
    }

    fun stop() {
        player?.stop()
        _state.value = State(speed = _state.value.speed)
    }

    fun release() {
        ticker?.cancel()
        player?.release(); player = null
        _state.value = State()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                player?.let { _state.value = _state.value.copy(positionMs = it.currentPosition, durationMs = it.duration.coerceAtLeast(0)) }
                delay(32) // ~30 fps progress is plenty; waveform fill is interpolated by the UI
            }
        }
    }
}
