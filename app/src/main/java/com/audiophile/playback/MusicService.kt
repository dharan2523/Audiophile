package com.audiophile.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.audiophile.domain.RemoteStreamRegistry
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class MusicService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()
        val httpFactory = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val baseFactory = DefaultDataSource.Factory(this, httpFactory)
        val resolvingFactory = ResolvingDataSource.Factory(baseFactory) { dataSpec: DataSpec ->
            val stream = RemoteStreamRegistry.resolve(dataSpec.uri.toString())
            if (stream == null) dataSpec else dataSpec.buildUpon().setUri(stream.url).setHttpRequestHeaders(stream.headers).build()
        }
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(resolvingFactory)).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true
            )
            setHandleAudioBecomingNoisy(true)
        }
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, Class.forName("com.audiophile.MainActivity")), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run { player.release(); release() }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        fun item(uri: String, title: String, artist: String, album: String, artwork: String? = null): MediaItem =
            MediaItem.Builder().setUri(uri).setMediaId(uri).setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(title).setArtist(artist).setAlbumTitle(album).setArtworkUri(artwork?.let(android.net.Uri::parse)).build()
            ).build()
    }
}
