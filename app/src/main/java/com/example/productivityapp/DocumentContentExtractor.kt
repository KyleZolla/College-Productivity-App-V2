package com.example.productivityapp

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

object DocumentContentExtractor {
    sealed class Result {
        data class Success(val content: String) : Result()
        data class Failure(val message: String) : Result()
    }

    private const val MAX_CHARS = 120_000
    private const val MAX_BYTES = 8 * 1024 * 1024

    @Volatile
    private var pdfBoxInitialized = false

    fun extractText(context: Context, uri: Uri): Result {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri).orEmpty()
        val name = queryDisplayName(resolver, uri).orEmpty()

        val looksPdf = mime == "application/pdf" || name.lowercase().endsWith(".pdf")

        // Best-effort: support text-ish files + PDF (via PdfBox-Android).
        val looksText = mime.startsWith("text/") ||
            mime == "application/json" ||
            mime == "application/xml" ||
            mime == "application/xhtml+xml" ||
            mime == "application/csv" ||
            mime == "application/x-csv" ||
            mime == "text/csv"

        if (!looksText && !looksPdf) {
            return Result.Failure("Unsupported document type ($mime). Try PDF or a text-based file (txt/csv/json). File: $name")
        }

        return try {
            resolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_BYTES) {
                    return Result.Failure("Document is too large (${bytes.size} bytes). Max is ${MAX_BYTES} bytes. File: $name")
                }

                if (looksPdf) {
                    extractPdfText(context, bytes, name)
                } else {
                    val text = bytes.toString(Charset.forName("UTF-8"))
                    val trimmed = text.trim()
                    if (trimmed.isEmpty()) {
                        Result.Failure("Document was empty. File: $name")
                    } else {
                        Result.Success(trimmed.take(MAX_CHARS))
                    }
                }
            } ?: Result.Failure("Could not open the document.")
        } catch (e: Exception) {
            Result.Failure(e.message ?: "Failed to read document.")
        }
    }

    private fun extractPdfText(context: Context, bytes: ByteArray, name: String): Result {
        return try {
            ensurePdfBoxInitialized(context)
            PDDocument.load(ByteArrayInputStream(bytes)).use { doc ->
                val stripper = PDFTextStripper()
                val text = stripper.getText(doc).trim()
                if (text.isEmpty()) {
                    Result.Failure("Could not extract text from PDF (it may be scanned images). File: $name")
                } else {
                    Result.Success(text.take(MAX_CHARS))
                }
            }
        } catch (e: Exception) {
            Result.Failure("Failed to read PDF: ${e.message ?: "unknown error"}. File: $name")
        }
    }

    private fun ensurePdfBoxInitialized(context: Context) {
        if (pdfBoxInitialized) return
        synchronized(this) {
            if (pdfBoxInitialized) return
            PDFBoxResourceLoader.init(context.applicationContext)
            pdfBoxInitialized = true
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

