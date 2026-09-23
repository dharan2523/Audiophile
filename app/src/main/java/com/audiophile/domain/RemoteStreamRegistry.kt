package com.audiophile.domain

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class RemoteStream(val url: String, val headers: Map<String, String>, val createdAtMs: Long = System.currentTimeMillis())

object RemoteStreamRegistry {
    private val streams = ConcurrentHashMap<String, RemoteStream>()

    fun register(url: String, headers: Map<String, String> = emptyMap()): String {
        val reference = "audiophile://stream/${UUID.randomUUID()}"
        streams[reference] = RemoteStream(url, headers)
        if (streams.size > 2048) {
            val cutoff = System.currentTimeMillis() - 6 * 60 * 60 * 1000L
            streams.entries.removeIf { it.value.createdAtMs < cutoff }
        }
        return reference
    }

    fun resolve(reference: String): RemoteStream? = streams[reference]?.takeIf { System.currentTimeMillis() - it.createdAtMs < 24 * 60 * 60 * 1000L }
    fun remove(reference: String) { streams.remove(reference) }
}
