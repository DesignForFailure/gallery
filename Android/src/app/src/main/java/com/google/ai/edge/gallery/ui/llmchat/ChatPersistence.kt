/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.llmchat

import android.util.Log
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.ui.common.chat.ChatMessage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageThinking
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.litertlm.Contents
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "AGChatPersistence"

/** Maximum number of characters in an auto-generated chat title. */
const val CHAT_TITLE_MAX_LENGTH = 40

/** Tasks whose chats are persisted across app restarts. */
val CHAT_HISTORY_TASK_IDS = setOf(BuiltInTaskId.LLM_CHAT, BuiltInTaskId.LLM_AGENT_CHAT)

object PersistedMessageRole {
  const val USER = "user"
  const val ASSISTANT = "assistant"
  const val THINKING = "thinking"
}

/** JSON-serializable snapshot of a single chat message. */
@Serializable
data class PersistedMessage(val role: String, val content: String, val timestampMs: Long = 0L)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Converts chat messages to a JSON string of [PersistedMessage].
 *
 * Only plain text and thinking messages are persisted; transient messages (loading, errors,
 * progress panels, images, etc.) are skipped.
 */
fun List<ChatMessage>.toPersistedJson(nowMs: Long = System.currentTimeMillis()): String {
  return json.encodeToString(this.toPersistedMessages(nowMs = nowMs))
}

/** Converts chat messages to a list of [PersistedMessage], filtering transient messages. */
fun List<ChatMessage>.toPersistedMessages(
  nowMs: Long = System.currentTimeMillis()
): List<PersistedMessage> {
  val persisted = mutableListOf<PersistedMessage>()
  for (message in this) {
    when {
      message is ChatMessageText &&
        message.type == ChatMessageType.TEXT &&
        message.side == ChatSide.USER ->
        persisted.add(
          PersistedMessage(
            role = PersistedMessageRole.USER,
            content = message.content,
            timestampMs = nowMs,
          )
        )
      message is ChatMessageText &&
        message.type == ChatMessageType.TEXT &&
        message.side == ChatSide.AGENT ->
        persisted.add(
          PersistedMessage(
            role = PersistedMessageRole.ASSISTANT,
            content = message.content,
            timestampMs = nowMs,
          )
        )
      message is ChatMessageThinking ->
        persisted.add(
          PersistedMessage(
            role = PersistedMessageRole.THINKING,
            content = message.content,
            timestampMs = nowMs,
          )
        )
    }
  }
  return persisted
}

/** Serializes a list of [PersistedMessage] to JSON. */
fun List<PersistedMessage>.toJson(): String {
  return json.encodeToString(this)
}

/** Parses a JSON string produced by [toPersistedJson] back into [PersistedMessage]s. */
fun String.fromPersistedJson(): List<PersistedMessage> {
  if (this.isBlank()) {
    return listOf()
  }
  return try {
    json.decodeFromString<List<PersistedMessage>>(this)
  } catch (e: Exception) {
    Log.e(TAG, "Failed to parse persisted chat messages", e)
    listOf()
  }
}

/** Rebuilds displayable chat messages from persisted ones. */
fun List<PersistedMessage>.toChatMessages(): List<ChatMessage> {
  return this.mapNotNull { persisted ->
    when (persisted.role) {
      PersistedMessageRole.USER ->
        ChatMessageText(content = persisted.content, side = ChatSide.USER)
      PersistedMessageRole.ASSISTANT ->
        // Negative latency hides the latency label.
        ChatMessageText(content = persisted.content, side = ChatSide.AGENT, latencyMs = -1f)
      PersistedMessageRole.THINKING ->
        ChatMessageThinking(content = persisted.content, inProgress = false, side = ChatSide.AGENT)
      else -> null
    }
  }
}

/** Generates a chat title from the first user message (capped at [CHAT_TITLE_MAX_LENGTH]). */
fun autoTitleFromMessages(messages: List<ChatMessage>): String {
  val firstUserMessage =
    messages.firstOrNull { it is ChatMessageText && it.side == ChatSide.USER } as? ChatMessageText
  val title = firstUserMessage?.content?.trim()?.replace('\n', ' ') ?: ""
  return when {
    title.isEmpty() -> "New chat"
    title.length <= CHAT_TITLE_MAX_LENGTH -> title
    else -> title.substring(0, CHAT_TITLE_MAX_LENGTH).trimEnd() + "…"
  }
}

/** Builds a plain-text transcript block used for pseudo-replay of a stored chat. */
fun List<PersistedMessage>.toTranscriptBlock(): String {
  return this.filter { it.role != PersistedMessageRole.THINKING }
    .joinToString("\n") { persisted ->
      val label = if (persisted.role == PersistedMessageRole.USER) "User" else "Assistant"
      "$label: ${persisted.content}"
    }
}

/**
 * Composes the text of a system instruction from the agent/skill prefix, the global LLM memory,
 * and a prior-conversation transcript. Returns null when all parts are blank.
 */
fun buildSystemInstructionText(
  memory: String,
  agentOrSkillPrefix: String = "",
  priorTranscript: String = "",
): String? {
  val parts = buildList {
    if (agentOrSkillPrefix.isNotBlank()) add(agentOrSkillPrefix)
    if (memory.isNotBlank()) add("User preferences / memory:\n$memory")
    if (priorTranscript.isNotBlank()) add("Prior conversation:\n$priorTranscript")
  }
  return if (parts.isEmpty()) null else parts.joinToString("\n\n")
}

/** Same as [buildSystemInstructionText] but wrapped in LiteRT-LM [Contents]. */
fun buildSystemInstruction(
  memory: String,
  agentOrSkillPrefix: String = "",
  priorTranscript: String = "",
): Contents? {
  return buildSystemInstructionText(
      memory = memory,
      agentOrSkillPrefix = agentOrSkillPrefix,
      priorTranscript = priorTranscript,
    )
    ?.let { Contents.of(it) }
}

/**
 * Composes the system instruction used when (re-)initializing a model for a task.
 *
 * Includes the global LLM memory and, for tasks with persistent chat history, the transcript of
 * the task's active stored chat so a restored session can continue seamlessly.
 */
fun composeInitSystemInstruction(
  dataStoreRepository: DataStoreRepository,
  taskId: String,
  agentOrSkillPrefix: String = "",
): Contents? {
  val memory = dataStoreRepository.getLlmMemory()
  var transcript = ""
  if (taskId in CHAT_HISTORY_TASK_IDS) {
    val activeChatId = dataStoreRepository.getActiveChatId(taskId)
    if (activeChatId.isNotEmpty()) {
      val activeChat = dataStoreRepository.getAllChats().find { it.id == activeChatId }
      if (activeChat != null) {
        transcript = activeChat.messagesJson.fromPersistedJson().toTranscriptBlock()
      }
    }
  }
  return buildSystemInstruction(
    memory = memory,
    agentOrSkillPrefix = agentOrSkillPrefix,
    priorTranscript = transcript,
  )
}
