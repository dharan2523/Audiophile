package com.audiophile.domain

import com.audiophile.data.AudioRepository
import com.audiophile.data.AudioTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class LocalMusicSource(private val repository: AudioRepository) : MusicSource {
    override val id = "local"
    override val displayName = "Local storage"
    override fun observeTracks(): Flow<List<AudioTrack>> = repository.tracks
    override suspend fun connect(): Result<Unit> = runCatching { repository.scanLocalStorage() }
    override suspend fun disconnect() = Unit
}

data class SourceStatus(val id: String, val name: String, val connected: Boolean, val detail: String)

class SourceRegistry(localSource: LocalMusicSource, remoteSources: List<MusicSource> = emptyList()) {
    private val sources = listOf(localSource) + remoteSources
    private val _statuses = MutableStateFlow(
        sources.map { source -> SourceStatus(source.id, source.displayName, source.id == "local", if (source.id == "local") "Device library" else "Not connected") }
    )
    val statuses: StateFlow<List<SourceStatus>> = _statuses.asStateFlow()

    fun observeAllTracks(): Flow<List<AudioTrack>> = combine(sources.map { it.observeTracks() }) { groups ->
        groups.flatMap { it.toList() }.distinctBy { it.id }.sortedBy { it.title.lowercase() }
    }

    fun search(query: String): Flow<List<AudioTrack>> = observeAllTracks().map { tracks ->
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) tracks else tracks.filter { track ->
            track.title.lowercase().contains(normalized) || track.artist.lowercase().contains(normalized) || track.album.lowercase().contains(normalized) || track.uri.lowercase().contains(normalized)
        }
    }

    suspend fun connect(id: String): Result<Unit> {
        val source = sources.firstOrNull { it.id == id } ?: return Result.failure(UnsupportedOperationException("Source adapter is not configured"))
        val result = source.connect()
        val current = _statuses.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) current[index] = SourceStatus(id, source.displayName, result.isSuccess, result.exceptionOrNull()?.message ?: "Connected")
        _statuses.value = current
        return result
    }

    suspend fun disconnect(id: String) { sources.firstOrNull { it.id == id }?.disconnect(); _statuses.value = _statuses.value.map { status -> if (status.id == id) status.copy(connected = false, detail = "Disconnected") else status } }
}
