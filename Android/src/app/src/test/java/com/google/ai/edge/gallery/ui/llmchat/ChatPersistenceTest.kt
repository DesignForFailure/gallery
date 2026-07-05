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
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageLoading
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageThinking
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPersistenceTest {

  private val sampleMessages: List<ChatMessage> =
    listOf(
      ChatMessageText(content = "Tell me a joke about cats", side = ChatSide.USER),
      ChatMessageLoading(), // Transient, must be filtered out.
      ChatMessageThinking(content = "The user wants a cat joke...", inProgress = false),
      ChatMessageText(content = "Why did the cat sit on the computer?", side = ChatSide.AGENT),
    )

  @Test
  fun toPersistedMessages_filtersTransientMessagesAndMapsRoles() {
    val persisted = sampleMessages.toPersistedMessages(nowMs = 1234L)

    assertEquals(3, persisted.size)
    assertEquals(PersistedMessageRole.USER, persisted[0].role)
    assertEquals("Tell me a joke about cats", persisted[0].content)
    assertEquals(PersistedMessageRole.THINKING, persisted[1].role)
    assertEquals(PersistedMessageRole.ASSISTANT, persisted[2].role)
    assertEquals(1234L, persisted[0].timestampMs)
  }

  @Test
  fun persistedJson_roundTripsThroughJsonAndBackToChatMessages() {
    val json = sampleMessages.toPersistedJson(nowMs = 42L)
    val restored = json.fromPersistedJson().toChatMessages()

    assertEquals(3, restored.size)

    val userMessage = restored[0] as ChatMessageText
    assertEquals(ChatSide.USER, userMessage.side)
    assertEquals("Tell me a joke about cats", userMessage.content)

    val thinkingMessage = restored[1] as ChatMessageThinking
    assertEquals(ChatMessageType.THINKING, thinkingMessage.type)
    assertEquals("The user wants a cat joke...", thinkingMessage.content)
    assertEquals(false, thinkingMessage.inProgress)

    val agentMessage = restored[2] as ChatMessageText
    assertEquals(ChatSide.AGENT, agentMessage.side)
    assertEquals("Why did the cat sit on the computer?", agentMessage.content)
  }

  @Test
  fun toJson_and_fromPersistedJson_roundTripPersistedMessages() {
    val persisted =
      listOf(
        PersistedMessage(role = PersistedMessageRole.USER, content = "hi", timestampMs = 1L),
        PersistedMessage(role = PersistedMessageRole.ASSISTANT, content = "hello", timestampMs = 2L),
      )
    assertEquals(persisted, persisted.toJson().fromPersistedJson())
  }

  @Test
  fun fromPersistedJson_returnsEmptyListForBlankOrInvalidJson() {
    assertTrue("".fromPersistedJson().isEmpty())
    assertTrue("   ".fromPersistedJson().isEmpty())
    assertTrue("not json at all".fromPersistedJson().isEmpty())
  }

  @Test
  fun fromPersistedJson_ignoresUnknownRoles() {
    val json =
      """[{"role":"user","content":"a","timestampMs":1},{"role":"mystery","content":"b","timestampMs":2}]"""
    val restored = json.fromPersistedJson().toChatMessages()
    assertEquals(1, restored.size)
    assertEquals(ChatSide.USER, restored[0].side)
  }

  @Test
  fun autoTitleFromMessages_usesFirstUserMessage() {
    assertEquals("Tell me a joke about cats", autoTitleFromMessages(sampleMessages))
  }

  @Test
  fun autoTitleFromMessages_capsLongTitlesAt40Chars() {
    val longContent = "a".repeat(100)
    val title =
      autoTitleFromMessages(listOf(ChatMessageText(content = longContent, side = ChatSide.USER)))
    assertEquals("a".repeat(CHAT_TITLE_MAX_LENGTH) + "…", title)
  }

  @Test
  fun autoTitleFromMessages_flattensNewlinesAndTrims() {
    val title =
      autoTitleFromMessages(
        listOf(ChatMessageText(content = "  hello\nworld  ", side = ChatSide.USER))
      )
    assertEquals("hello world", title)
  }

  @Test
  fun autoTitleFromMessages_fallsBackWhenNoUserMessage() {
    assertEquals("New chat", autoTitleFromMessages(listOf()))
    assertEquals(
      "New chat",
      autoTitleFromMessages(listOf(ChatMessageText(content = "agent", side = ChatSide.AGENT))),
    )
  }

  @Test
  fun toTranscriptBlock_labelsRolesAndSkipsThinking() {
    val transcript =
      listOf(
          PersistedMessage(role = PersistedMessageRole.USER, content = "What is 2+2?"),
          PersistedMessage(role = PersistedMessageRole.THINKING, content = "let me think"),
          PersistedMessage(role = PersistedMessageRole.ASSISTANT, content = "4"),
        )
        .toTranscriptBlock()
    assertEquals("User: What is 2+2?\nAssistant: 4", transcript)
  }

  @Test
  fun toTranscriptBlock_emptyForNoMessages() {
    assertEquals("", listOf<PersistedMessage>().toTranscriptBlock())
  }

  @Test
  fun buildSystemInstructionText_returnsNullWhenAllPartsBlank() {
    assertNull(buildSystemInstructionText(memory = "", agentOrSkillPrefix = "", priorTranscript = ""))
    assertNull(buildSystemInstructionText(memory = "  ", agentOrSkillPrefix = "\n", priorTranscript = ""))
  }

  @Test
  fun buildSystemInstructionText_composesPartsInOrder() {
    val text =
      buildSystemInstructionText(
        memory = "Respond in haiku.",
        agentOrSkillPrefix = "You are an agent.",
        priorTranscript = "User: hi\nAssistant: hello",
      )
    assertEquals(
      "You are an agent.\n\n" +
        "User preferences / memory:\nRespond in haiku.\n\n" +
        "Prior conversation:\nUser: hi\nAssistant: hello",
      text,
    )
  }

  @Test
  fun buildSystemInstructionText_memoryOnly() {
    assertEquals(
      "User preferences / memory:\nRespond in haiku.",
      buildSystemInstructionText(memory = "Respond in haiku."),
    )
  }

  @Test
  fun buildSystemInstructionText_transcriptOnly() {
    assertEquals(
      "Prior conversation:\nUser: hi",
      buildSystemInstructionText(memory = "", priorTranscript = "User: hi"),
    )
  }
}
