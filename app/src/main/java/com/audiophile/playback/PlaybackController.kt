package com.audiophile.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.audiophile.data.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.*

class PlaybackController(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controllerFuture = MediaController.Builder(context, SessionToken(context, ComponentName(context, MusicService::class.java))).buildAsync()
    private val _current = MutableStateFlow<AudioTrack?>(null)
    val current: StateFlow<AudioTrack?> = _current.asStateFlow()
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()
    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()
    private var queue: List<AudioTrack> = emptyList()

    init {
        scope.launch {
            while (isActive) {
                if (controllerFuture.isDone && !controllerFuture.isCancelled) publishPosition(controllerFuture.get())
                delay(500L)
            }
        }
        controllerFuture.addListener({
            controllerFuture.get().addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { _isPlaying.value = isPlaying }
                override fun onPlaybackStateChanged(playbackState: Int) { publishPosition(controllerFuture.get()) }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    _current.value = queue.firstOrNull { it.uri == mediaItem?.mediaId }
                    publishPosition(controllerFuture.get())
                }
            })
        }, { it.run() })
    }

    fun play(track: AudioTrack, queue: List<AudioTrack>) {
        this.queue = queue
        controllerFuture.addListener({
            val controller = controllerFuture.get()
            val items = queue.map { MusicService.item(it.uri, it.title, it.artist, it.album, it.artworkUri) }
            val index = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            controller.setMediaItems(items, index, 0L)
            controller.prepare()
            controller.play()
            _current.value = track
            publishPosition(controller)
        }, { it.run() })
    }

    fun toggle() { controllerFuture.addListener({ controllerFuture.get().let { if (it.isPlaying) it.pause() else it.play() } }, { it.run() }) }
    fun next() { controllerFuture.addListener({ controllerFuture.get().seekToNext() }, { it.run() }) }
    fun previous() { controllerFuture.addListener({ controllerFuture.get().seekToPrevious() }, { it.run() }) }
    fun seekTo(positionMs: Long) { controllerFuture.addListener({ controllerFuture.get().seekTo(positionMs) }, { it.run() }) }
    fun toggleShuffle() { controllerFuture.addListener({ controllerFuture.get().let { controller -> controller.shuffleModeEnabled = !controller.shuffleModeEnabled; _shuffleEnabled.value = controller.shuffleModeEnabled } }, { it.run() }) }
    fun cycleRepeat() { controllerFuture.addListener({ controllerFuture.get().let { controller -> val next = when (controller.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }; controller.repeatMode = next; _repeatMode.value = next } }, { it.run() }) }
    fun release() { scope.cancel(); MediaController.releaseFuture(controllerFuture) }

    private fun publishPosition(controller: MediaController) { _positionMs.value = controller.currentPosition.coerceAtLeast(0L) }
}
