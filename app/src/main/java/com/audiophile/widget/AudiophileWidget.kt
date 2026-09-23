package com.audiophile.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.graphics.BitmapFactory
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.audiophile.R
import com.audiophile.playback.MusicService

class AudiophileWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_PLAY, ACTION_PREVIOUS, ACTION_NEXT -> {
                val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, MusicService::class.java))).buildAsync()
                future.addListener({
                    val controller = future.get()
                    when (intent.action) {
                        ACTION_PLAY -> if (controller.isPlaying) controller.pause() else controller.play()
                        ACTION_PREVIOUS -> controller.seekToPrevious()
                        ACTION_NEXT -> controller.seekToNext()
                    }
                    refresh(context, controller)
                    MediaController.releaseFuture(future)
                }, java.util.concurrent.Executor { it.run() })
            }
        }
    }

    private fun update(context: Context, manager: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_audiophile).apply {
            setOnClickPendingIntent(R.id.widget_play, action(context, ACTION_PLAY))
            setOnClickPendingIntent(R.id.widget_previous, action(context, ACTION_PREVIOUS))
            setOnClickPendingIntent(R.id.widget_next, action(context, ACTION_NEXT))
        }
        manager.updateAppWidget(id, views)
    }

    private fun refresh(context: Context, controller: MediaController) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, AudiophileWidget::class.java))
        val metadata = controller.mediaMetadata
        val views = RemoteViews(context.packageName, R.layout.widget_audiophile).apply {
            setTextViewText(R.id.widget_title, metadata.title ?: "Audiophile")
            setTextViewText(R.id.widget_artist, metadata.artist ?: "Ready to listen")
            setImageViewResource(R.id.widget_play, if (controller.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            metadata.artworkUri?.let { uri ->
                runCatching { context.contentResolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream) } }
                    .getOrNull()?.let { bitmap -> setImageViewBitmap(R.id.widget_art, bitmap) }
            }
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private fun action(context: Context, action: String) = PendingIntent.getBroadcast(context, action.hashCode(), Intent(context, AudiophileWidget::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    companion object { const val ACTION_PLAY = "com.audiophile.widget.PLAY"; const val ACTION_PREVIOUS = "com.audiophile.widget.PREVIOUS"; const val ACTION_NEXT = "com.audiophile.widget.NEXT" }
}
