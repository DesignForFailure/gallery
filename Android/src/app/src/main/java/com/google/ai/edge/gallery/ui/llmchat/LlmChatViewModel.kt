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

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.proto.StoredChat
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageAudioClip
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageError
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageLoading
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageThinking
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.ChatViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ToolProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "AGLlmChatViewModel"

/**
 * Builds the system instruction for a chat session.
 *
 * Receives the global LLM memory, the pseudo-replay transcript of the chat being (re-)activated
 * (empty for new chats), and the agent system prompt associated with the chat (only used by Agent
 * Chat).
 */
typealias ChatSystemInstructionBuilder =
  (memory: String, transcript: String, agentSystemPrompt: String) -> Contents?

/** Resets the underlying LLM session with the given system instruction. */
typealias ChatSessionResetter =
  (task: Task, model: Model, systemInstruction: Contents?, onDone: () -> Unit) -> Unit

@OptIn(ExperimentalApi::class)
open class LlmChatViewModelBase(val dataStoreRepository: DataStoreRepository) : ChatViewModel() {
  private val _chats = MutableStateFlow<List<StoredChat>>(listOf())
  /** Stored chats for this view model's task, sorted by last update (most recent first). */
  val chats = _chats.asStateFlow()

  private val _activeChatId = MutableStateFlow("")
  val activeChatId = _activeChatId.asStateFlow()

  fun getLlmMemory(): String = dataStoreRepository.getLlmMemory()

  /** Loads stored chats and the active chat id for the given task from the data store. */
  fun loadChats(taskId: String) {
    viewModelScope.launch(Dispatchers.Default) {
      _chats.value = readChatsForTask(taskId)
      _activeChatId.value = dataStoreRepository.getActiveChatId(taskId)
    }
  }

  /**
   * Restores the active stored chat (if any) into the UI for the given model.
   *
   * Called when the chat screen is (re-)entered. The LLM session itself is restored separately:
   * model initialization composes its system instruction from the active chat's transcript.
   */
  fun restoreActiveChat(taskId: String, model: Model, onRestored: (StoredChat) -> Unit = {}) {
    viewModelScope.launch(Dispatchers.Default) {
      val chatsForTask = readChatsForTask(taskId)
      _chats.value = chatsForTask
      val activeId = dataStoreRepository.getActiveChatId(taskId)
      _activeChatId.value = activeId
      if (activeId.isEmpty()) {
        return@launch
      }
      val activeChat = chatsForTask.find { it.id == activeId } ?: return@launch
      // Only restore when the UI has no messages yet, so an in-memory conversation is never
      // clobbered (e.g. when the view model survives a navigation).
      if (uiState.value.messagesByModel[model.name].isNullOrEmpty()) {
        setMessages(model = model, messages = activeChat.messagesJson.fromPersistedJson().toChatMessages())
      }
      onRestored(activeChat)
    }
  }

  /**
   * Saves the current in-memory conversation to the data store, creating a new stored chat (with
   * an auto-generated title) on first save.
   */
  fun persistCurrentChat(model: Model, taskId: String, agentSystemPrompt: String = "") {
    viewModelScope.launch(Dispatchers.Default) {
      persistCurrentChatSync(model = model, taskId = taskId, agentSystemPrompt = agentSystemPrompt)
    }
  }

  /** Starts a new empty chat: persists the current one, then resets the session. */
  fun newChat(
    task: Task,
    model: Model,
    agentSystemPrompt: String = "",
    systemInstructionBuilder: ChatSystemInstructionBuilder,
    sessionResetter: ChatSessionResetter,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      persistCurrentChatSync(model = model, taskId = task.id, agentSystemPrompt = agentSystemPrompt)

      val newChatId = UUID.randomUUID().toString()
      dataStoreRepository.setActiveChatId(taskId = task.id, chatId = newChatId)
      _activeChatId.value = newChatId

      val systemInstruction =
        systemInstructionBuilder(getLlmMemory(), "", agentSystemPrompt)
      sessionResetter(task, model, systemInstruction) {}
    }
  }

  /**
   * Switches to a stored chat: persists the current one, resets the session with a pseudo-replay
   * system instruction containing the stored transcript, then restores the stored messages.
   */
  fun switchChat(
    task: Task,
    model: Model,
    targetChat: StoredChat,
    currentAgentSystemPrompt: String = "",
    systemInstructionBuilder: ChatSystemInstructionBuilder,
    sessionResetter: ChatSessionResetter,
    onActivated: (StoredChat) -> Unit = {},
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      persistCurrentChatSync(
        model = model,
        taskId = task.id,
        agentSystemPrompt = currentAgentSystemPrompt,
      )

      dataStoreRepository.setActiveChatId(taskId = task.id, chatId = targetChat.id)
      _activeChatId.value = targetChat.id

      val persistedMessages = targetChat.messagesJson.fromPersistedJson()
      val systemInstruction =
        systemInstructionBuilder(
          getLlmMemory(),
          persistedMessages.toTranscriptBlock(),
          targetChat.agentSystemPrompt,
        )
      onActivated(targetChat)
      sessionResetter(task, model, systemInstruction) {
        setMessages(model = model, messages = persistedMessages.toChatMessages())
      }
    }
  }

  /** Deletes a stored chat. If it was the active one, starts a fresh session. */
  fun deleteChat(
    task: Task,
    model: Model,
    chatId: String,
    agentSystemPrompt: String = "",
    systemInstructionBuilder: ChatSystemInstructionBuilder,
    sessionResetter: ChatSessionResetter,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      dataStoreRepository.deleteChat(chatId = chatId)
      _chats.value = _chats.value.filter { it.id != chatId }
      if (_activeChatId.value == chatId) {
        dataStoreRepository.setActiveChatId(taskId = task.id, chatId = "")
        _activeChatId.value = ""
        val systemInstruction = systemInstructionBuilder(getLlmMemory(), "", agentSystemPrompt)
        sessionResetter(task, model, systemInstruction) {}
      }
    }
  }

  private fun persistCurrentChatSync(model: Model, taskId: String, agentSystemPrompt: String) {
    val messages = uiState.value.messagesByModel[model.name] ?: listOf()
    val persistedMessages = messages.toPersistedMessages()
    if (persistedMessages.isEmpty()) {
      return
    }

    val nowMs = System.currentTimeMillis()
    var chatId = _activeChatId.value
    if (chatId.isEmpty()) {
      chatId = UUID.randomUUID().toString()
      _activeChatId.value = chatId
    }
    val existingChat = _chats.value.find { it.id == chatId }

    val storedChat =
      StoredChat.newBuilder()
        .setId(chatId)
        .setTitle(existingChat?.title?.ifEmpty { null } ?: autoTitleFromMessages(messages))
        .setCreatedAtMs(existingChat?.createdAtMs ?: nowMs)
        .setUpdatedAtMs(nowMs)
        .setModelName(model.name)
        .setTaskId(taskId)
        .setMessagesJson(persistedMessages.toJson())
        .setAgentSystemPrompt(agentSystemPrompt)
        .build()

    dataStoreRepository.upsertChat(storedChat)
    dataStoreRepository.setActiveChatId(taskId = taskId, chatId = chatId)
    _chats.value =
      (listOf(storedChat) + _chats.value.filter { it.id != chatId }).sortedByDescending {
        it.updatedAtMs
      }
  }

  private fun readChatsForTask(taskId: String): List<StoredChat> {
    return dataStoreRepository
      .getAllChats()
      .filter { it.taskId == taskId }
      .sortedByDescending { it.updatedAtMs }
  }

  fun generateResponse(
    model: Model,
    input: String,
    images: List<Bitmap> = listOf(),
    audioMessages: List<ChatMessageAudioClip> = listOf(),
    onFirstToken: (Model) -> Unit = {},
    onDone: () -> Unit = {},
    onError: (String) -> Unit,
    allowThinking: Boolean = false,
  ) {
    val accelerator = model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = "")
    viewModelScope.launch(Dispatchers.Default) {
      setInProgress(true)
      setPreparing(true)

      // Loading.
      addMessage(model = model, message = ChatMessageLoading(accelerator = accelerator))

      // Wait for instance to be initialized.
      while (model.instance == null) {
        delay(100)
      }
      delay(500)

      // Run inference.
      val audioClips: MutableList<ByteArray> = mutableListOf()
      for (audioMessage in audioMessages) {
        audioClips.add(audioMessage.genByteArrayForWav())
      }

      var firstRun = true
      val start = System.currentTimeMillis()

      try {
        val resultListener: (String, Boolean, String?) -> Unit =
          { partialResult, done, partialThinkingResult ->
            if (partialResult.startsWith("<ctrl")) {
              // Do nothing. Ignore control tokens.
            } else {
              // Remove the last message if it is a "loading" message.
              // This will only be done once.
              val lastMessage = getLastMessage(model = model)
              val wasLoading = lastMessage?.type == ChatMessageType.LOADING
              if (wasLoading) {
                removeLastMessage(model = model)
              }

              val thinkingText = partialThinkingResult
              val isThinking = thinkingText != null && thinkingText.isNotEmpty()
              var currentLastMessage = getLastMessage(model = model)

              // If thinking is enabled, add a thinking message.
              if (isThinking) {
                if (currentLastMessage?.type != ChatMessageType.THINKING) {
                  addMessage(
                    model = model,
                    message =
                      ChatMessageThinking(
                        content = "",
                        inProgress = true,
                        side = ChatSide.AGENT,
                        accelerator = accelerator,
                        hideSenderLabel =
                          currentLastMessage?.type == ChatMessageType.COLLAPSABLE_PROGRESS_PANEL,
                      ),
                  )
                }
                updateLastThinkingMessageContentIncrementally(
                  model = model,
                  partialContent = thinkingText!!,
                )
              } else {
                if (currentLastMessage?.type == ChatMessageType.THINKING) {
                  val thinkingMsg = currentLastMessage as ChatMessageThinking
                  if (thinkingMsg.inProgress) {
                    replaceLastMessage(
                      model = model,
                      message =
                        ChatMessageThinking(
                          content = thinkingMsg.content,
                          inProgress = false,
                          side = thinkingMsg.side,
                          accelerator = thinkingMsg.accelerator,
                          hideSenderLabel = thinkingMsg.hideSenderLabel,
                        ),
                      type = ChatMessageType.THINKING,
                    )
                  }
                }
                currentLastMessage = getLastMessage(model = model)
                if (
                  currentLastMessage?.type != ChatMessageType.TEXT ||
                    currentLastMessage.side != ChatSide.AGENT
                ) {
                  // Add an empty message that will receive streaming results.
                  addMessage(
                    model = model,
                    message =
                      ChatMessageText(
                        content = "",
                        side = ChatSide.AGENT,
                        accelerator = accelerator,
                        hideSenderLabel =
                          currentLastMessage?.type == ChatMessageType.COLLAPSABLE_PROGRESS_PANEL ||
                            currentLastMessage?.type == ChatMessageType.THINKING,
                      ),
                  )
                }

                // Incrementally update the streamed partial results.
                val latencyMs: Long = if (done) System.currentTimeMillis() - start else -1
                if (partialResult.isNotEmpty() || wasLoading || done) {
                  updateLastTextMessageContentIncrementally(
                    model = model,
                    partialContent = partialResult,
                    latencyMs = latencyMs.toFloat(),
                  )
                }
              }

              if (firstRun) {
                firstRun = false
                setPreparing(false)
                onFirstToken(model)
              }

              if (done) {
                val finalLastMessage = getLastMessage(model = model)
                if (finalLastMessage?.type == ChatMessageType.THINKING) {
                  val thinkingMsg = finalLastMessage as ChatMessageThinking
                  if (thinkingMsg.inProgress) {
                    replaceLastMessage(
                      model = model,
                      message =
                        ChatMessageThinking(
                          content = thinkingMsg.content,
                          inProgress = false,
                          side = thinkingMsg.side,
                          accelerator = thinkingMsg.accelerator,
                          hideSenderLabel = thinkingMsg.hideSenderLabel,
                        ),
                      type = ChatMessageType.THINKING,
                    )
                  }
                }
                setInProgress(false)
                onDone()
              }
            }
          }

        val cleanUpListener: () -> Unit = {
          setInProgress(false)
          setPreparing(false)
        }

        val errorListener: (String) -> Unit = { message ->
          Log.e(TAG, "Error occurred while running inference")
          setInProgress(false)
          setPreparing(false)
          onError(message)
        }

        val enableThinking =
          allowThinking &&
            model.getBooleanConfigValue(key = ConfigKeys.ENABLE_THINKING, defaultValue = false)
        val extraContext = if (enableThinking) mapOf("enable_thinking" to "true") else null

        model.runtimeHelper.runInference(
          model = model,
          input = input,
          images = images,
          audioClips = audioClips,
          resultListener = resultListener,
          cleanUpListener = cleanUpListener,
          onError = errorListener,
          coroutineScope = viewModelScope,
          extraContext = extraContext,
        )
      } catch (e: Exception) {
        Log.e(TAG, "Error occurred while running inference", e)
        setInProgress(false)
        setPreparing(false)
        onError(e.message ?: "")
      }
    }
  }

  fun stopResponse(model: Model) {
    Log.d(TAG, "Stopping response for model ${model.name}...")
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }
    setInProgress(false)
    model.runtimeHelper.stopResponse(model)
    Log.d(TAG, "Done stopping response")
  }

  fun resetSession(
    task: Task,
    model: Model,
    systemInstruction: Contents? = null,
    tools: List<ToolProvider> = listOf(),
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
    onDone: () -> Unit = {},
    enableConversationConstrainedDecoding: Boolean = false,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      setIsResettingSession(true)
      clearAllMessages(model = model)
      stopResponse(model = model)

      while (true) {
        try {
          model.runtimeHelper.resetConversation(
            model = model,
            supportImage = supportImage,
            supportAudio = supportAudio,
            systemInstruction = systemInstruction,
            tools = tools,
            enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
          )
          break
        } catch (e: Exception) {
          Log.d(TAG, "Failed to reset session. Trying again")
        }
        delay(200)
      }
      setIsResettingSession(false)
      onDone()
    }
  }

  fun runAgain(
    model: Model,
    message: ChatMessageText,
    onError: (String) -> Unit,
    allowThinking: Boolean = false,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      // Wait for model to be initialized.
      while (model.instance == null) {
        delay(100)
      }

      // Clone the clicked message and add it.
      addMessage(model = model, message = message.clone())

      // Run inference.
      generateResponse(
        model = model,
        input = message.content,
        onError = onError,
        allowThinking = allowThinking,
      )
    }
  }

  fun handleError(
    context: Context,
    task: Task,
    model: Model,
    modelManagerViewModel: ModelManagerViewModel,
    errorMessage: String,
  ) {
    // Remove the "loading" message.
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }

    // Show error message.
    addMessage(model = model, message = ChatMessageError(content = errorMessage))

    // Clean up and re-initialize.
    viewModelScope.launch(Dispatchers.Default) {
      modelManagerViewModel.cleanupModel(
        context = context,
        task = task,
        model = model,
        onDone = {
          modelManagerViewModel.initializeModel(context = context, task = task, model = model)

          // Add a warning message for re-initializing the session.
          addMessage(
            model = model,
            message = ChatMessageWarning(content = "Session re-initialized"),
          )
        },
      )
    }
  }
}

@HiltViewModel
class LlmChatViewModel @Inject constructor(dataStoreRepository: DataStoreRepository) :
  LlmChatViewModelBase(dataStoreRepository)

@HiltViewModel
class LlmAskImageViewModel @Inject constructor(dataStoreRepository: DataStoreRepository) :
  LlmChatViewModelBase(dataStoreRepository)

@HiltViewModel
class LlmAskAudioViewModel @Inject constructor(dataStoreRepository: DataStoreRepository) :
  LlmChatViewModelBase(dataStoreRepository)
