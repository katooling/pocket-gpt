package com.pocketagent.android.ui.state

import android.content.Context
import com.pocketagent.core.RoutingMode
import com.pocketagent.runtime.RuntimePerformanceProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class PersistedChatState(
    val sessions: List<ChatSessionUiModel> = emptyList(),
    val activeSessionId: String? = null,
    val routingMode: String = "AUTO",
    val performanceProfile: String = "BALANCED",
    val keepAlivePreference: String = RuntimeKeepAlivePreference.AUTO.name,
    val gpuAccelerationEnabled: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val firstSessionStage: String = FirstSessionStage.ONBOARDING.name,
    val advancedUnlocked: Boolean = true,
    val firstAnswerCompleted: Boolean = false,
    val followUpCompleted: Boolean = false,
    val firstSessionTelemetryEvents: List<FirstSessionTelemetryEvent> = emptyList(),
)

sealed interface SessionStateLoadResult {
    data class Success(val state: PersistedChatState) : SessionStateLoadResult

    data class RecoverableCorruption(
        val resetState: PersistedChatState,
        val code: String,
        val userMessage: String,
        val technicalDetail: String,
    ) : SessionStateLoadResult

    data class FatalCorruption(
        val code: String,
        val userMessage: String,
        val technicalDetail: String,
    ) : SessionStateLoadResult
}

interface SessionPersistence {
    fun loadState(): PersistedChatState
    fun loadStateResult(): SessionStateLoadResult = SessionStateLoadResult.Success(loadState())
    fun saveState(state: PersistedChatState)
    fun clearState() {}
}

class AndroidSessionPersistence(
    context: Context,
) : SessionPersistence {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun loadState(): PersistedChatState {
        return when (val result = loadStateResult()) {
            is SessionStateLoadResult.Success -> result.state
            is SessionStateLoadResult.RecoverableCorruption -> result.resetState
            is SessionStateLoadResult.FatalCorruption -> PersistedChatState()
        }
    }

    override fun loadStateResult(): SessionStateLoadResult {
        val raw = prefs.getString(KEY_STATE, null) ?: return SessionStateLoadResult.Success(PersistedChatState())
        return runCatching { PersistedChatStateCodec.decode(raw) }
            .fold(
                onSuccess = { decoded ->
                    SessionStateLoadResult.Success(decoded)
                },
                onFailure = { error ->
                    val backupToken = backupCorruptState(raw = raw, error = error)
                    clearState()
                    SessionStateLoadResult.RecoverableCorruption(
                        resetState = PersistedChatState(),
                        code = CHAT_STATE_CORRUPTION_CODE,
                        userMessage = "Saved chat state was corrupted and reset.",
                        technicalDetail = "code=$CHAT_STATE_CORRUPTION_CODE;backup=$backupToken;error=${error.message ?: "decode_failed"}",
                    )
                },
            )
    }

    override fun saveState(state: PersistedChatState) {
        prefs.edit().putString(KEY_STATE, PersistedChatStateCodec.encode(state)).apply()
    }

    override fun clearState() {
        prefs.edit().remove(KEY_STATE).apply()
    }

    private fun backupCorruptState(raw: String, error: Throwable): String {
        val token = "chat-state-${System.currentTimeMillis()}"
        prefs.edit()
            .putString(KEY_CORRUPT_BACKUP_PAYLOAD, raw)
            .putString(KEY_CORRUPT_BACKUP_TOKEN, token)
            .putLong(KEY_CORRUPT_BACKUP_SAVED_AT_EPOCH_MS, System.currentTimeMillis())
            .putString(KEY_CORRUPT_BACKUP_ERROR, error.message ?: error::class.java.simpleName)
            .apply()
        return token
    }

    private companion object {
        const val PREFS_NAME = "pocketagent_chat_state"
        const val KEY_STATE = "chat_state_v2"
        const val KEY_CORRUPT_BACKUP_PAYLOAD = "chat_state_corrupt_backup_payload"
        const val KEY_CORRUPT_BACKUP_TOKEN = "chat_state_corrupt_backup_token"
        const val KEY_CORRUPT_BACKUP_SAVED_AT_EPOCH_MS = "chat_state_corrupt_backup_saved_at"
        const val KEY_CORRUPT_BACKUP_ERROR = "chat_state_corrupt_backup_error"
        const val CHAT_STATE_CORRUPTION_CODE = "CHAT_STATE_CORRUPT_JSON"
    }
}

internal object PersistedChatStateCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun decode(raw: String): PersistedChatState {
        val root = json.parseToJsonElement(raw).jsonObject
        val sessions = root["sessions"]
            ?.let { element -> parseSessions(element) }
            ?: emptyList()
        return PersistedChatState(
            sessions = sessions,
            activeSessionId = root.stringOrNull("activeSessionId"),
            routingMode = parseRoutingModeName(root.stringOrDefault("routingMode", RoutingMode.AUTO.name)),
            performanceProfile = parsePerformanceProfileName(root.stringOrDefault("performanceProfile", RuntimePerformanceProfile.BALANCED.name)),
            keepAlivePreference = parseKeepAlivePreferenceName(
                root.stringOrDefault("keepAlivePreference", RuntimeKeepAlivePreference.AUTO.name),
            ),
            gpuAccelerationEnabled = root.booleanOrDefault("gpuAccelerationEnabled", false),
            onboardingCompleted = root.booleanOrDefault("onboardingCompleted", false),
            firstSessionStage = parseFirstSessionStageName(root.stringOrDefault("firstSessionStage", FirstSessionStage.ONBOARDING.name)),
            advancedUnlocked = root.booleanOrDefault("advancedUnlocked", true),
            firstAnswerCompleted = root.booleanOrDefault("firstAnswerCompleted", false),
            followUpCompleted = root.booleanOrDefault("followUpCompleted", false),
            firstSessionTelemetryEvents = parseFirstSessionTelemetryEvents(root["firstSessionTelemetryEvents"]),
        )
    }

    fun encode(state: PersistedChatState): String {
        val root = buildJsonObject {
            state.activeSessionId?.let { put("activeSessionId", JsonPrimitive(it)) }
            put("routingMode", JsonPrimitive(state.routingMode))
            put("performanceProfile", JsonPrimitive(state.performanceProfile))
            put("keepAlivePreference", JsonPrimitive(state.keepAlivePreference))
            put("gpuAccelerationEnabled", JsonPrimitive(state.gpuAccelerationEnabled))
            put("onboardingCompleted", JsonPrimitive(state.onboardingCompleted))
            put("firstSessionStage", JsonPrimitive(state.firstSessionStage))
            put("advancedUnlocked", JsonPrimitive(state.advancedUnlocked))
            put("firstAnswerCompleted", JsonPrimitive(state.firstAnswerCompleted))
            put("followUpCompleted", JsonPrimitive(state.followUpCompleted))
            put(
                "firstSessionTelemetryEvents",
                buildJsonArray {
                    state.firstSessionTelemetryEvents.forEach { event ->
                        add(
                            buildJsonObject {
                                put("eventName", JsonPrimitive(event.eventName))
                                put("eventTimeUtc", JsonPrimitive(event.eventTimeUtc))
                            },
                        )
                    }
                },
            )
            put(
                "sessions",
                buildJsonArray {
                    state.sessions.forEach { session ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(session.id))
                                put("title", JsonPrimitive(session.title))
                                put("createdAtEpochMs", JsonPrimitive(session.createdAtEpochMs))
                                put("updatedAtEpochMs", JsonPrimitive(session.updatedAtEpochMs))
                                put("completionSettings", buildJsonObject {
                                    put("temperature", JsonPrimitive(session.completionSettings.temperature))
                                    put("topP", JsonPrimitive(session.completionSettings.topP))
                                    put("topK", JsonPrimitive(session.completionSettings.topK))
                                    put("maxTokens", JsonPrimitive(session.completionSettings.maxTokens))
                                    put("repeatPenalty", JsonPrimitive(session.completionSettings.repeatPenalty))
                                    put("frequencyPenalty", JsonPrimitive(session.completionSettings.frequencyPenalty))
                                    put("presencePenalty", JsonPrimitive(session.completionSettings.presencePenalty))
                                    put("systemPrompt", JsonPrimitive(session.completionSettings.systemPrompt))
                                })
                                put(
                                    "messages",
                                    buildJsonArray {
                                        session.messages.forEach { message ->
                                            add(
                                                buildJsonObject {
                                                    put("id", JsonPrimitive(message.id))
                                                    put("role", JsonPrimitive(message.role.name))
                                                    put("content", JsonPrimitive(message.content))
                                                    put("timestampEpochMs", JsonPrimitive(message.timestampEpochMs))
                                                    put("kind", JsonPrimitive(message.kind.name))
                                                    message.imagePath?.let { put("imagePath", JsonPrimitive(it)) }
                                                    if (message.imagePaths.isNotEmpty()) {
                                                        put("imagePaths", buildJsonArray {
                                                            message.imagePaths.forEach { add(JsonPrimitive(it)) }
                                                        })
                                                    }
                                                    message.toolName?.let { put("toolName", JsonPrimitive(it)) }
                                                    put("isStreaming", JsonPrimitive(message.isStreaming))
                                                    message.requestId?.let { put("requestId", JsonPrimitive(it)) }
                                                    message.finishReason?.let { put("finishReason", JsonPrimitive(it)) }
                                                    put("terminalEventSeen", JsonPrimitive(message.terminalEventSeen))
                                                    put("interaction", encodeInteraction(message))
                                                    message.reasoningContent?.let { put("reasoningContent", JsonPrimitive(it)) }
                                                    message.firstTokenMs?.let { put("firstTokenMs", JsonPrimitive(it)) }
                                                    message.tokensPerSec?.let { put("tokensPerSec", JsonPrimitive(it)) }
                                                    message.totalLatencyMs?.let { put("totalLatencyMs", JsonPrimitive(it)) }
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    private fun parseFirstSessionTelemetryEvents(element: JsonElement?): List<FirstSessionTelemetryEvent> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item.asObjectOrNull() ?: return@mapNotNull null
            val eventName = obj.stringOrNull("eventName") ?: return@mapNotNull null
            val eventTimeUtc = obj.stringOrNull("eventTimeUtc") ?: return@mapNotNull null
            FirstSessionTelemetryEvent(
                eventName = eventName,
                eventTimeUtc = eventTimeUtc,
            )
        }
    }

    private fun parseSessions(element: JsonElement): List<ChatSessionUiModel> {
        return element.jsonArray.mapNotNull { sessionElement ->
            val obj = sessionElement.asObjectOrNull() ?: return@mapNotNull null
            ChatSessionUiModel(
                id = obj.stringOrDefault("id", ""),
                title = obj.stringOrDefault("title", "New chat"),
                createdAtEpochMs = obj.longOrDefault("createdAtEpochMs", 0L),
                updatedAtEpochMs = obj.longOrDefault("updatedAtEpochMs", 0L),
                messages = parseMessages(obj["messages"]),
                completionSettings = parseCompletionSettings(obj["completionSettings"]),
            )
        }
    }

    private fun parseCompletionSettings(element: JsonElement?): CompletionSettings {
        val obj = element?.asObjectOrNull() ?: return CompletionSettings()
        return CompletionSettings(
            temperature = obj.floatOrDefault("temperature", 0.7f),
            topP = obj.floatOrDefault("topP", 0.9f),
            topK = obj.intOrDefault("topK", 40),
            maxTokens = obj.intOrDefault("maxTokens", 2048),
            repeatPenalty = obj.floatOrDefault("repeatPenalty", 1.1f),
            frequencyPenalty = obj.floatOrDefault("frequencyPenalty", 0.0f),
            presencePenalty = obj.floatOrDefault("presencePenalty", 0.0f),
            systemPrompt = obj.stringOrDefault("systemPrompt", ""),
        )
    }

    private fun parseMessages(element: JsonElement?): List<MessageUiModel> {
        val array = (element as? JsonArray) ?: return emptyList()
        return array.mapNotNull { messageElement ->
            val obj = messageElement.asObjectOrNull() ?: return@mapNotNull null
            MessageUiModel(
                id = obj.stringOrDefault("id", ""),
                role = parseRole(obj.stringOrDefault("role", MessageRole.SYSTEM.name)),
                content = obj.stringOrDefault("content", ""),
                timestampEpochMs = obj.longOrDefault("timestampEpochMs", 0L),
                kind = parseKind(obj.stringOrDefault("kind", MessageKind.TEXT.name)),
                imagePath = obj.stringOrNull("imagePath"),
                imagePaths = parseStringList(obj["imagePaths"]),
                toolName = obj.stringOrNull("toolName"),
                isStreaming = obj.booleanOrDefault("isStreaming", false),
                requestId = obj.stringOrNull("requestId"),
                finishReason = obj.stringOrNull("finishReason"),
                terminalEventSeen = obj.booleanOrDefault("terminalEventSeen", false),
                interaction = decodeInteraction(obj),
                reasoningContent = obj.stringOrNull("reasoningContent"),
                firstTokenMs = obj.longOrNull("firstTokenMs"),
                tokensPerSec = obj.doubleOrNull("tokensPerSec"),
                totalLatencyMs = obj.longOrNull("totalLatencyMs"),
            )
        }
    }

    private fun encodeInteraction(message: MessageUiModel): JsonObject {
        val interaction = message.interaction ?: legacyInteractionFor(message)
        return buildJsonObject {
            put("role", JsonPrimitive(interaction.role))
            interaction.toolCallId?.let { put("toolCallId", JsonPrimitive(it)) }
            put(
                "parts",
                buildJsonArray {
                    interaction.parts.forEach { part ->
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive(part.type))
                                part.text?.let { put("text", JsonPrimitive(it)) }
                            },
                        )
                    }
                },
            )
            put(
                "toolCalls",
                buildJsonArray {
                    interaction.toolCalls.forEach { toolCall ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(toolCall.id))
                                put("name", JsonPrimitive(toolCall.name))
                                put("argumentsJson", JsonPrimitive(toolCall.argumentsJson))
                                put("status", JsonPrimitive(toolCall.status.name))
                            },
                        )
                    }
                },
            )
            put(
                "metadata",
                buildJsonObject {
                    interaction.metadata.forEach { (key, value) ->
                        put(key, JsonPrimitive(value))
                    }
                },
            )
        }
    }

    private fun decodeInteraction(obj: JsonObject): PersistedInteractionMessage {
        val encoded = obj["interaction"]?.asObjectOrNull()
        if (encoded == null) {
            return legacyInteractionFor(obj)
        }
        val role = encoded.stringOrDefault("role", obj.stringOrDefault("role", MessageRole.SYSTEM.name))
        val parts = (encoded["parts"] as? JsonArray)?.mapNotNull { partElement ->
            val part = partElement.asObjectOrNull() ?: return@mapNotNull null
            PersistedInteractionPart(
                type = part.stringOrDefault("type", "text"),
                text = part.stringOrNull("text"),
            )
        } ?: emptyList()
        val toolCalls = (encoded["toolCalls"] as? JsonArray)?.mapNotNull { callElement ->
            val call = callElement.asObjectOrNull() ?: return@mapNotNull null
            val id = call.stringOrNull("id") ?: return@mapNotNull null
            val name = call.stringOrNull("name") ?: return@mapNotNull null
            val args = call.stringOrDefault("argumentsJson", "{}")
            val status = call.stringOrDefault("status", PersistedToolCallStatus.PENDING.name)
            PersistedToolCall(
                id = id,
                name = name,
                argumentsJson = args,
                status = parseToolCallStatus(status),
            )
        } ?: emptyList()
        val metadata = encoded["metadata"]?.asObjectOrNull()?.entries?.associate { entry ->
            val value = runCatching { entry.value.jsonPrimitive.content }.getOrDefault("")
            entry.key to value
        } ?: emptyMap()
        return PersistedInteractionMessage(
            role = role,
            parts = if (parts.isEmpty()) listOf(PersistedInteractionPart(type = "text", text = obj.stringOrDefault("content", ""))) else parts,
            toolCalls = toolCalls,
            toolCallId = encoded.stringOrNull("toolCallId"),
            metadata = metadata,
        )
    }

    private fun legacyInteractionFor(obj: JsonObject): PersistedInteractionMessage {
        return PersistedInteractionMessage(
            role = obj.stringOrDefault("role", MessageRole.SYSTEM.name),
            parts = listOf(PersistedInteractionPart(type = "text", text = obj.stringOrDefault("content", ""))),
            metadata = mapOf("source" to "legacy_v2"),
        )
    }

    private fun legacyInteractionFor(message: MessageUiModel): PersistedInteractionMessage {
        return PersistedInteractionMessage(
            role = message.role.name,
            parts = listOf(PersistedInteractionPart(type = "text", text = message.content)),
            metadata = buildMap {
                put("kind", message.kind.name)
                message.toolName?.let { put("toolName", it) }
            },
        )
    }

    private fun parseRole(raw: String): MessageRole {
        return runCatching { MessageRole.valueOf(raw) }
            .getOrElse { throw IllegalArgumentException("CHAT_STATE_INVALID_ROLE:$raw") }
    }

    private fun parseKind(raw: String): MessageKind {
        return runCatching { MessageKind.valueOf(raw) }
            .getOrElse { throw IllegalArgumentException("CHAT_STATE_INVALID_KIND:$raw") }
    }

    private fun parseRoutingModeName(raw: String): String {
        return runCatching { RoutingMode.valueOf(raw) }
            .getOrElse { throw IllegalArgumentException("CHAT_STATE_INVALID_ROUTING_MODE:$raw") }
            .name
    }

    private fun parsePerformanceProfileName(raw: String): String {
        return runCatching { RuntimePerformanceProfile.valueOf(raw) }
            .getOrElse { throw IllegalArgumentException("CHAT_STATE_INVALID_PERFORMANCE_PROFILE:$raw") }
            .name
    }

    private fun parseFirstSessionStageName(raw: String): String {
        return runCatching { FirstSessionStage.valueOf(raw) }
            .getOrElse { throw IllegalArgumentException("CHAT_STATE_INVALID_FIRST_SESSION_STAGE:$raw") }
            .name
    }

    private fun parseKeepAlivePreferenceName(raw: String): String {
        return runCatching { RuntimeKeepAlivePreference.valueOf(raw) }
            .getOrElse { throw IllegalArgumentException("CHAT_STATE_INVALID_KEEP_ALIVE_PREFERENCE:$raw") }
            .name
    }
}

private fun parseToolCallStatus(raw: String): PersistedToolCallStatus {
    return runCatching { PersistedToolCallStatus.valueOf(raw.trim().uppercase()) }
        .getOrElse { PersistedToolCallStatus.PENDING }
}

private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonObject.stringOrNull(key: String): String? {
    val value = this[key] ?: return null
    val content = runCatching { value.jsonPrimitive.content }.getOrNull()?.trim().orEmpty()
    return content.ifBlank { null }
}

private fun JsonObject.stringOrDefault(key: String, default: String): String {
    return stringOrNull(key) ?: default
}

private fun JsonObject.longOrDefault(key: String, default: Long): Long {
    val value = this[key] ?: return default
    val raw = runCatching { value.jsonPrimitive.content }.getOrNull()
        ?: throw IllegalArgumentException("CHAT_STATE_INVALID_LONG:$key")
    return raw.toLongOrNull() ?: throw IllegalArgumentException("CHAT_STATE_INVALID_LONG:$key")
}

private fun JsonObject.floatOrDefault(key: String, default: Float): Float {
    val value = this[key] ?: return default
    val raw = runCatching { value.jsonPrimitive.content }.getOrNull() ?: return default
    return raw.toFloatOrNull() ?: default
}

private fun JsonObject.intOrDefault(key: String, default: Int): Int {
    val value = this[key] ?: return default
    val raw = runCatching { value.jsonPrimitive.content }.getOrNull() ?: return default
    return raw.toIntOrNull() ?: default
}

private fun JsonObject.longOrNull(key: String): Long? {
    val value = this[key] ?: return null
    val raw = runCatching { value.jsonPrimitive.content }.getOrNull() ?: return null
    return raw.toLongOrNull()
}

private fun JsonObject.doubleOrNull(key: String): Double? {
    val value = this[key] ?: return null
    val raw = runCatching { value.jsonPrimitive.content }.getOrNull() ?: return null
    return raw.toDoubleOrNull()
}

private fun parseStringList(element: JsonElement?): List<String> {
    val array = element as? JsonArray ?: return emptyList()
    return array.mapNotNull { item ->
        runCatching { item.jsonPrimitive.content }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

private fun JsonObject.booleanOrDefault(key: String, default: Boolean): Boolean {
    val value = this[key] ?: return default
    val raw = runCatching { value.jsonPrimitive.content }.getOrNull()
        ?: throw IllegalArgumentException("CHAT_STATE_INVALID_BOOLEAN:$key")
    return runCatching { raw.toBooleanStrict() }
        .getOrElse { throw IllegalArgumentException("CHAT_STATE_INVALID_BOOLEAN:$key") }
}
