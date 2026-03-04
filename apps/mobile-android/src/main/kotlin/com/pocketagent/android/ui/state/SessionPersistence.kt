package com.pocketagent.android.ui.state

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PersistedChatState(
    val sessions: List<ChatSessionUiModel> = emptyList(),
    val activeSessionId: String? = null,
    val routingMode: String = "AUTO",
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
        return runCatching { parseState(raw) }.getOrElse { PersistedChatState() }
    }

    override fun saveState(state: PersistedChatState) {
        prefs.edit().putString(KEY_STATE, encodeState(state)).apply()
    }

    private fun parseState(raw: String): PersistedChatState {
        val root = JSONObject(raw)
        val sessionsArray = root.optJSONArray("sessions") ?: JSONArray()
        val sessions = mutableListOf<ChatSessionUiModel>()
        for (index in 0 until sessionsArray.length()) {
            val obj = sessionsArray.optJSONObject(index) ?: continue
            sessions += parseSession(obj)
        }
        return PersistedChatState(
            sessions = sessions,
            activeSessionId = root.optString("activeSessionId").ifBlank { null },
            routingMode = root.optString("routingMode", "AUTO"),
        )
    }

    private fun parseSession(obj: JSONObject): ChatSessionUiModel {
        val messagesArray = obj.optJSONArray("messages") ?: JSONArray()
        val messages = mutableListOf<MessageUiModel>()
        for (index in 0 until messagesArray.length()) {
            val message = messagesArray.optJSONObject(index) ?: continue
            messages += MessageUiModel(
                id = message.optString("id"),
                role = MessageRole.valueOf(message.optString("role", MessageRole.SYSTEM.name)),
                content = message.optString("content"),
                timestampEpochMs = message.optLong("timestampEpochMs"),
                kind = MessageKind.valueOf(message.optString("kind", MessageKind.TEXT.name)),
                imagePath = message.optString("imagePath").ifBlank { null },
                toolName = message.optString("toolName").ifBlank { null },
                isStreaming = message.optBoolean("isStreaming", false),
            )
        }
        return ChatSessionUiModel(
            id = obj.optString("id"),
            title = obj.optString("title", "New chat"),
            createdAtEpochMs = obj.optLong("createdAtEpochMs"),
            updatedAtEpochMs = obj.optLong("updatedAtEpochMs"),
            messages = messages,
        )
    }

    private fun encodeState(state: PersistedChatState): String {
        val root = JSONObject()
        root.put("activeSessionId", state.activeSessionId)
        root.put("routingMode", state.routingMode)
        val sessionsArray = JSONArray()
        state.sessions.forEach { session ->
            val sessionObj = JSONObject()
            sessionObj.put("id", session.id)
            sessionObj.put("title", session.title)
            sessionObj.put("createdAtEpochMs", session.createdAtEpochMs)
            sessionObj.put("updatedAtEpochMs", session.updatedAtEpochMs)
            val messagesArray = JSONArray()
            session.messages.forEach { message ->
                val messageObj = JSONObject()
                messageObj.put("id", message.id)
                messageObj.put("role", message.role.name)
                messageObj.put("content", message.content)
                messageObj.put("timestampEpochMs", message.timestampEpochMs)
                messageObj.put("kind", message.kind.name)
                messageObj.put("imagePath", message.imagePath)
                messageObj.put("toolName", message.toolName)
                messageObj.put("isStreaming", message.isStreaming)
                messagesArray.put(messageObj)
            }
            sessionObj.put("messages", messagesArray)
            sessionsArray.put(sessionObj)
        }
        root.put("sessions", sessionsArray)
        return root.toString()
    }

    private companion object {
        const val PREFS_NAME = "pocketagent_chat_state"
        const val KEY_STATE = "chat_state_v1"
    }
}
