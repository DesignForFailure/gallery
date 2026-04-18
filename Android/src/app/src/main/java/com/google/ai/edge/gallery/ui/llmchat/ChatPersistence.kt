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

import com.google.ai.edge.gallery.ui.common.chat.ChatMessage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageThinking
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PersistedMessage(
  val role: String,
  val content: String,
  val timestampMs: Long,
)

private val json = Json { ignoreUnknownKeys = true }

fun List<ChatMessage>.toPersistedJson(): String {
  val persisted = this.mapNotNull { msg ->
    when (msg) {
      is ChatMessageText -> PersistedMessage(
        role = if (msg.side == ChatSide.USER) "user" else "assistant",
        content = msg.content,
        timestampMs = System.currentTimeMillis(),
      )
      is ChatMessageThinking -> PersistedMessage(
        role = "thinking",
        content = msg.content,
        timestampMs = System.currentTimeMillis(),
      )
      else -> null
    }
  }
  return json.encodeToString(persisted)
}

fun String.fromPersistedJson(): List<ChatMessage> {
  if (this.isBlank()) return emptyList()
  val persisted: List<PersistedMessage> = json.decodeFromString(this)
  return persisted.map { pm ->
    when (pm.role) {
      "user" -> ChatMessageText(content = pm.content, side = ChatSide.USER)
      "thinking" -> ChatMessageThinking(
        content = pm.content,
        inProgress = false,
        side = ChatSide.AGENT,
        hideSenderLabel = true,
      )
      else -> ChatMessageText(
        content = pm.content,
        side = ChatSide.AGENT,
        hideSenderLabel = false,
      )
    }
  }
}

fun autoTitleFromMessages(messages: List<ChatMessage>): String {
  val firstUserMsg = messages.firstOrNull {
    it is ChatMessageText && it.side == ChatSide.USER
  } as? ChatMessageText ?: return "New Chat"
  val text = firstUserMsg.content.trim().replace('\n', ' ')
  return if (text.length <= 40) text else text.substring(0, 40) + "..."
}

fun List<PersistedMessage>.toTranscriptBlock(): String {
  return this.filter { it.role == "user" || it.role == "assistant" }
    .joinToString("\n") { pm ->
      val label = if (pm.role == "user") "User" else "Assistant"
      "$label: ${pm.content}"
    }
}

fun buildSystemInstruction(
  memory: String,
  agentOrSkillPrefix: String,
  priorTranscript: String,
): Contents? {
  val parts = buildList {
    if (agentOrSkillPrefix.isNotBlank()) add(agentOrSkillPrefix)
    if (memory.isNotBlank()) add("User preferences / memory:\n$memory")
    if (priorTranscript.isNotBlank()) add("Prior conversation:\n$priorTranscript")
  }
  return if (parts.isEmpty()) null
  else Contents.of(listOf(Content.Text(parts.joinToString("\n\n"))))
}
