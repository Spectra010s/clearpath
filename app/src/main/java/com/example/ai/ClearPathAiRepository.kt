package com.example.ai

import android.graphics.Bitmap
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.Chat
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private const val TAG = "ClearPathAiRepo"
private const val CLOUD_MODEL_NAME = "gemini-3.8-flash"

enum class InferenceOrigin(val badgeLabel: String) {
    ON_DEVICE("On-Device (Gemini Nano)"),
    CLOUD("Cloud (Gemini 3.8 Flash)"),
    OFFLINE_ML_KIT("Offline (ML Kit OCR)")
}

data class AiStreamChunk(
    val partialText: String,
    val isFinal: Boolean,
    val origin: InferenceOrigin
)

@OptIn(PublicPreviewAPI::class)
class ClearPathAiRepository {

    private val systemPrompt = content {
        text(
            """
            You are ClearPath, a voice-first AI assistive companion for a blind or visually impaired person.
            Follow these safety and clarity rules strictly:
            1. Keep responses concise (2 to 4 short sentences) unless the user asks for full detail or text reading.
            2. Clearly distinguish what is directly visible in the camera image from what is uncertain or inferred.
            3. NEVER invent exact distances in meters or feet because the phone camera has no depth sensor. Use qualitative terms like "directly ahead", "to your left", "close by", or say "distance cannot be measured reliably".
            4. NEVER invent obstacles, people, doors, or traffic conditions that are not clearly visible.
            5. NEVER claim it is safe to cross a road or proceed without a cane/guide dog/mobility check.
            6. If lighting is poor, blurry, shadowed, or partially occluded, say so honestly first.
            7. Write natural spoken language suitable for Text-to-Speech: no markdown bullets, asterisks, or emojis.
            8. You can control ClearPath directly via tools—NEVER tell a blind user to switch tabs or tap settings menus when you can inspect or change a setting or run a tool for them.
            """.trimIndent()
        )
    }

    private val clearPathTools by lazy {
        val listAvailableSettingsTool = FunctionDeclaration(
            "list_available_settings",
            "List all available ClearPath settings, their current values, allowed options, and plain-language descriptions.",
            emptyMap()
        )
        val updateSettingTool = FunctionDeclaration(
            "update_setting",
            "Update or toggle a ClearPath setting requested by the user.",
            mapOf(
                "setting_name" to Schema.enumeration(
                    values = listOf(
                        "speech_rate",
                        "continuous_conversation",
                        "scene_watch",
                        "haptics",
                        "prefer_on_device_ai",
                        "show_camera_preview",
                        "wake_phrase_mode",
                        "auto_listen_on_open",
                        "translation_source_language",
                        "translation_target_language",
                        "routing_mode",
                        "preferred_voice"
                    ),
                    description = "The setting identifier to modify."
                ),
                "boolean_value" to Schema.boolean(
                    "New on/off value for boolean settings (true = on, false = off)."
                ),
                "number_value" to Schema.double(
                    "New numeric value, e.g. speech_rate between 0.6 and 1.8."
                ),
                "string_value" to Schema.string(
                    "New string value (e.g. language code/name, routing mode, voice name/locale, or 'toggle' to flip a boolean setting)."
                )
            ),
            optionalParameters = listOf("boolean_value", "number_value", "string_value")
        )
        val listVoicesTool = FunctionDeclaration(
            "list_available_voices",
            "List available Text-to-Speech voices on the user's phone and show which voice is active.",
            emptyMap()
        )
        val describeCameraSceneTool = FunctionDeclaration(
            "describe_camera_scene",
            "Capture a live frame from the back camera and describe surroundings, obstacles, people, doors, or objects.",
            mapOf(
                "question" to Schema.string("The user's visual question about what is ahead or nearby.")
            )
        )
        val readCameraTextTool = FunctionDeclaration(
            "read_camera_text",
            "Capture a frame from the camera and read visible printed or handwritten text, optionally translating it.",
            mapOf(
                "target_language" to Schema.string("Optional target language code or name such as en, yo, ig, ha, pcm, fr, ar, es.")
            ),
            optionalParameters = listOf("target_language")
        )
        val getCurrentLocationTool = FunctionDeclaration(
            "get_current_location",
            "Check the user's current GPS street address and compass facing direction.",
            emptyMap()
        )
        val startWalkingGuidanceTool = FunctionDeclaration(
            "start_walking_guidance",
            "Search for a destination or nearby place and begin spoken ClearPath walking/compass guidance.",
            mapOf(
                "destination" to Schema.string("Destination name, address, or nearby category such as pharmacy.")
            )
        )
        val openPhoneMapsTool = FunctionDeclaration(
            "open_phone_maps",
            "Open walking directions in the user's installed Maps app.",
            mapOf(
                "destination" to Schema.string("Optional destination name or address.")
            ),
            optionalParameters = listOf("destination")
        )
        val getNavigationStatusTool = FunctionDeclaration(
            "get_navigation_status",
            "Check active walking route or compass bearing guidance status.",
            emptyMap()
        )
        val stopNavigationTool = FunctionDeclaration(
            "stop_navigation",
            "Stop active walking guidance.",
            emptyMap()
        )
        val translateTextTool = FunctionDeclaration(
            "translate_text",
            "Translate spoken or typed text into a requested target language.",
            mapOf(
                "text" to Schema.string("Text to translate."),
                "target_language" to Schema.string("Target language code or name.")
            )
        )
        Tool.functionDeclarations(
            listOf(
                listAvailableSettingsTool,
                updateSettingTool,
                listVoicesTool,
                describeCameraSceneTool,
                readCameraTextTool,
                getCurrentLocationTool,
                startWalkingGuidanceTool,
                openPhoneMapsTool,
                getNavigationStatusTool,
                stopNavigationTool,
                translateTextTool
            )
        )
    }

    private val cloudChatModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = CLOUD_MODEL_NAME,
            generationConfig = generationConfig {
                temperature = 0.25f
                maxOutputTokens = 320
            },
            systemInstruction = systemPrompt,
            tools = listOf(clearPathTools)
        )
    }

    private val hybridSingleTurnModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = CLOUD_MODEL_NAME,
            generationConfig = generationConfig {
                temperature = 0.2f
                maxOutputTokens = 256
            },
            systemInstruction = systemPrompt,
            onDeviceConfig = OnDeviceConfig(mode = InferenceMode.PREFER_ON_DEVICE)
        )
    }

    private val translationModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = CLOUD_MODEL_NAME,
            generationConfig = generationConfig {
                temperature = 0.1f
                maxOutputTokens = 350
            },
            systemInstruction = content {
                text(
                    """
                    You are ClearPath's multilingual assistive translator supporting English, Yoruba, Igbo, Hausa, Nigerian Pidgin, French, Spanish, and Arabic.
                    Respond only with clear spoken-friendly text, no markdown symbols, and explicitly note if any source text was partially illegible.
                    """.trimIndent()
                )
            }
        )
    }

    private var activeChat: Chat? = null

    fun resetConversation() {
        activeChat = null
    }

    private fun ensureChat(): Chat {
        return activeChat ?: cloudChatModel.startChat().also { activeChat = it }
    }

    /**
     * Sends a voice/text request to the Gemini tool-enabled assistant chat, executing any
     * requested app/settings/camera/navigation tools and returning the spoken response.
     */
    suspend fun askAssistantWithTools(
        userMessage: String,
        toolHandler: suspend (name: String, args: Map<String, JsonElement>) -> JsonObject
    ): String {
        try {
            return withTimeout(60_000L) {
                val chat = ensureChat()
                var response = chat.sendMessage(userMessage)
                var rounds = 0
                while (response.functionCalls.isNotEmpty() && rounds < 5) {
                    rounds++
                    val responseParts = response.functionCalls.map { call ->
                        val toolResult = toolHandler(call.name, call.args)
                        FunctionResponsePart(call.name, toolResult)
                    }
                    response = chat.sendMessage(
                        content("user") {
                            responseParts.forEach { part(it) }
                        }
                    )
                }
                val finalText = response.text?.trim().orEmpty()
                if (finalText.isBlank()) {
                    throw IllegalStateException("The assistant did not return a spoken response.")
                }
                finalText
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Assistant tool request timed out", e)
            throw IllegalStateException("Request timed out after 60 seconds. Please try again.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Assistant tool request failed", e)
            throw e
        }
    }

    /**
     * Analyzes a captured camera frame alongside the user's voice query.
     * Feeds into the rolling multi-turn chat so immediate follow-up questions preserve scene context.
     */
    fun analyzeSceneStream(
        bitmap: Bitmap,
        userQuestion: String,
        preferOnDeviceSingleTurn: Boolean = false
    ): Flow<AiStreamChunk> = flow {
        val prompt = content {
            image(bitmap)
            text(userQuestion.ifBlank { "Describe what is directly in front of me safely and concisely." })
        }

        val builder = StringBuilder()
        try {
            withTimeout(60_000L) {
                if (preferOnDeviceSingleTurn) {
                    val response = hybridSingleTurnModel.generateContent(prompt)
                    val text = response.text?.trim().orEmpty()
                    if (text.isBlank()) {
                        throw IllegalStateException("The AI model returned an empty description.")
                    }
                    val origin = if (response.inferenceSource.toString().contains("ON_DEVICE", ignoreCase = true)) {
                        InferenceOrigin.ON_DEVICE
                    } else {
                        InferenceOrigin.CLOUD
                    }
                    // Also seed cloud chat context for follow-ups if online
                    activeChat = cloudChatModel.startChat(
                        history = listOf(
                            content("user") { text("Scene question: $userQuestion") },
                            content("model") { text(text) }
                        )
                    )
                    emit(AiStreamChunk(text, isFinal = true, origin = origin))
                } else {
                    val chat = ensureChat()
                    chat.sendMessageStream(prompt).collect { chunk ->
                        val piece = chunk.text.orEmpty()
                        if (piece.isNotEmpty()) {
                            builder.append(piece)
                            emit(
                                AiStreamChunk(
                                    partialText = builder.toString(),
                                    isFinal = false,
                                    origin = InferenceOrigin.CLOUD
                                )
                            )
                        }
                    }
                    val finalText = builder.toString().trim()
                    if (finalText.isBlank()) {
                        throw IllegalStateException("The AI model returned an empty scene description.")
                    }
                    emit(AiStreamChunk(finalText, isFinal = true, origin = InferenceOrigin.CLOUD))
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Scene analysis timed out", e)
            throw IllegalStateException("Scene analysis timed out after 60 seconds. Check your internet connection and try again.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Scene analysis failed", e)
            throw e
        }
    }

    /**
     * Multi-turn conversational follow-up without needing to re-capture the scene unless asked.
     */
    fun askFollowUpStream(question: String): Flow<AiStreamChunk> = flow {
        val builder = StringBuilder()
        try {
            withTimeout(60_000L) {
                val chat = ensureChat()
                chat.sendMessageStream(question).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        builder.append(piece)
                        emit(
                            AiStreamChunk(
                                partialText = builder.toString(),
                                isFinal = false,
                                origin = InferenceOrigin.CLOUD
                            )
                        )
                    }
                }
                val finalText = builder.toString().trim()
                if (finalText.isBlank()) {
                    throw IllegalStateException("The AI model returned an empty response.")
                }
                emit(AiStreamChunk(finalText, isFinal = true, origin = InferenceOrigin.CLOUD))
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Follow-up timed out", e)
            throw IllegalStateException("Request timed out after 60 seconds. Please try again.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Follow-up failed", e)
            throw e
        }
    }

    suspend fun translateText(
        sourceText: String,
        sourceLanguageName: String,
        targetLanguageName: String
    ): TranslationAiResult {
        val prompt = """
            Translate the following text accurately into $targetLanguageName.
            Source language hint: $sourceLanguageName.
            First detect the source language name, then provide the natural translation.
            Format strictly as two lines:
            DETECTED: <language name>
            TRANSLATION: <translated text only, plain spoken text>
            
            Text:
            $sourceText
        """.trimIndent()

        try {
            val response = withTimeout(60_000L) {
                translationModel.generateContent(prompt)
            }
            val raw = response.text?.trim().orEmpty()
            if (raw.isBlank()) throw IllegalStateException("Translation returned empty output.")

            val detected = raw.lines()
                .firstOrNull { it.startsWith("DETECTED:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()
                ?: sourceLanguageName
            val translated = raw.lines()
                .firstOrNull { it.startsWith("TRANSLATION:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()
                ?: raw

            return TranslationAiResult(
                detectedLanguage = detected,
                translatedText = translated
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Translation timed out", e)
            throw IllegalStateException("Translation timed out. Check your internet connection.", e)
        } catch (e: CancellationException) {
            throw e
        }
    }

    suspend fun readAndTranslateImage(
        bitmap: Bitmap,
        targetLanguageName: String
    ): ImageTranslationAiResult {
        val prompt = content {
            image(bitmap)
            text(
                """
                Read all legible text visible in this image, identify the language, and translate it to $targetLanguageName.
                If no text is visible or it is too blurry, clearly say NO_TEXT_VISIBLE.
                Format strictly as:
                DETECTED: <language name>
                ORIGINAL: <exact visible text>
                TRANSLATION: <translated text in $targetLanguageName>
                """.trimIndent()
            )
        }

        try {
            val response = withTimeout(60_000L) {
                translationModel.generateContent(prompt)
            }
            val raw = response.text?.trim().orEmpty()
            if (raw.isBlank() || raw.contains("NO_TEXT_VISIBLE")) {
                throw IllegalStateException("No readable text was detected in front of the camera.")
            }
            val detected = raw.lines()
                .firstOrNull { it.startsWith("DETECTED:", ignoreCase = true) }
                ?.substringAfter(":")?.trim() ?: "Unknown"
            val original = raw.lines()
                .firstOrNull { it.startsWith("ORIGINAL:", ignoreCase = true) }
                ?.substringAfter(":")?.trim() ?: ""
            val translated = raw.lines()
                .firstOrNull { it.startsWith("TRANSLATION:", ignoreCase = true) }
                ?.substringAfter(":")?.trim() ?: raw

            return ImageTranslationAiResult(
                detectedLanguage = detected,
                originalText = original,
                translatedText = translated
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Image translation timed out", e)
            throw IllegalStateException("Image translation timed out. Please try again.", e)
        } catch (e: CancellationException) {
            throw e
        }
    }
}

data class TranslationAiResult(
    val detectedLanguage: String,
    val translatedText: String
)

data class ImageTranslationAiResult(
    val detectedLanguage: String,
    val originalText: String,
    val translatedText: String
)
