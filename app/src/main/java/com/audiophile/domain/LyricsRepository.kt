package com.audiophile.domain

import android.content.ContentResolver
import android.net.Uri
import com.audiophile.data.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context

class LyricsRepository(private val resolver: ContentResolver, context: Context? = null) {
    private val savedLyrics = context?.getSharedPreferences("saved_lyrics", Context.MODE_PRIVATE)

    suspend fun load(track: AudioTrack): List<LyricLine> = withContext(Dispatchers.IO) {
        val saved = savedLyrics?.getString(track.uri, null)
        if (!saved.isNullOrBlank()) return@withContext LyricsParser.parse(saved)
        val candidates = buildList {
            val parsed = Uri.parse(track.uri)
            if (parsed.scheme == "file") {
                add(java.io.File(parsed.path.orEmpty().removeSuffix(java.io.File.separator + track.title), "${track.title}.lrc"))
            }
            resolver.getFilePath(parsed)?.let { path -> add(java.io.File(path.substringBeforeLast('.', path) + ".lrc")) }
        }
        val sidecar = candidates.firstOrNull { it.isFile && it.canRead() }
        if (sidecar != null) LyricsParser.parse(sidecar.readText(Charsets.UTF_8))
        else {
            val embedded = readEmbedded(track)
            if (embedded != null) LyricsParser.parse(embedded) else emptyList()
        }
    }

    fun save(track: AudioTrack, raw: String) {
        savedLyrics?.edit()?.putString(track.uri, raw)?.apply()
    }

    private fun readEmbedded(track: AudioTrack): String? = runCatching<String?> {
        resolver.openInputStream(Uri.parse(track.uri))?.use { stream ->
            val bytes = stream.readBytes().take(4 * 1024 * 1024).toByteArray()
            val text = bytes.toString(Charsets.UTF_8)
            Regex("(?is)(?:UNSYNCEDLYRICS|LYRICS|©lyr)\\s*[=:\\u0000]+(.+?)(?:\\u0000{2,}|$)").find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()
}

private fun ContentResolver.getFilePath(uri: Uri): String? = runCatching {
    query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()
