package com.example.translation

import android.graphics.Bitmap
import com.example.ai.ClearPathAiRepository
import com.example.ai.InferenceOrigin
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrReadResult(
    val extractedText: String,
    val origin: InferenceOrigin
)

data class TranslationOutput(
    val originalText: String,
    val detectedLanguage: String,
    val translatedText: String,
    val targetLanguage: SupportedLanguage,
    val origin: InferenceOrigin
)

class TranslationManager(
    private val aiRepository: ClearPathAiRepository
) {
    private val latinRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Runs fast on-device ML Kit OCR for offline Latin text reading.
     */
    suspend fun recognizeTextOffline(bitmap: Bitmap): Result<OcrReadResult> {
        return runCatching {
            val image = InputImage.fromBitmap(bitmap, 0)
            val text = suspendCancellableCoroutine { continuation ->
                latinRecognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        if (continuation.isActive) {
                            continuation.resume(visionText.text.trim())
                        }
                    }
                    .addOnFailureListener { err ->
                        if (continuation.isActive) {
                            continuation.resumeWithException(err)
                        }
                    }
            }
            if (text.isBlank()) {
                throw IllegalStateException("No clear printed text detected on-device.")
            }
            OcrReadResult(extractedText = text, origin = InferenceOrigin.OFFLINE_ML_KIT)
        }
    }

    suspend fun translateSpokenOrTypedText(
        input: String,
        sourceLanguage: SupportedLanguage,
        targetLanguage: SupportedLanguage
    ): Result<TranslationOutput> {
        val cleanInput = input.trim()
        if (cleanInput.isEmpty()) {
            return Result.failure(IllegalArgumentException("No text provided to translate."))
        }
        return runCatching {
            val aiResult = aiRepository.translateText(
                sourceText = cleanInput,
                sourceLanguageName = sourceLanguage.displayName,
                targetLanguageName = targetLanguage.displayName
            )
            TranslationOutput(
                originalText = cleanInput,
                detectedLanguage = aiResult.detectedLanguage,
                translatedText = aiResult.translatedText,
                targetLanguage = targetLanguage,
                origin = InferenceOrigin.CLOUD
            )
        }
    }

    suspend fun readAndTranslateFromCamera(
        bitmap: Bitmap,
        targetLanguage: SupportedLanguage
    ): Result<TranslationOutput> {
        return runCatching {
            val aiResult = aiRepository.readAndTranslateImage(
                bitmap = bitmap,
                targetLanguageName = targetLanguage.displayName
            )
            TranslationOutput(
                originalText = aiResult.originalText,
                detectedLanguage = aiResult.detectedLanguage,
                translatedText = aiResult.translatedText,
                targetLanguage = targetLanguage,
                origin = InferenceOrigin.CLOUD
            )
        }
    }
}
