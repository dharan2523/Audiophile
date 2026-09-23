package com.audiophile.domain

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TelegramMediaCache(context: Context, private val maxBytes: Long = 256L * 1024L * 1024L) {
    private val directory = File(context.cacheDir, "telegram-media").apply { mkdirs() }

    suspend fun cache(key: String, source: File): File = withContext(Dispatchers.IO) {
        val target = File(directory, key.hashCode().toString())
        if (!target.exists() || target.length() != source.length()) source.copyTo(target, overwrite = true)
        target.setLastModified(System.currentTimeMillis())
        prune()
        target
    }

    private fun prune() {
        var size = directory.listFiles()?.sumOf { it.length() } ?: 0L
        if (size <= maxBytes) return
        directory.listFiles()?.sortedBy { it.lastModified() }?.forEach { file ->
            if (size > maxBytes) {
                size -= file.length()
                file.delete()
            }
        }
    }
}
