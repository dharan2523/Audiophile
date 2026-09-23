package com.audiophile.domain

import android.content.Context
import com.audiophile.security.SecureTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.lang.reflect.Proxy
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TdLibGateway(
    private val context: Context,
    private val apiId: Int,
    private val apiHash: String,
    private val credentials: SecureTokenStore
) : TelegramGateway {
    private val state = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Disconnected)
    private var client: Any? = null
    private var initialized = false
    override val authState: Flow<TelegramAuthState> = state

    override suspend fun start(): Result<TelegramAuthState> = runCatching {
        ensureClient()
        if (!initialized) {
            val databaseDirectory = File(context.noBackupFilesDir, "telegram-tdlib").apply { mkdirs() }
            send("SetTdlibParameters", mapOf(
                "databaseDirectory" to databaseDirectory.absolutePath,
                "useMessageDatabase" to true,
                "useSecretChats" to false,
                "apiId" to apiId,
                "apiHash" to apiHash,
                "systemLanguageCode" to "en",
                "deviceModel" to "Audiophile Android",
                "applicationVersion" to "1.0",
                "enableStorageOptimizer" to true,
                "useTestDc" to false
            ))
            send("CheckDatabaseEncryptionKey", mapOf("newEncryptionKey" to encryptionKey()))
            initialized = true
        }
        state.value
    }

    override suspend fun submitPhone(phoneNumber: String): Result<TelegramAuthState> = command("SetAuthenticationPhoneNumber", mapOf("phoneNumber" to phoneNumber, "settings" to newObject("PhoneNumberAuthenticationSettings")))
    override suspend fun submitCode(code: String): Result<TelegramAuthState> = command("CheckAuthenticationCode", mapOf("code" to code))
    override suspend fun submitPassword(password: String): Result<TelegramAuthState> = command("CheckAuthenticationPassword", mapOf("password" to password))

    override suspend fun logout(): Result<Unit> = runCatching {
        if (client != null) send("LogOut", emptyMap())
        initialized = false
        client = null
        credentials.remove("telegram_session")
        state.value = TelegramAuthState.Disconnected
    }

    override suspend fun listChats(offset: Long?, limit: Int): Result<TelegramPage<TelegramChat>> = runCatching {
        ensureReady()
        val chatList = newObject("ChatListMain")
        val result = send("GetChats", mapOf("chatList" to chatList, "limit" to limit, "offsetChatId" to (offset ?: 0L), "offsetOrder" to Long.MAX_VALUE))
        val ids = longArray(result ?: error("TDLib returned no chat list"), "chatIds")
        val chats = mutableListOf<TelegramChat>()
        for (id in ids) runCatching { chat(id) }.getOrNull()?.let(chats::add)
        TelegramPage(chats, ids.lastOrNull(), ids.size >= limit)
    }

    override suspend fun listAudio(chatId: Long, fromMessageId: Long?, limit: Int): Result<TelegramPage<TelegramAudio>> = runCatching {
        ensureReady()
        val result = send("GetChatHistory", mapOf("chatId" to chatId, "fromMessageId" to (fromMessageId ?: 0L), "offset" to 0, "limit" to limit, "onlyLocal" to false))
        val messages = objectArray(result ?: error("TDLib returned no chat history"), "messages")
        val audio = messages.mapNotNull { message(it) }
        TelegramPage(audio, messages.lastOrNull()?.let { longField(it, "id") }, messages.size >= limit)
    }

    override suspend fun search(query: String, fromMessageId: Long?, limit: Int): Result<TelegramPage<TelegramAudio>> = runCatching {
        ensureReady()
        val result = send("SearchMessages", mapOf("chatList" to newObject("ChatListMain"), "query" to query, "fromChatId" to 0L, "savedMessagesTopicId" to 0L, "fromMessageId" to (fromMessageId ?: 0L), "offset" to 0, "limit" to limit, "filter" to newObject("SearchMessagesFilterAudio")))
        val messages = objectArray(result ?: error("TDLib returned no search result"), "messages")
        val audio = messages.mapNotNull { message(it) }
        TelegramPage(audio, messages.lastOrNull()?.let { longField(it, "id") }, messages.size >= limit)
    }

    override suspend fun stream(audio: TelegramAudio): Result<TelegramStream> = runCatching {
        ensureReady()
        val file = send("DownloadFile", mapOf("fileId" to audio.fileId, "priority" to 32, "offset" to 0L, "limit" to 0L, "synchronous" to true)) ?: error("TDLib returned no downloaded file")
        val local = objectField(file, "local") ?: error("Telegram media path unavailable")
        val path = stringField(local, "path")
        TelegramStream(path, localFile = File(path))
    }

    private suspend fun command(name: String, fields: Map<String, Any?>): Result<TelegramAuthState> = runCatching { ensureClient(); send(name, fields); state.value }

    private suspend fun chat(id: Long): TelegramChat {
        val result = send("GetChat", mapOf("chatId" to id)) ?: error("TDLib returned no chat")
        val type = objectField(result, "type")?.javaClass?.simpleName.orEmpty()
        return TelegramChat(id, stringField(result, "title"), type.contains("Channel", true), id == 0L)
    }

    private fun message(value: Any): TelegramAudio? {
        val content = objectField(value, "content") ?: return null
        val type = content.javaClass.simpleName
        val media = when {
            type.contains("MessageAudio") -> objectField(content, "audio")
            type.contains("MessageDocument") -> objectField(content, "document")?.takeIf { stringField(it, "mimeType").startsWith("audio/") }
            else -> null
        } ?: return null
        val file = objectField(media, "audio") ?: objectField(media, "document") ?: objectField(media, "file") ?: return null
        val fileName = stringField(media, "fileName").ifBlank { stringField(file, "fileName") }
        val duration = longField(media, "duration")
        return TelegramAudio(longField(value, "chatId"), longField(value, "id"), intField(file, "id"), fileName, stringField(media, "title"), stringField(media, "performer"), stringField(media, "albumTitle"), duration * 1000L, stringField(media, "mimeType").ifBlank { "audio/*" }, longField(file, "size"), longField(value, "date") * 1000L)
    }

    private suspend fun ensureReady() { ensureClient(); if (state.value !is TelegramAuthState.Ready) error("Telegram authentication required") }

    private suspend fun ensureClient() {
        if (client != null) return
        val clientClass = Class.forName("org.drinkless.tdlib.Client")
        val resultHandlerClass = Class.forName("org.drinkless.tdlib.Client\$ResultHandler")
        val exceptionHandlerClass = Class.forName("org.drinkless.tdlib.Client\$ExceptionHandler")
        val updateHandler = Proxy.newProxyInstance(resultHandlerClass.classLoader, arrayOf(resultHandlerClass)) { _, method, args -> if (method.name == "onResult") args?.firstOrNull()?.let(::handleUpdate); null }
        val exceptionHandler = Proxy.newProxyInstance(exceptionHandlerClass.classLoader, arrayOf(exceptionHandlerClass)) { _, _, _ -> null }
        val create = clientClass.methods.first { it.name == "create" && it.parameterTypes.size == 3 }
        client = create.invoke(null, updateHandler, exceptionHandler, exceptionHandler)
    }

    private suspend fun send(name: String, fields: Map<String, Any?>): Any? = suspendCancellableCoroutine { continuation ->
        try {
            val current = client ?: error("TDLib client is not initialized")
            val function = newObject(name)
            fields.forEach { (field, value) -> setField(function, field, value) }
            val resultHandlerClass = Class.forName("org.drinkless.tdlib.Client\$ResultHandler")
            val handler = Proxy.newProxyInstance(resultHandlerClass.classLoader, arrayOf(resultHandlerClass)) { _, method, args ->
                if (method.name == "onResult" && continuation.isActive) continuation.resume(args?.firstOrNull())
                null
            }
            val send = current.javaClass.methods.first { it.name == "send" && it.parameterTypes.size == 2 }
            send.invoke(current, function, handler)
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(error.cause ?: error)
        }
    }

    private fun handleUpdate(value: Any) {
        if (value.javaClass.simpleName != "UpdateAuthorizationState") return
        val authorization = objectField(value, "authorizationState") ?: return
        state.value = when (authorization.javaClass.simpleName) {
            "AuthorizationStateWaitPhoneNumber" -> TelegramAuthState.WaitingForPhone
            "AuthorizationStateWaitCode" -> TelegramAuthState.WaitingForCode
            "AuthorizationStateWaitPassword" -> TelegramAuthState.WaitingForPassword
            "AuthorizationStateReady" -> TelegramAuthState.Ready
            "AuthorizationStateClosed" -> TelegramAuthState.Disconnected
            else -> state.value
        }
    }

    private fun encryptionKey(): ByteArray {
        val stored = credentials.get("telegram_database_key")
        if (stored != null) return Base64.getDecoder().decode(stored)
        return ByteArray(32).also { java.security.SecureRandom().nextBytes(it); credentials.put("telegram_database_key", Base64.getEncoder().encodeToString(it)) }
    }

    private fun newObject(name: String): Any = Class.forName("org.drinkless.tdlib.TdApi\$$name").getDeclaredConstructor().newInstance()
    private fun setField(target: Any, name: String, value: Any?) { runCatching { target.javaClass.getField(name).set(target, value) } }
    private fun objectField(target: Any, name: String): Any? = runCatching { target.javaClass.getField(name).get(target) }.getOrNull()
    private fun stringField(target: Any, name: String): String = objectField(target, name)?.toString().orEmpty()
    private fun longField(target: Any, name: String): Long = (objectField(target, name) as? Number)?.toLong() ?: 0L
    private fun intField(target: Any, name: String): Int = (objectField(target, name) as? Number)?.toInt() ?: 0
    private fun longArray(target: Any, name: String): LongArray = objectField(target, name) as? LongArray ?: LongArray(0)
    private fun objectArray(target: Any, name: String): List<Any> = (objectField(target, name) as? Array<*>)?.filterNotNull() ?: emptyList()
}
