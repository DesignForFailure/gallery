# Roadmap: Chat Storage, LLM Memory, Thinking Toggle (Android / S24 Ultra)

## Context

Google AI Edge Gallery is an Android app (Jetpack Compose + Hilt + proto DataStore + LiteRT-LM engine) that runs on-device LLMs. It currently has **in-memory-only** chat, no cross-session LLM memory, and a "thinking mode" switch buried three taps deep inside the per-model config dialog. This roadmap describes the **simplest, most efficient** way to add:

1. **Multiple persistent chats** scoped to *AI Chat* (`LLM_CHAT`) and *Agent Chat* (`LLM_AGENT_CHAT`).
2. **LLM persistent memory** — a single global text blob injected as a system instruction on every init / reset.
3. **Prominent Normal / Thinking toggle** — the infrastructure is ~80 % wired; we just need to surface it.

Target device is the **Samsung Galaxy S24 Ultra** (Snapdragon 8 Gen 3, Hexagon NPU, 12 GB RAM, Android 14+). No need to be frugal with storage or memory; the NPU `Backend.NPU` path already supports `systemInstruction` through the same `Engine.createConversation` code path as CPU/GPU.

### Design decisions

- **Chat switch semantics:** *pseudo-replay via system prompt*. On switching to a stored chat, we compose a fresh LiteRT-LM `Conversation` with a system instruction that concatenates `{llm_memory}\n\nPrior conversation:\n{transcript}`. Fast, no inference cost on switch, model has readable context.
- **Scope:** AI Chat (`LLM_CHAT`) + Agent Chat (`LLM_AGENT_CHAT`) only. Ask Image, Audio Scribe, Prompt Lab unchanged.

---

## Files to create

1. `Android/src/app/src/main/java/com/google/ai/edge/gallery/ChatHistorySerializer.kt` — proto serializer (mirror `SettingsSerializer.kt`).
2. `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/ChatPersistence.kt` — JSON-serializable message DTO + converters + transcript builder + auto-title.
3. `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/ChatListDrawer.kt` — composable drawer for the chat list (new/switch/delete).

## Files to modify

### Feature 1 — Chat storage
- `Android/src/app/src/main/proto/settings.proto` — add `StoredChat`, `ChatHistoryCollection` messages.
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/di/AppModule.kt` — provide `DataStore<ChatHistoryCollection>` (new `chats.pb` file), inject into repo.
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/DataStoreRepository.kt` — add chat CRUD methods + active-id get/set.
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatViewModel.kt` — inject repo, add chat state (`_chats`, `activeChatIdByTask`), `loadChats`, `newChat`, `switchChat`, `deleteChat`, `persistCurrentChat`, `buildMemoryAndHistorySystemInstruction`.
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatScreen.kt` — wrap `ChatViewWrapper` in `ModalNavigationDrawer` when `taskId == LLM_CHAT`; hook `persistCurrentChat(model)` into `onGenerateResponseDone`; call `resetSession` with composed system instruction on `newChat` / `switchChat`.
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentChatScreen.kt` — same drawer wrapping for `LLM_AGENT_CHAT`; when switching chats, rebuild `systemInstruction` via `skillManagerViewModel.getSystemPrompt(curSystemPrompt)` **then append** the pseudo-replay transcript block, then call `resetSessionWithCurrentSkills`. Restore `curSystemPrompt` from the stored chat so the agent skill state follows the chat.
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/ModelPageAppBar.kt` — add optional `onOpenChatListClicked: (() -> Unit)?` param; render a `Icons.AutoMirrored.Rounded.Menu` icon when non-null.
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/ChatView.kt` — forward `onOpenChatListClicked` through to `ModelPageAppBar`.

### Feature 2 — LLM persistent memory
- `Android/src/app/src/main/proto/settings.proto` — add `string llm_memory = 11;` to `Settings` (field 11 is free; existing fields use 1–10).
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/DataStoreRepository.kt` — add `getLlmMemory()` / `setLlmMemory(memory: String)` (mirror `saveTextInputHistory`).
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/home/SettingsDialog.kt` — add a "LLM Memory" section with `OutlinedTextField` (minLines=3, maxLines=6) + Save button.
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt` — add `getLlmMemory()` / `saveLlmMemory(memory)`; on save, if a model is currently initialized, call `initializeModel(..., force = true)` so next turn uses the new instruction.
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatTaskModule.kt` — update `LlmChatTask`, `LlmAskImageTask`, `LlmAskAudioTask` to `@Inject` `DataStoreRepository`; in each `initializeModelFn` build `systemInstruction = Contents.of(listOf(Content.Text(memory)))` when memory is non-blank, pass to `model.runtimeHelper.initialize(...)`. Update each `@Provides fun provideTask(dataStoreRepository: DataStoreRepository): CustomTask` so Hilt auto-wires it.
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentChatTaskModule.kt` — same injection; memory becomes the *base* prefix that agent skill prompts are layered on top of (see `resetSessionWithCurrentSkills` at `AgentChatScreen.kt:537`).

### Feature 3 — Thinking mode toggle
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Config.kt` — at line 275, set `needReinitialization = false` on the `BooleanSwitchConfig` for `ENABLE_THINKING`. Thinking is a per-request flag (`extraContext["enable_thinking"]` at `LlmChatViewModel.kt:216`); no reinit needed. Without this fix, toggling causes a multi-second NPU reinit.
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/ModelPageAppBar.kt` — add an `IconButton` in the actions row (restructure the existing `Box` → `Row` to avoid fragile dp offset math). Icon: `Icons.Outlined.Psychology` when off, `Icons.Filled.Psychology` when on, tinted primary. Visibility gate: `task.allowThinking() && model.getBooleanConfigValue(ConfigKeys.SUPPORT_THINKING, false) && isModelInitialized`. On click: mutate `model.configValues[ConfigKeys.ENABLE_THINKING.label]` then call `modelManagerViewModel.updateConfigValuesUpdateTrigger()` (same path as `ConfigDialog`'s save). Use `val trigger by task.updateTrigger` so the icon recomposes when state changes from either toggle or dialog.

---

## Concrete details

### Proto additions (`settings.proto`)

```proto
message StoredChat {
  string id = 1;                 // UUID
  string title = 2;              // auto from first user message (40 char cap)
  int64  created_at_ms = 3;
  int64  updated_at_ms = 4;
  string model_name = 5;
  string task_id = 6;            // LLM_CHAT or LLM_AGENT_CHAT
  string messages_json = 7;      // JSON array of PersistedMessage
  string agent_system_prompt = 8; // Agent Chat only: remembered custom system prompt
}

message ChatHistoryCollection {
  repeated StoredChat chat = 1;
  map<string, string> active_chat_id_by_task = 2; // "llm_chat" -> uuid
}

// inside existing Settings message:
string llm_memory = 11;
```

### Persisted message DTO (`ChatPersistence.kt`)

```kotlin
@Serializable
data class PersistedMessage(
  val role: String,        // "user" | "assistant" | "thinking"
  val content: String,
  val timestampMs: Long,
)

fun List<ChatMessage>.toPersistedJson(): String   // filters: TEXT + THINKING only
fun String.fromPersistedJson(): List<ChatMessage> // rebuilds ChatMessageText / ChatMessageThinking
fun autoTitleFromMessages(messages: List<ChatMessage>): String  // first user msg, cap 40
fun List<PersistedMessage>.toTranscriptBlock(): String           // "User: ...\nAssistant: ..." joined with \n
```

### System-instruction composition (pseudo-replay)

Single shared helper in `ChatPersistence.kt`:

```kotlin
fun buildSystemInstruction(
  memory: String,
  agentOrSkillPrefix: String, // empty for LLM_CHAT; skill prompt for LLM_AGENT_CHAT
  priorTranscript: String,    // empty for new chats
): Contents? {
  val parts = buildList {
    if (agentOrSkillPrefix.isNotBlank()) add(agentOrSkillPrefix)
    if (memory.isNotBlank()) add("User preferences / memory:\n$memory")
    if (priorTranscript.isNotBlank()) add("Prior conversation:\n$priorTranscript")
  }
  return if (parts.isEmpty()) null
         else Contents.of(listOf(Content.Text(parts.joinToString("\n\n"))))
}
```

### Flow: send message

Unchanged. `LlmChatViewModel.generateResponse` still calls `runInference` with `extraContext` for thinking.

### Flow: turn done

Append to existing `ChatViewWrapper.onGenerateResponseDone` lambda (for LLM_CHAT) and `AgentChatScreen.onGenerateResponseDone` (for LLM_AGENT_CHAT):

```kotlin
viewModel.persistCurrentChat(model = model, task = task /*, agentPrompt for agent*/)
```

`persistCurrentChat` reads `uiState.value.messagesByModel[model.name]`, filters, serializes, upserts a `StoredChat` (new id if `activeChatIdByTask[taskId] == null`, auto-title on first save), sets active id.

### Flow: new chat

1. Generate new UUID, set `activeChatIdByTask[taskId] = id`.
2. `clearAllMessages(model)`.
3. Build system instruction with memory + agent prefix only (no transcript).
4. Call `resetSession(..., systemInstruction = sysInstr)`.

### Flow: switch chat

1. `persistCurrentChat(...)` to save current work.
2. Load target `StoredChat`, deserialize messages.
3. Replace `uiState.messagesByModel[model.name]` via the existing `addMessage` loop (or a new `setMessages` helper on `ChatViewModel` to avoid N state updates — recommended).
4. For Agent Chat: restore `curSystemPrompt = stored.agentSystemPrompt`, rebuild skill prefix via `skillManagerViewModel.getSystemPrompt(curSystemPrompt)`.
5. Build system instruction: memory + (skill prefix if agent) + transcript from loaded messages.
6. `resetSession(..., systemInstruction = sysInstr)`.

### Flow: delete chat

Remove from repo. If it was active, call `newChat(...)`.

### Thinking toggle layout fix

Replace in `ModelPageAppBar.kt` the `actions = { Box { ... } }` with `actions = { Row(horizontalArrangement = Arrangement.End) { ... } }`, containing in order: chat-list icon (if `onOpenChatListClicked != null`), reset-session icon (if `canShowResetSessionButton`), thinking toggle (if visible), config icon (if `showConfigButton`). Removes all `configButtonOffset` dp math.

---

## S24 Ultra notes

- Gemma 4 / Gemma 3n / Qwen3 on the S24 Ultra support thinking mode and will render properly in `ChatMessageThinking` bubbles — no device-specific work needed.
- NPU path (`Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)` at `LlmChatModelHelper.kt:100`) already passes `systemInstruction` through `ConversationConfig` the same way as CPU/GPU; memory injection works on NPU.
- 12 GB RAM + large internal storage → we don't need to cap chat count or prune transcripts. Plan assumes no cap in v1.
- Pseudo-replay transcript length is bounded by the user's max context tokens (`ConfigKeys.MAX_TOKENS`); for very long chats the transcript may be truncated. v1 keeps the full transcript; if the model errors on overflow, users can create a new chat. A future iteration can add a sliding-window truncation helper in `toTranscriptBlock`.

---

## Verification

```bash
cd Android/src
./gradlew :app:installDebug
```

Manual flow on the S24 Ultra (Android 14), debug APK, a Gemma 4 model that supports thinking:

1. **Memory** — Open gear icon → enter `"Always respond in haiku format."` → Save. Open AI Chat → ask "What is the capital of France?" → expect haiku response.
2. **Chat storage (AI Chat)** — New menu icon in chat app bar → drawer opens. Tap "+ New Chat", send "Tell me a joke about cats". Tap "+ New Chat" again, send "Explain quantum tunneling". Switch back to first chat: messages reappear, send follow-up "and another one" → model replies referencing cats (pseudo-replay working). Kill app, relaunch → both chats still listed with active one restored.
3. **Delete** — Trailing delete on one chat → it disappears; the remaining chat stays.
4. **Chat storage (Agent Chat)** — Repeat step 2 in Agent Chat. Switch chats → agent skill prompt + memory + transcript all stitched into the system instruction; agent skills still work inside the restored chat.
5. **Thinking toggle** — Tap the Psychology icon in the app bar → icon fills. Send "What is 47 × 38?" → a `ChatMessageThinking` bubble appears before the answer. Observe: **no reinit spinner** (confirming `needReinitialization = false`). Toggle off → next reply has no thinking bubble.
6. **Cross-feature** — With memory set, thinking on, two stored chats: verify all three interact correctly and survive an app restart.

### Automated checks

- `./gradlew :app:assembleDebug` — compiles with new proto fields.
- `./gradlew :app:lintDebug` — no new lint errors.
- Existing unit tests should remain green; add a small test for `toPersistedJson` / `fromPersistedJson` round-trip if there's an existing test module.

---

## Critical files (most likely to need hand-tuning)

- `Android/src/app/src/main/proto/settings.proto`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/DataStoreRepository.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatViewModel.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatScreen.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentChatScreen.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/ModelPageAppBar.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Config.kt`
