package com.pocketagent.android.runtime.modelmanager.gguf

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Interface for reading GGUF metadata from model files.
 * Use `GgufMetadataReader.create()` to get an instance.
 */
interface GgufMetadataReader {
    suspend fun ensureSourceFileFormat(file: File): Boolean
    suspend fun ensureSourceFileFormat(context: Context, uri: Uri): Boolean
    fun readStructuredMetadata(input: InputStream): GgufMetadata

    companion object {
        private val DEFAULT_SKIP_KEYS = setOf(
            "tokenizer.chat_template",
            "tokenizer.ggml.scores",
            "tokenizer.ggml.tokens",
            "tokenizer.ggml.token_type"
        )

        fun create(): GgufMetadataReader = GgufMetadataReaderImpl(
            skipKeys = DEFAULT_SKIP_KEYS,
            arraySummariseThreshold = 1_000
        )

        fun create(
            skipKeys: Set<String> = DEFAULT_SKIP_KEYS,
            arraySummariseThreshold: Int = 1_000
        ): GgufMetadataReader = GgufMetadataReaderImpl(
            skipKeys = skipKeys,
            arraySummariseThreshold = arraySummariseThreshold
        )
    }
}

class InvalidFileFormatException : IOException()
