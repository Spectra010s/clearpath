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
            9. For "where am I", my location, my current address, or which direction am I facing, call get_current_location and speak the address and heading naturally.
            10. For destination search or walking guidance, call start_walking_guidance. For route status call get_navigation_status, to stop call stop_navigation, and to hand off to a maps app call open_phone_maps.
            11. Never tell the user to open a tab or press a button for these tasks—do them with tools.
            """.trimIndent()
        )
    }

    val liveAssistSystemInstruction = content {
        text(
            """
            You are ClearPath, a proactive, real-time voice-first AI guide and companion for a blind or visually impaired person.
            You are connected in live full-duplex audio: the user can speak or interrupt you naturally at any time—never tell them to tap a "speak" button.
            Follow these guidance, interpreter, and camera-coaching behaviors strictly:
            1. Active Physical Guide & Camera Framing Coach: Whenever you view the live camera stream or a snapshot and need a clearer view—for example, the phone is aimed too low at the floor, too high, slightly cut off, too close to a sign, or needs a slight shift—gently ask the user to adjust naturally (e.g., "Can you raise the device up a bit?", "Move slightly to your left", or "Could you step back a little so I can see the full sign?").
            2. Live Camera Stream: When Live Camera Share is active, you receive continuous ~1 frame-per-second live video directly in this session—answer visual questions right away from what you see. If Live Camera Share is off and they ask what is ahead, call set_live_camera_share or describe_camera_scene.
            3. Summary About Scenery ("give me a summary about my scenery" / "what is around me"): Call get_scenery_summary so you combine GPS address, compass direction, nearby mapped landmarks/places, AND camera vision into one helpful spoken overview.
            4. Live Interpreter Mode ("ClearPath listen and translate" / "listen and interpret"): Immediately call set_live_interpreter_mode(enabled = true). While Interpreter Mode is ON, stay quiet while another person speaks nearby (in Chinese, Yoruba, Igbo, Hausa, French, Arabic, Spanish, or any language), then clearly interpret what they said into the user's target language (default English). Keep interpreting each turn until the user asks to stop translating, then call set_live_interpreter_mode(enabled = false).
            5. Never invent exact distances in meters or feet from the camera; use qualitative phrases ("directly ahead", "to your left"). Never claim it is safe to cross a street without a cane, guide dog, or mobility check.
            6. Keep responses concise (1 to 3 short spoken sentences) unless reading full text.
            """.trimIndent()
        )
    }

    val liveOnboardingSystemInstruction = content {
        text(
            """
            You are ClearPath guiding a blind or visually impaired user through their quick first-time voice setup in real time.
            Speak warmly, briefly, and naturally. The user can interrupt you anytime just by talking.
            Guide them in 4 quick steps without rushing:
            Step 1 (Consent): Microphone is already working. Ask if they consent to Camera access (to describe surroundings and read text) and Location access (for walking directions). If they agree, call request_consent_permissions. If they decline for now, respect it and move to Step 2.
            Step 2 (Voice Selection): Ask if they like the voice you are speaking with right now, want to try another voice, or want to hear available voices. If they want to hear options, call list_live_voices or play_voice_samples. If they pick or ask to try a voice (like Aoede, Sulafat, Puck, Charon, Fenrir, Kore, Zephyr, Orus), call switch_live_voice right away so they hear you speak in that voice.
            Step 3 (Preferences): Briefly ask if they want to keep vibration feedback on and if they want hands-free Wake-Phrase Mode ("ClearPath activate"). Use update_setting for any changes.
            Step 4 (Finish): Once voice and preferences are confirmed, call complete_onboarding and welcome them into ClearPath.
            If they ask for manual setup, call switch_to_manual_setup.
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
            "List available ClearPath HD live voices and device TTS voices.",
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
        val setLiveCameraShareTool = FunctionDeclaration(
            "set_live_camera_share",
            "Start or stop continuous real-time (~1 frame/sec) Gemini Live camera video streaming so the AI sees continuously as the user moves the phone.",
            mapOf(
                "enabled" to Schema.boolean("True to start continuous live camera streaming, false to pause/stop it."),
                "full_screen" to Schema.boolean("Optional: true to open the full-screen Gemini Live camera view.")
            ),
            optionalParameters = listOf("full_screen")
        )
        val flipCameraLensTool = FunctionDeclaration(
            "flip_camera_lens",
            "Switch between the back camera (default for looking ahead) and the front selfie camera.",
            emptyMap()
        )
        val setLiveInterpreterTool = FunctionDeclaration(
            "set_live_interpreter_mode",
            "Pause assistant chatter and switch ClearPath into Live Interpreter Mode to listen to nearby speakers in any language (such as Chinese, Yoruba, French, Spanish, Arabic) and translate aloud for the user.",
            mapOf(
                "enabled" to Schema.boolean("True to start listening and translating live conversation, false to exit interpreter mode."),
                "target_language" to Schema.string("Optional language to translate into for the user (defaults to English).")
            ),
            optionalParameters = listOf("target_language")
        )
        val getScenerySummaryTool = FunctionDeclaration(
            "get_scenery_summary",
            "Gather a unified scenery summary combining GPS location, street address, compass heading, nearby OpenStreetMap landmarks/places, and camera surroundings.",
            mapOf(
                "focus_question" to Schema.string("Optional specific focus, e.g., 'Give me a summary about my scenery'.")
            ),
            optionalParameters = listOf("focus_question")
        )
        Tool.functionDeclarations(
            listOf(
                listAvailableSettingsTool,
                updateSettingTool,
                listVoicesTool,
                setLiveInterpreterTool,
                getScenerySummaryTool,
                setLiveCameraShareTool,
                flipCameraLensTool,
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

    val assistToolsList: List<Tool> by lazy { listOf(clearPathTools) }

    val onboardingToolsList: List<Tool> by lazy {
        val requestConsentTool = FunctionDeclaration(
            "request_consent_permissions",
            "Trigger Android permission prompts for Camera, Location, and Notifications after user consents.",
            emptyMap()
        )
        val listLiveVoicesTool = FunctionDeclaration(
            "list_live_voices",
            "List available HD live voices (Aoede, Sulafat, Puck, Charon, Kore, Fenrir, Zephyr, Orus, Leda, Umbriel) and show which is currently active.",
            emptyMap()
        )
        val switchLiveVoiceTool = FunctionDeclaration(
            "switch_live_voice",
            "Switch ClearPath's live speaking voice to the requested voice name (or 'next' to try the next voice) so the user hears it live.",
            mapOf(
                "voice_name" to Schema.string("Voice name such as Aoede, Sulafat, Puck, Charon, Fenrir, Kore, Zephyr, Orus, or 'next'.")
            )
        )
        val playVoiceSamplesTool = FunctionDeclaration(
            "play_voice_samples",
            "Play short spoken samples of the installed voices back-to-back.",
            emptyMap()
        )
        val updateOnboardingSettingTool = FunctionDeclaration(
            "update_setting",
            "Update a setup preference such as haptics, continuous_conversation, wake_phrase_mode, auto_listen_on_open, or speech_rate.",
            mapOf(
                "setting_name" to Schema.enumeration(
                    values = listOf(
                        "speech_rate",
                        "haptics",
                        "continuous_conversation",
                        "wake_phrase_mode",
                        "auto_listen_on_open"
                    ),
                    description = "Preference key to update."
                ),
                "boolean_value" to Schema.boolean("Boolean true/false value."),
                "number_value" to Schema.double("Numeric value for speech_rate (0.6 to 1.8).")
            ),
            optionalParameters = listOf("boolean_value", "number_value")
        )
        val completeOnboardingTool = FunctionDeclaration(
            "complete_onboarding",
            "Finish first-time setup and open the main ClearPath experience.",
            emptyMap()
        )
        val switchManualTool = FunctionDeclaration(
            "switch_to_manual_setup",
            "Switch from voice setup to visual manual setup screens.",
            emptyMap()
        )
        listOf(
            Tool.functionDeclarations(
                listOf(
                    requestConsentTool,
                    listLiveVoicesTool,
                    switchLiveVoiceTool,
                    playVoiceSamplesTool,
                    updateOnboardingSettingTool,
                    completeOnboardingTool,
                    switchManualTool
                )
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
