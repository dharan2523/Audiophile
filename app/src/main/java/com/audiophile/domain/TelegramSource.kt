package com.audiophile.domain

import android.content.Context
import com.audiophile.data.AudioTrack
import com.audiophile.security.SecureTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

sealed interface TelegramAuthState {
    data object Disconnected : TelegramAuthState
    data object WaitingForPhone : TelegramAuthState
    data object WaitingForCode : TelegramAuthState
    data object WaitingForPassword : TelegramAuthState
    data object Ready : TelegramAuthState
    data class Error(val message: String) : TelegramAuthState
}

data class TelegramChat(val id: Long, val title: String, val isChannel: Boolean, val isSavedMessages: Boolean, val audioCount: Int? = null, val iconUri: String? = null)
data class TelegramAudio(val chatId: Long, val messageId: Long, val fileId: Int, val fileName: String, val title: String, val artist: String, val album: String, val durationMs: Long, val mimeType: String, val fileSize: Long, val messageDateMs: Long, val thumbnailUri: String? = null)
data class TelegramPage<T>(val items: List<T>, val nextOffset: Long?, val hasMore: Boolean)
data class TelegramStream(val url: String, val headers: Map<String, String> = emptyMap(), val localFile: File? = null)

interface TelegramGateway {
    val authState: Flow<TelegramAuthState>
    suspend fun start(): Result<TelegramAuthState>
    suspend fun submitPhone(phoneNumber: String): Result<TelegramAuthState>
    suspend fun submitCode(code: String): Result<TelegramAuthState>
    suspend fun submitPassword(password: String): Result<TelegramAuthState>
    suspend fun logout(): Result<Unit>
    suspend fun listChats(offset: Long?, limit: Int): Result<TelegramPage<TelegramChat>>
    suspend fun listAudio(chatId: Long, fromMessageId: Long?, limit: Int): Result<TelegramPage<TelegramAudio>>
    suspend fun search(query: String, fromMessageId: Long?, limit: Int): Result<TelegramPage<TelegramAudio>>
    suspend fun stream(audio: TelegramAudio): Result<TelegramStream>
}

class TelegramSource(
    private val gateway: TelegramGateway,
    private val credentials: SecureTokenStore,
    private val cache: TelegramMediaCache? = null
) : BrowseableMusicSource {
    override val id = "telegram"
    override val displayName = "Telegram"
    private val tracksState = MutableStateFlow<List<AudioTrack>>(emptyList())
    private val selectedChat = MutableStateFlow<Long?>(null)
    override fun observeTracks(): Flow<List<AudioTrack>> = tracksState
    val authState: Flow<TelegramAuthState> = gateway.authState

    suspend fun submitPhone(phoneNumber: String): Result<TelegramAuthState> = gateway.submitPhone(phoneNumber)
    suspend fun submitCode(code: String): Result<TelegramAuthState> = gateway.submitCode(code)
    suspend fun submitPassword(password: String): Result<TelegramAuthState> = gateway.submitPassword(password)

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        gateway.start().map { state ->
            if (state !is TelegramAuthState.Ready) error("Telegram authentication required")
            Unit
        }
    }

    override suspend fun disconnect() {
        gateway.logout()
        tracksState.value = emptyList()
        selectedChat.value = null
    }

    override suspend fun browse(containerId: String?): Result<SourcePage> = withContext(Dispatchers.IO) {
        if (containerId == null) {
            gateway.listChats(null, 50).map { page -> SourcePage(page.items.map { chat -> SourceEntry(chat.id.toString(), chat.title, true, null) }, page.nextOffset?.toString()) }
        } else {
            val chatId = containerId.toLongOrNull() ?: return@withContext Result.failure(IllegalArgumentException("Invalid Telegram chat"))
            selectedChat.value = chatId
            val result = gateway.listAudio(chatId, null, 50)
            if (result.isFailure) Result.failure(result.exceptionOrNull() ?: IllegalStateException("Telegram browse failed")) else result.getOrThrow().let { page -> Result.success(SourcePage(page.items.toEntries(), page.nextOffset?.toString())) }
        }
    }

    override suspend fun searchRemote(query: String): Result<SourcePage> = withContext(Dispatchers.IO) {
        val result = gateway.search(query, null, 50)
        if (result.isFailure) Result.failure(result.exceptionOrNull() ?: IllegalStateException("Telegram search failed")) else result.getOrThrow().let { page -> Result.success(SourcePage(page.items.toEntries(), page.nextOffset?.toString())) }
    }

    suspend fun loadMore(chatId: Long, offset: Long?): Result<List<AudioTrack>> = withContext(Dispatchers.IO) {
        gateway.listAudio(chatId, offset, 50).map { page ->
            val mapped = page.items.map { it.toTrack() }
            tracksState.value = (tracksState.value + mapped).distinctBy { it.id }
            mapped
        }
    }

    private suspend fun TelegramAudio.toTrack(): AudioTrack {
        val stream = gateway.stream(this).getOrThrow()
        val streamUri = stream.localFile?.let { file -> cache?.cache("$chatId-$messageId", file)?.toURI()?.toString() } ?: stream.url
        return AudioTrack(
            id = ("telegram:$chatId:$messageId").hashCode().toLong().and(Long.MAX_VALUE).coerceAtLeast(1L),
            uri = RemoteStreamRegistry.register(streamUri, stream.headers),
            title = title.ifBlank { fileName.substringBeforeLast('.') },
            artist = artist.ifBlank { "Unknown artist" },
            album = album.ifBlank { "Telegram" },
            albumArtist = artist,
            genre = "",
            year = 0,
            trackNumber = 0,
            durationMs = durationMs,
            mimeType = mimeType.ifBlank { "audio/*" },
            codec = mimeType.substringAfter('/', "unknown"),
            bitrate = 0,
            sampleRate = 0,
            artworkUri = thumbnailUri,
            source = "Telegram"
        )
    }

    private suspend fun List<TelegramAudio>.toEntries(): List<SourceEntry> {
        val entries = mutableListOf<SourceEntry>()
        for (audio in this) entries += SourceEntry(audio.messageId.toString(), audio.fileName, false, audio.chatId.toString(), audio.toTrack())
        return entries
    }
}

class TelegramConfiguration(private val context: Context, private val credentials: SecureTokenStore) {
    fun hasSession(): Boolean = !credentials.get("telegram_session") .isNullOrBlank()
    fun storeSession(session: String) = credentials.put("telegram_session", session)
    fun clearSession() = credentials.remove("telegram_session")
}

