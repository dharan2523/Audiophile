package com.audiophile.domain

import android.net.Uri
import com.audiophile.data.AudioTrack
import com.audiophile.security.SecureTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.nio.charset.StandardCharsets

private const val REMOTE_PAGE_SIZE = 1000

abstract class RemoteMusicSource(
    private val client: OkHttpClient,
    protected val credentials: SecureTokenStore,
    private val refreshAccessToken: (suspend () -> Boolean)? = null
) : BrowseableMusicSource {
    private val tracksState = MutableStateFlow<List<AudioTrack>>(emptyList())
    override fun observeTracks(): Flow<List<AudioTrack>> = tracksState

    final override suspend fun connect(): Result<Unit> = runCatching {
        if (requiresCredential && sourceCredential().isNullOrBlank()) error("Authentication is required")
        val loaded = withContext(Dispatchers.IO) { loadTracks() }
        tracksState.value = loaded
    }

    override suspend fun disconnect() { tracksState.value = emptyList() }
    override suspend fun browse(containerId: String?): Result<SourcePage> = Result.success(SourcePage(tracksState.value.map { SourceEntry(it.id.toString(), it.title, false, containerId, it) }))
    override suspend fun searchRemote(query: String): Result<SourcePage> = Result.success(SourcePage(tracksState.value.filter { track -> track.title.contains(query, true) || track.artist.contains(query, true) || track.album.contains(query, true) }.map { SourceEntry(it.id.toString(), it.title, false, null, it) }))
    protected abstract suspend fun loadTracks(): List<AudioTrack>
    protected abstract fun sourceCredential(): String?
    protected open val requiresCredential: Boolean = true
    protected open val credentialKey: String? = null

    protected suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String = request("GET", url, headers)

    protected suspend fun request(method: String, url: String, headers: Map<String, String> = emptyMap(), body: String? = null): String = withContext(Dispatchers.IO) {
        val requestBody = body?.toRequestBody("application/json; charset=utf-8".toMediaType()) ?: "".toRequestBody("application/json; charset=utf-8".toMediaType())
        var attempt = 0
        var credential = sourceCredential()
        val requestHeaders = if (attempt > 0 && "Authorization" in headers && credential != null) headers + ("Authorization" to "Bearer $credential") else headers
        val retryBuilder = Request.Builder().url(url)
        requestHeaders.forEach { (name, value) -> retryBuilder.header(name, value) }
        var response = client.newCall(retryBuilder.method(method, requestBody).build()).execute()
        while (response.code == 401 && attempt == 0 && refreshAccessToken != null && refreshAccessToken()) {
            attempt++
            credential = sourceCredential()
            val retryHeaders = if ("Authorization" in headers && credential != null) headers + ("Authorization" to "Bearer $credential") else headers
            val retryBuilder2 = Request.Builder().url(url)
            retryHeaders.forEach { (name, value) -> retryBuilder2.header(name, value) }
            response.close()
            response = client.newCall(retryBuilder2.method(method, requestBody).build()).execute()
        }
        if (response.code == 401) {
            credentialKey?.let { key -> credentials.remove(key); credentials.remove(key.replace("_access_token", "_refresh_token")) }
            error("Authentication expired")
        }
        if (!response.isSuccessful) error("${response.code}: remote source request failed")
        val responseBody = response.body?.use { it.string() }.orEmpty()
        response.close()
        return@withContext responseBody
    }

    protected fun bearer(): Map<String, String> = sourceCredential()?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

    protected fun track(remoteId: String, title: String, artist: String, album: String, streamUri: String, artworkUri: String? = null, durationMs: Long = 0L, mimeType: String = "audio/*", sourceName: String): AudioTrack = AudioTrack(
        id = ("$id:$remoteId").hashCode().toLong().and(Long.MAX_VALUE).coerceAtLeast(1L),
        uri = streamUri,
        title = title.ifBlank { "Untitled track" },
        artist = artist.ifBlank { "Unknown artist" },
        album = album.ifBlank { "Unknown album" },
        albumArtist = artist,
        genre = "",
        year = 0,
        trackNumber = 0,
        durationMs = durationMs,
        mimeType = mimeType,
        codec = mimeType.substringAfter('/', "unknown"),
        bitrate = 0,
        sampleRate = 0,
        artworkUri = artworkUri,
        source = sourceName
    )
}

class GoogleDriveSource(
    private val client: OkHttpClient,
    credentials: SecureTokenStore,
    refreshAccessToken: (suspend () -> Boolean)? = null
) : RemoteMusicSource(client, credentials, refreshAccessToken) {
    override val id = "drive"
    override val displayName = "Google Drive"
    override fun sourceCredential(): String? = credentials.get("google_drive_access_token")
    override val credentialKey = "google_drive_access_token"

    override suspend fun loadTracks(): List<AudioTrack> {
        val tracks = mutableListOf<AudioTrack>()
        var pageToken: String? = null
        do {
            val tokenQuery = pageToken?.let { "&pageToken=${Uri.encode(it)}" }.orEmpty()
            val json = get("https://www.googleapis.com/drive/v3/files?q=trashed%3Dfalse%20and%20mimeType%20contains%20'audio%2F'&pageSize=$REMOTE_PAGE_SIZE&fields=nextPageToken,files(id,name,mimeType,size,thumbnailLink,modifiedTime)$tokenQuery", bearer())
            val payload = JSONObject(json)
            val files = payload.optJSONArray("files") ?: JSONArray()
            (0 until files.length()).forEach { index ->
                val file = files.getJSONObject(index)
                val id = file.getString("id")
                tracks += track(id, file.optString("name"), "", "Google Drive", RemoteStreamRegistry.register("https://www.googleapis.com/drive/v3/files/$id?alt=media", bearer()), file.optString("thumbnailLink").ifBlank { null }, sourceName = displayName, mimeType = file.optString("mimeType", "audio/*"))
            }
            pageToken = payload.optString("nextPageToken").ifBlank { null }
        } while (pageToken != null)
        return tracks
    }

    override suspend fun browse(containerId: String?): Result<SourcePage> = runCatching {
        val parent = containerId ?: "root"
        val json = get("https://www.googleapis.com/drive/v3/files?q='${Uri.encode(parent)}'%20in%20parents%20and%20trashed%3Dfalse&pageSize=$REMOTE_PAGE_SIZE&fields=files(id,name,mimeType,thumbnailLink)", bearer())
        val files = JSONObject(json).optJSONArray("files") ?: JSONArray()
        SourcePage((0 until files.length()).map { index -> driveEntry(files.getJSONObject(index), parent) })
    }

    override suspend fun searchRemote(query: String): Result<SourcePage> = runCatching {
        val json = get("https://www.googleapis.com/drive/v3/files?q=trashed%3Dfalse%20and%20name%20contains%20'${Uri.encode(query)}'&pageSize=$REMOTE_PAGE_SIZE&fields=files(id,name,mimeType,thumbnailLink)", bearer())
        val files = JSONObject(json).optJSONArray("files") ?: JSONArray()
        SourcePage((0 until files.length()).map { index -> driveEntry(files.getJSONObject(index), null) })
    }

    private fun driveEntry(file: JSONObject, parent: String?): SourceEntry {
        val id = file.getString("id")
        val name = file.optString("name")
        if (file.optString("mimeType") == "application/vnd.google-apps.folder") return SourceEntry(id, name, true, parent)
        val track = track(id, name, "", "Google Drive", RemoteStreamRegistry.register("https://www.googleapis.com/drive/v3/files/$id?alt=media", bearer()), file.optString("thumbnailLink").ifBlank { null }, sourceName = displayName, mimeType = file.optString("mimeType", "audio/*"))
        return SourceEntry(id, name, false, parent, track)
    }
}

class DropboxSource(
    private val client: OkHttpClient,
    credentials: SecureTokenStore,
    refreshAccessToken: (suspend () -> Boolean)? = null
) : RemoteMusicSource(client, credentials, refreshAccessToken) {
    override val id = "dropbox"
    override val displayName = "Dropbox"
    override fun sourceCredential(): String? = credentials.get("dropbox_access_token")
    override val credentialKey = "dropbox_access_token"

    override suspend fun loadTracks(): List<AudioTrack> {
        val tracks = mutableListOf<AudioTrack>()
        var response = request("POST", "https://api.dropboxapi.com/2/files/list_folder", bearer() + ("Content-Type" to "application/json"), "{\"path\":\"\",\"recursive\":true,\"include_media_info\":true,\"limit\":2000}")
        var hasMore: Boolean
        do {
            val payload = JSONObject(response)
            hasMore = payload.optBoolean("has_more")
            val entries = payload.optJSONArray("entries") ?: JSONArray()
            (0 until entries.length()).forEach { index ->
                val entry = entries.getJSONObject(index)
                if (entry.optString(".tag") == "file" && isAudio(entry.optString("name"))) {
                    val path = entry.optString("path_lower")
                    val link = request("POST", "https://api.dropboxapi.com/2/files/get_temporary_link", bearer() + ("Content-Type" to "application/json"), JSONObject().put("path", path).toString())
                    val media = entry.optJSONObject("media_metadata")
                    tracks += track(entry.optString("id"), entry.optString("name").substringBeforeLast('.'), media?.optString("artist").orEmpty(), media?.optString("album").orEmpty(), JSONObject(link).optString("link"), sourceName = displayName, mimeType = mimeFor(entry.optString("name")))
                }
            }
            if (hasMore) {
                response = request("POST", "https://api.dropboxapi.com/2/files/list_folder/continue", bearer() + ("Content-Type" to "application/json"), JSONObject().put("cursor", payload.optString("cursor")).toString())
            }
        } while (hasMore)
        return tracks
    }

    override suspend fun browse(containerId: String?): Result<SourcePage> = runCatching {
        val path = containerId ?: ""
        val response = request("POST", "https://api.dropboxapi.com/2/files/list_folder", bearer() + ("Content-Type" to "application/json"), JSONObject().put("path", path).put("recursive", false).put("include_media_info", true).toString())
        val entries = JSONObject(response).optJSONArray("entries") ?: JSONArray()
        val mapped = mutableListOf<SourceEntry>()
        for (index in 0 until entries.length()) dropboxEntry(entries.getJSONObject(index), path)?.let(mapped::add)
        SourcePage(mapped)
    }

    override suspend fun searchRemote(query: String): Result<SourcePage> = runCatching {
        val response = request("POST", "https://api.dropboxapi.com/2/files/search_v2", bearer() + ("Content-Type" to "application/json"), JSONObject().put("query", query).put("options", JSONObject().put("max_results", REMOTE_PAGE_SIZE)).toString())
        val matches = JSONObject(response).optJSONObject("matches")?.optJSONArray("entries") ?: JSONArray()
        val mapped = mutableListOf<SourceEntry>()
        for (index in 0 until matches.length()) matches.getJSONObject(index).optJSONObject("metadata")?.let { dropboxEntry(it, null)?.let(mapped::add) }
        SourcePage(mapped)
    }

    private suspend fun dropboxEntry(entry: JSONObject, parent: String?): SourceEntry? {
        val id = entry.optString("id")
        val name = entry.optString("name")
        if (entry.optString(".tag") == "folder") return SourceEntry(id, name, true, parent)
        if (!isAudio(name)) return null
        val path = entry.optString("path_lower")
        val link = request("POST", "https://api.dropboxapi.com/2/files/get_temporary_link", bearer() + ("Content-Type" to "application/json"), JSONObject().put("path", path).toString())
        val media = entry.optJSONObject("media_metadata")
        val track = track(id, name.substringBeforeLast('.'), media?.optString("artist").orEmpty(), media?.optString("album").orEmpty(), JSONObject(link).optString("link"), sourceName = displayName, mimeType = mimeFor(name))
        return SourceEntry(id, name, false, parent, track)
    }
}

class WebDavSource(
    private val client: OkHttpClient,
    credentials: SecureTokenStore,
    private val baseUrl: String,
    private val username: String,
    refreshAccessToken: (suspend () -> Boolean)? = null
) : RemoteMusicSource(client, credentials, refreshAccessToken) {
    override val id = "webdav"
    override val displayName = "WebDAV"
    override fun sourceCredential(): String? = credentials.get("webdav_password")
    override val credentialKey = "webdav_password"

    override suspend fun loadTracks(): List<AudioTrack> {
        val password = sourceCredential() ?: error("WebDAV authentication is required")
        val auth = okhttp3.Credentials.basic(username, password)
        val xml = request("PROPFIND", baseUrl.trimEnd('/') + "/", mapOf("Authorization" to auth, "Depth" to "infinity", "Content-Type" to "application/xml"), "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontenttype/><d:getcontentlength/></d:prop></d:propfind>")
        return Regex("<[^:>]*:response[^>]*>(.*?)</[^:>]*:response>", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(xml).mapNotNull { match ->
            val block = match.groupValues[1]
            val href = Regex("<[^:>]*:href[^>]*>(.*?)</[^:>]*:href>").find(block)?.groupValues?.get(1)?.trim()?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) } ?: return@mapNotNull null
            val name = href.substringAfterLast('/').ifBlank { return@mapNotNull null }
            if (!isAudio(name)) return@mapNotNull null
            val type = Regex("<[^:>]*:getcontenttype[^>]*>(.*?)</[^:>]*:getcontenttype>").find(block)?.groupValues?.get(1).orEmpty()
            track(href, name.substringBeforeLast('.'), "", "WebDAV", RemoteStreamRegistry.register(absoluteUrl(href), mapOf("Authorization" to auth)), sourceName = displayName, mimeType = type.ifBlank { mimeFor(name) })
        }.toList()
    }

    private fun absoluteUrl(path: String): String = if (path.startsWith("http")) path else baseUrl.trimEnd('/') + "/" + path.trimStart('/')
}

class UpnpSource(
    private val client: OkHttpClient,
    credentials: SecureTokenStore,
    private val contentDirectoryUrl: String,
    refreshAccessToken: (suspend () -> Boolean)? = null
) : RemoteMusicSource(client, credentials, refreshAccessToken) {
    override val id = "upnp"
    override val displayName = "UPnP / DLNA"
    override fun sourceCredential(): String? = null
    override val requiresCredential = false

    override suspend fun loadTracks(): List<AudioTrack> {
        val endpoint = contentDirectoryUrl.ifBlank { discoverContentDirectory() ?: error("No UPnP media server found") }
        val soap = """<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\"><ObjectID>0</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>0</StartingIndex><RequestedCount>0</RequestedCount><SortCriteria></SortCriteria></u:Browse></s:Body></s:Envelope>"""
        val response = request("POST", endpoint, mapOf("SOAPACTION" to "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"", "Content-Type" to "text/xml; charset=utf-8"), soap)
        val result = Regex("<res[^>]*>(.*?)</res>", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(response).mapNotNull { match ->
            val stream = match.groupValues[1].trim()
            if (!stream.startsWith("http") || !isAudio(stream)) return@mapNotNull null
            val title = tag(response, "dc:title", match.range.first).ifBlank { stream.substringAfterLast('/') }
            val artist = tag(response, "upnp:artist", match.range.first)
            val album = tag(response, "upnp:album", match.range.first)
            track(stream, title, artist, album, stream, sourceName = displayName, mimeType = mimeFor(stream))
        }.toList()
        return result
    }

    private suspend fun discoverContentDirectory(): String? {
        val request = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n".toByteArray()
        DatagramSocket().use { socket ->
            socket.soTimeout = 1200
            socket.send(DatagramPacket(request, request.size, InetAddress.getByName("239.255.255.250"), 1900))
            val buffer = ByteArray(8192)
            repeat(4) {
                val packet = DatagramPacket(buffer, buffer.size)
                runCatching { socket.receive(packet) }.getOrNull() ?: return@repeat
                val location = packet.data.decodeToString(0, packet.length).lineSequence().firstOrNull { it.startsWith("location:", true) }?.substringAfter(':')?.trim() ?: return@repeat
                val description = runCatching { get(location) }.getOrNull() ?: return@repeat
                val control = Regex("<controlURL>(.*?)</controlURL>", RegexOption.IGNORE_CASE).find(description)?.groupValues?.get(1) ?: return@repeat
                val base = Regex("<URLBase>(.*?)</URLBase>", RegexOption.IGNORE_CASE).find(description)?.groupValues?.get(1)?.trim() ?: location
                return URI(base).resolve(control).toString()
            }
        }
        return null
    }

    private fun tag(xml: String, name: String, offset: Int): String {
        val start = xml.lastIndexOf("<$name", offset)
        val end = xml.indexOf(">", start).takeIf { it >= 0 } ?: return ""
        val close = xml.indexOf("</$name>", end).takeIf { it >= 0 } ?: return ""
        return xml.substring(end + 1, close)
    }
}

class EmbySource(
    private val client: OkHttpClient,
    credentials: SecureTokenStore,
    private val serverUrl: String,
    refreshAccessToken: (suspend () -> Boolean)? = null
) : RemoteMusicSource(client, credentials, refreshAccessToken) {
    override val id = "emby"
    override val displayName = "Emby"
    override fun sourceCredential(): String? = credentials.get("emby_access_token")
    override val credentialKey = "emby_access_token"

    override suspend fun loadTracks(): List<AudioTrack> {
        val token = sourceCredential() ?: error("Emby authentication is required")
        val url = serverUrl.trimEnd('/') + "/Items?Recursive=true&IncludeItemTypes=Audio&Fields=MediaSources,Album,Artists,RunTimeTicks,ProductionYear&api_key=$token"
        val items = JSONObject(get(url)).optJSONArray("Items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.getJSONObject(index)
            val mediaSources = item.optJSONArray("MediaSources") ?: return@mapNotNull null
            val media = mediaSources.optJSONObject(0) ?: return@mapNotNull null
            val stream = media.optString("DirectStreamUrl").ifBlank { serverUrl.trimEnd('/') + "/Audio/${item.optString("Id")}/stream?static=true&api_key=$token" }
            track(item.optString("Id"), item.optString("Name"), item.optJSONArray("Artists")?.optString(0).orEmpty(), item.optString("Album"), RemoteStreamRegistry.register(stream, mapOf("X-Emby-Token" to token)), item.optString("ImageTags").ifBlank { null }?.let { serverUrl.trimEnd('/') + "/Items/${item.optString("Id")}/Images/Primary" }, (item.optLong("RunTimeTicks") / 10_000L), media.optString("Container").let { mimeFor("x.$it") }, displayName)
        }
    }
}

private fun isAudio(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "alac", "aiff", "amr", "wma")
private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) { "mp3" -> "audio/mpeg"; "flac" -> "audio/flac"; "wav" -> "audio/wav"; "m4a", "alac" -> "audio/mp4"; "ogg", "opus" -> "audio/ogg"; else -> "audio/*" }
