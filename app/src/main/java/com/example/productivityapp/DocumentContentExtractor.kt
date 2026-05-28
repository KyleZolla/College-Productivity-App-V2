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
import java.util.zip.ZipInputStream

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

        val looksDocx = mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
            mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.template" ||
            name.lowercase().endsWith(".docx")

        // Best-effort: support text-ish files + PDF (via PdfBox-Android) + Word (.docx).
        val looksText = mime.startsWith("text/") ||
            mime == "application/json" ||
            mime == "application/xml" ||
            mime == "application/xhtml+xml" ||
            mime == "application/csv" ||
            mime == "application/x-csv" ||
            mime == "text/csv"

        if (!looksText && !looksPdf && !looksDocx) {
            return Result.Failure(
                "Unsupported document type ($mime). Try PDF, Word (.docx), or a text-based file (txt/csv/json). File: $name",
            )
        }

        return try {
            resolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_BYTES) {
                    return Result.Failure("Document is too large (${bytes.size} bytes). Max is ${MAX_BYTES} bytes. File: $name")
                }

                when {
                    looksPdf -> extractPdfText(context, bytes, name)
                    looksDocx -> extractDocxText(bytes, name)
                    else -> {
                        val text = bytes.toString(Charset.forName("UTF-8"))
                        val trimmed = text.trim()
                        if (trimmed.isEmpty()) {
                            Result.Failure("Document was empty. File: $name")
                        } else {
                            Result.Success(trimmed.take(MAX_CHARS))
                        }
                    }
                }
            } ?: Result.Failure("Could not open the document.")
        } catch (e: Exception) {
            Result.Failure(e.message ?: "Failed to read document.")
        }
    }

    private fun extractDocxText(bytes: ByteArray, name: String): Result {
        return try {
            val documentXml = readZipEntryText(bytes, "word/document.xml")
                ?: return Result.Failure("Could not read Word document structure. File: $name")
            val text = extractWordDocumentXmlText(documentXml)
            if (text.isBlank()) {
                Result.Failure("Could not extract text from Word document. File: $name")
            } else {
                Result.Success(text.take(MAX_CHARS))
            }
        } catch (e: Exception) {
            Result.Failure("Failed to read Word document: ${e.message ?: "unknown error"}. File: $name")
        }
    }

    /** Pull plain text from WordprocessingML (`word/document.xml` inside a .docx zip). */
    internal fun extractWordDocumentXmlText(xml: String): String {
        val paragraphPattern = Regex("<w:p[\\s>][\\s\\S]*?</w:p>")
        val textRunPattern = Regex("<w:t(?:\\s[^>]*)?>([^<]*)</w:t>")
        val lines = paragraphPattern.findAll(xml).map { match ->
            textRunPattern.findAll(match.value).joinToString("") { it.groupValues[1] }
        }.filter { it.isNotEmpty() }
        return lines.joinToString("\n").trim()
    }

    private fun readZipEntryText(bytes: ByteArray, entryPath: String): String? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == entryPath) {
                    return zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return null
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

