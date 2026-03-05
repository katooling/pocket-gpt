package com.pocketagent.android.ui.state

import android.content.Context
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
    val onboardingCompleted: Boolean = false,
)

interface SessionPersistence {
    fun loadState(): PersistedChatState
    fun saveState(state: PersistedChatState)
}

class AndroidSessionPersistence(
    context: Context,
) : SessionPersistence {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun loadState(): PersistedChatState {
        val raw = prefs.getString(KEY_STATE, null) ?: return PersistedChatState()
        return runCatching { PersistedChatStateCodec.decode(raw) }.getOrElse { PersistedChatState() }
    }

    override fun saveState(state: PersistedChatState) {
        prefs.edit().putString(KEY_STATE, PersistedChatStateCodec.encode(state)).apply()
    }

    private companion object {
        const val PREFS_NAME = "pocketagent_chat_state"
        const val KEY_STATE = "chat_state_v2"
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
            routingMode = root.stringOrDefault("routingMode", "AUTO"),
            onboardingCompleted = root.booleanOrDefault("onboardingCompleted", false),
        )
    }

    fun encode(state: PersistedChatState): String {
        val root = buildJsonObject {
            state.activeSessionId?.let { put("activeSessionId", JsonPrimitive(it)) }
            put("routingMode", JsonPrimitive(state.routingMode))
            put("onboardingCompleted", JsonPrimitive(state.onboardingCompleted))
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
                                                    message.toolName?.let { put("toolName", JsonPrimitive(it)) }
                                                    put("isStreaming", JsonPrimitive(message.isStreaming))
                                                    message.requestId?.let { put("requestId", JsonPrimitive(it)) }
                                                    message.finishReason?.let { put("finishReason", JsonPrimitive(it)) }
                                                    put("terminalEventSeen", JsonPrimitive(message.terminalEventSeen))
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

    private fun parseSessions(element: JsonElement): List<ChatSessionUiModel> {
        return element.jsonArray.mapNotNull { sessionElement ->
            val obj = sessionElement.asObjectOrNull() ?: return@mapNotNull null
            ChatSessionUiModel(
                id = obj.stringOrDefault("id", ""),
                title = obj.stringOrDefault("title", "New chat"),
                createdAtEpochMs = obj.longOrDefault("createdAtEpochMs", 0L),
                updatedAtEpochMs = obj.longOrDefault("updatedAtEpochMs", 0L),
                messages = parseMessages(obj["messages"]),
            )
        }
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
                toolName = obj.stringOrNull("toolName"),
                isStreaming = obj.booleanOrDefault("isStreaming", false),
                requestId = obj.stringOrNull("requestId"),
                finishReason = obj.stringOrNull("finishReason"),
                terminalEventSeen = obj.booleanOrDefault("terminalEventSeen", false),
            )
        }
    }

    private fun parseRole(raw: String): MessageRole {
        return runCatching { MessageRole.valueOf(raw) }.getOrDefault(MessageRole.SYSTEM)
    }

    private fun parseKind(raw: String): MessageKind {
        return runCatching { MessageKind.valueOf(raw) }.getOrDefault(MessageKind.TEXT)
    }
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
    return runCatching { value.jsonPrimitive.content.toLong() }.getOrDefault(default)
}

private fun JsonObject.booleanOrDefault(key: String, default: Boolean): Boolean {
    val value = this[key] ?: return default
    return runCatching { value.jsonPrimitive.content.toBooleanStrict() }.getOrDefault(default)
}
