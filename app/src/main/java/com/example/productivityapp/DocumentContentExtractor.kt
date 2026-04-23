package com.example.productivityapp

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.nio.charset.Charset

object DocumentContentExtractor {
    sealed class Result {
        data class Success(val content: String) : Result()
        data class Failure(val message: String) : Result()
    }

    private const val MAX_CHARS = 120_000

    fun extractText(context: Context, uri: Uri): Result {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri).orEmpty()
        val name = queryDisplayName(resolver, uri).orEmpty()

        // Best-effort: support text-ish files. PDFs/docx need a real parser (out of scope for now).
        val looksText = mime.startsWith("text/") ||
            mime == "application/json" ||
            mime == "application/xml" ||
            mime == "application/xhtml+xml" ||
            mime == "application/csv" ||
            mime == "application/x-csv" ||
            mime == "text/csv"

        if (!looksText) {
            return Result.Failure("Unsupported document type ($mime). Use a text-based file (txt/csv/json). File: $name")
        }

        return try {
            resolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                val text = bytes.toString(Charset.forName("UTF-8"))
                val trimmed = text.trim()
                if (trimmed.isEmpty()) {
                    Result.Failure("Document was empty. File: $name")
                } else {
                    Result.Success(trimmed.take(MAX_CHARS))
                }
            } ?: Result.Failure("Could not open the document.")
        } catch (e: Exception) {
            Result.Failure(e.message ?: "Failed to read document.")
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }
}

