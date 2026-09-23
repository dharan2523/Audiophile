package com.audiophile.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import androidx.room.Query
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Upsert
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Entity(tableName = "tracks")
data class AudioTrack(
    @PrimaryKey val id: Long,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val year: Int,
    val trackNumber: Int,
    val durationMs: Long,
    val mimeType: String,
    val codec: String = "",
    val bitrate: Int,
    val sampleRate: Int,
    val artworkUri: String? = null,
    val source: String = "Local storage",
    val isFavorite: Boolean = false,
    val lastPlayedAt: Long = 0L,
    val bitDepth: Int = 0
)

@Dao
interface AudioDao {
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<AudioTrack>>

    @Query("SELECT * FROM tracks WHERE isFavorite = 1 ORDER BY title COLLATE NOCASE")
    fun observeFavorites(): Flow<List<AudioTrack>>

    @Query("SELECT * FROM tracks WHERE title LIKE :query OR artist LIKE :query OR album LIKE :query OR uri LIKE :query ORDER BY title COLLATE NOCASE")
    fun search(query: String): Flow<List<AudioTrack>>

    @Query("SELECT * FROM tracks ORDER BY lastPlayedAt DESC LIMIT 12")
    fun observeRecentlyPlayed(): Flow<List<AudioTrack>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tracks: List<AudioTrack>)

    @Query("UPDATE tracks SET uri = :uri, title = :title, artist = :artist, album = :album, albumArtist = :albumArtist, genre = :genre, year = :year, trackNumber = :trackNumber, durationMs = :durationMs, mimeType = :mimeType, codec = :codec, bitrate = :bitrate, sampleRate = :sampleRate, artworkUri = :artworkUri, source = :source, bitDepth = :bitDepth WHERE id = :id")
    suspend fun updateMetadata(id: Long, uri: String, title: String, artist: String, album: String, albumArtist: String, genre: String, year: Int, trackNumber: Int, durationMs: Long, mimeType: String, codec: String, bitrate: Int, sampleRate: Int, artworkUri: String?, source: String, bitDepth: Int)

    @Transaction
    suspend fun syncTracks(tracks: List<AudioTrack>) {
        insertAll(tracks)
        tracks.forEach { track -> updateMetadata(track.id, track.uri, track.title, track.artist, track.album, track.albumArtist, track.genre, track.year, track.trackNumber, track.durationMs, track.mimeType, track.codec, track.bitrate, track.sampleRate, track.artworkUri, track.source, track.bitDepth) }
    }

    @Query("UPDATE tracks SET isFavorite = NOT isFavorite WHERE id = :id")
    suspend fun toggleFavorite(id: Long)

    @Query("UPDATE tracks SET lastPlayedAt = :timestamp WHERE id = :id")
    suspend fun markPlayed(id: Long, timestamp: Long)
}

@Database(entities = [AudioTrack::class], version = 3, exportSchema = false)
abstract class AudioDatabase : RoomDatabase() { abstract fun audioDao(): AudioDao }

class AudioRepository(internal val context: Context, private val dao: AudioDao) {
    val tracks = dao.observeAll()
    val favorites = dao.observeFavorites()
    val recentlyPlayed = dao.observeRecentlyPlayed()

    fun search(query: String): Flow<List<AudioTrack>> = dao.search("%$query%")
    suspend fun toggleFavorite(id: Long) = dao.toggleFavorite(id)
    suspend fun markPlayed(id: Long) = dao.markPlayed(id, System.currentTimeMillis())

    suspend fun scanLocalStorage() = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ARTIST, MediaStore.Audio.Media.GENRE,
            MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.BITRATE,
            MediaStore.Audio.Media.ALBUM_ID
        )
        val result = mutableListOf<AudioTrack>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
            null, null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                val audioMetadata = audioMetadataFor(uri)
                result += AudioTrack(
                    id = id,
                    uri = uri.toString(),
                    title = cursor.text(MediaStore.Audio.Media.TITLE),
                    artist = cursor.text(MediaStore.Audio.Media.ARTIST).ifBlank { "Unknown artist" },
                    album = cursor.text(MediaStore.Audio.Media.ALBUM).ifBlank { "Unknown album" },
                    albumArtist = cursor.text(MediaStore.Audio.Media.ALBUM_ARTIST),
                    genre = cursor.text(MediaStore.Audio.Media.GENRE),
                    year = cursor.int(MediaStore.Audio.Media.YEAR),
                    trackNumber = cursor.int(MediaStore.Audio.Media.TRACK),
                    durationMs = cursor.long(MediaStore.Audio.Media.DURATION),
                    mimeType = cursor.text(MediaStore.Audio.Media.MIME_TYPE),
                    codec = cursor.text(MediaStore.Audio.Media.MIME_TYPE).substringAfter('/', "unknown"),
                    bitrate = cursor.int(MediaStore.Audio.Media.BITRATE),
                    sampleRate = audioMetadata.sampleRate,
                    bitDepth = audioMetadata.bitDepth,
                    artworkUri = cursor.long(MediaStore.Audio.Media.ALBUM_ID).takeIf { it > 0 }?.let { albumId -> "content://media/external/audio/albumart/$albumId" }
                )
            }
        }
        if (result.isNotEmpty()) dao.syncTracks(result)
    }
}

private fun android.database.Cursor.text(column: String): String = getColumnIndex(column).takeIf { it >= 0 }?.let { getString(it) }.orEmpty()
private fun android.database.Cursor.int(column: String): Int = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getInt(it) } ?: 0
private fun android.database.Cursor.long(column: String): Long = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getLong(it) } ?: 0L

private data class AudioMetadata(val sampleRate: Int, val bitDepth: Int)

private fun AudioRepository.audioMetadataFor(uri: android.net.Uri): AudioMetadata = runCatching {
    android.media.MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(context, uri)
        AudioMetadata(
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0,
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0
        )
    }
}.getOrDefault(AudioMetadata(0, 0))
