package com.audiophile.domain

import android.net.Uri
import com.audiophile.data.AudioTrack
import kotlinx.coroutines.flow.Flow

interface MusicSource {
    val id: String
    val displayName: String
    fun observeTracks(): Flow<List<AudioTrack>>
    suspend fun connect(): Result<Unit>
    suspend fun disconnect()
}

data class RemoteLocation(val uri: Uri, val name: String, val sourceId: String)

data class SourceEntry(val id: String, val name: String, val isFolder: Boolean, val parentId: String? = null, val track: AudioTrack? = null)

data class SourcePage(val entries: List<SourceEntry>, val nextCursor: String? = null)

interface BrowseableMusicSource : MusicSource {
    suspend fun browse(containerId: String? = null): Result<SourcePage>
    suspend fun searchRemote(query: String): Result<SourcePage>
}
