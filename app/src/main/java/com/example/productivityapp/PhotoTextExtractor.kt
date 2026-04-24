package com.example.productivityapp

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object PhotoTextExtractor {
    sealed class Result {
        data class Success(val text: String) : Result()
        data class Failure(val message: String) : Result()
    }

    private const val MAX_CHARS = 12_000

    fun extractText(context: Context, uri: Uri): Result {
        return try {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri).orEmpty()
            if (mime.isNotBlank() && !mime.startsWith("image/")) {
                return Result.Failure("Selected file is not an image ($mime).")
            }

            resolver.openInputStream(uri)?.use { stream ->
                val bmp = BitmapFactory.decodeStream(stream)
                    ?: return Result.Failure("Could not decode the image.")
                val image = InputImage.fromBitmap(bmp, 0)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val visionText = Tasks.await(recognizer.process(image))
                val raw = visionText.text.trim()
                if (raw.isEmpty()) {
                    Result.Failure("No readable text found in the photo.")
                } else {
                    Result.Success(raw.take(MAX_CHARS))
                }
            } ?: Result.Failure("Could not open the photo.")
        } catch (e: Exception) {
            Result.Failure(e.message ?: "Failed to read photo.")
        }
    }
}
