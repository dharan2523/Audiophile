package com.audiophile.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface LyricLine { val text: String; data class Plain(override val text: String) : LyricLine; data class Synced(val atMs: Long, override val text: String) : LyricLine }

object LyricsParser {
    private val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]\\s*(.*)")

    suspend fun parse(raw: String): List<LyricLine> = withContext(Dispatchers.Default) {
        val lines = raw.lineSequence().mapNotNull { line ->
            timestamp.matchEntire(line)?.let { match ->
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].toLong()
                val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                LyricLine.Synced((minutes * 60 + seconds) * 1000 + fraction, match.groupValues[4])
            } ?: line.takeIf { it.isNotBlank() }?.let(LyricLine::Plain)
        }.toList()
        lines.sortedBy { (it as? LyricLine.Synced)?.atMs ?: Long.MIN_VALUE }
    }
}
