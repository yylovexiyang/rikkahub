# 大纲功能实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 RikkaHub 添加可选的"先大纲、后正文"生成流程：开关开启后发送被拦截 → 大纲界面（流式生成初稿/编辑/重新生成）→ 确认后大纲原文作为用户消息生成正文，原始输入不进上下文（存入消息元数据）。

**Architecture:** 正文生成完全复用现有 `ChatService.sendMessage` 链路（零改动）；大纲生成在 `ChatService` 中以 provider 直调 + 流式的方式独立实现（仿 `compressConversation`，但带对话历史与助手 system prompt）；原始输入写入用户消息 Text part 的 `metadata: {"originalInput": ...}`；大纲配置（开关/提示词/模型）为助手级单配置，设置页仿 `AssistantBasicPage`。

**Tech Stack:** Kotlin、Jetpack Compose（Material3 ModalBottomSheet）、kotlinx-serialization、JUnit 4、Koin

**设计文档:** `docs/superpowers/specs/2026-10-12-outline-feature-design.md`（实现前请先读一遍）

**环境注意:** Windows + PowerShell，gradle 命令用 `.\gradlew`。编译验证：`.\gradlew :app:compileDebugKotlin`；单测：`.\gradlew :app:testDebugUnitTest --tests "<TestClassName>"`。

**验证状态标记:** 无 UI 自动化设施，UI 相关任务以"编译通过 + 手动验证清单"收尾。

---

## Task 1: OutlineConfig 数据模型（TDD）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/model/OutlineConfigTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/me/rerere/rikkahub/data/model/OutlineConfigTest.kt`：

```kotlin
package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 大纲配置序列化回归测试：老版本数据（无 outlineConfig 字段）反序列化后应补默认值
class OutlineConfigTest {
    private val json = Json { ignoreUnknownKeys = true }

    // 老数据缺字段时反序列化得到默认值，无需迁移
    @Test
    fun `missing outlineConfig deserializes with default values`() {
        val legacy = """{"systemPrompt":"你是助手","temperature":0.7}"""
        val assistant = json.decodeFromString<Assistant>(legacy)
        assertFalse(assistant.outlineConfig.enabled)
        assertEquals(OutlineConfig.DEFAULT_OUTLINE_PROMPT, assistant.outlineConfig.prompt)
        assertNull(assistant.outlineConfig.modelId)
    }

    // 修改后的 outlineConfig 可完整序列化/反序列化
    @Test
    fun `outlineConfig roundtrip keeps values`() {
        val origin = Assistant().copy(
            outlineConfig = OutlineConfig(enabled = true, prompt = "自定义提示词")
        )
        val restored = json.decodeFromString<Assistant>(json.encodeToString(origin))
        assertTrue(restored.outlineConfig.enabled)
        assertEquals("自定义提示词", restored.outlineConfig.prompt)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
.\gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.model.OutlineConfigTest"
```

预期：编译失败，报 `Unresolved reference: OutlineConfig` / `outlineConfig`。

- [ ] **Step 3: 实现数据模型**

`Assistant.kt` 顶部的 `Assistant` 数据类（`@Serializable` 注解下，字段列表末尾 `allowConversationPromptInjection` 之后）追加字段：

```kotlin
    val outlineConfig: OutlineConfig = OutlineConfig(), // 大纲功能配置（助手级）
```

然后在 `Assistant` 数据类之后（`QuickMessage` 之前）追加类定义：

```kotlin
/**
 * 大纲功能配置（助手级单配置）
 *
 * 大纲开启后，用户点击发送先拦截生成大纲草稿；用户确认后，
 * 大纲原文直接作为用户消息进入上下文；原始输入仅用于生成大纲，
 * 写入消息元数据（见 utils/OutlineUtils.kt），不会作为上下文发送。
 */
@Serializable
data class OutlineConfig(
    val enabled: Boolean = false,                // 总开关
    val prompt: String = DEFAULT_OUTLINE_PROMPT, // 大纲生成提示词模板
    val modelId: Uuid? = null,                   // 大纲生成专用模型，null = 跟随当前聊天模型
) {
    companion object {
        /**
         * 默认大纲生成提示词
         * 占位符：{input}=用户原始输入 {locale}=系统语言（经 applyPlaceholders 渲染）
         */
        const val DEFAULT_OUTLINE_PROMPT = "请先不要直接完成任务，而是基于对话上下文与语言环境（{locale}），针对以下需求制定一份条目清晰的大纲：\n{input}\n\n要求：只输出大纲本身，按编号条目组织，涵盖任务的关键步骤/章节，简明扼要。"
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

同 Step 2 命令。预期：2 个测试 PASS。

- [ ] **Step 5: 提交**

```powershell
git add app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt app/src/test/java/me/rerere/rikkahub/data/model/OutlineConfigTest.kt
git commit -m "feat(outline): 新增助手级大纲配置数据模型"
```

---

## Task 2: OutlineUtils 元数据工具（TDD）

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/utils/OutlineUtils.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/utils/OutlineUtilsTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/me/rerere/rikkahub/utils/OutlineUtilsTest.kt`：

```kotlin
package me.rerere.rikkahub.utils

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// 大纲消息元数据读写测试：原始输入随消息持久化，界面可读取
class OutlineUtilsTest {

    // 写入后可读回原始输入
    @Test
    fun `attach then read returns original input`() {
        val parts = listOf(UIMessagePart.Text("一、背景\n二、方法\n三、结论"))
        val attached = parts.attachOutlineOriginalInput("帮我写一份实验报告")
        val read = (attached.first() as UIMessagePart.Text).readOutlineOriginalInput()
        assertEquals("帮我写一份实验报告", read)
    }

    // 保留部件已有的其他元数据键，不互相覆盖
    @Test
    fun `attach keeps existing metadata keys`() {
        val known = UIMessagePart.Text(
            "x",
            metadata = buildJsonObject { put("foo", JsonPrimitive("bar")) },
        )
        val attached = listOf(known).attachOutlineOriginalInput("需求")
        val metadata = (attached.first() as UIMessagePart.Text).metadata!!
        assertEquals("bar", metadata["foo"]?.jsonPrimitive?.content)
        assertEquals("需求", metadata[OUTLINE_META_KEY]?.jsonPrimitive?.content)
    }

    // 未写过元数据的普通部件读出 null（普通消息不受影响）
    @Test
    fun `read returns null for normal message`() {
        val text = UIMessagePart.Text("普通输入")
        assertNull(text.readOutlineOriginalInput())
    }

    // 原始输入为空白时不写入，列表原样返回
    @Test
    fun `attach ignores blank input`() {
        val parts = listOf(UIMessagePart.Text("t"))
        assertEquals(parts, parts.attachOutlineOriginalInput("  "))
    }

    // 列表中没有文本部件时原样返回
    @Test
    fun `attach returns original list when no text part`() {
        val parts = listOf(UIMessagePart.Image("file://a.png"))
        assertEquals(parts, parts.attachOutlineOriginalInput("需求"))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
.\gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.utils.OutlineUtilsTest"
```

预期：编译失败，报 `Unresolved reference: attachOutlineOriginalInput`。

- [ ] **Step 3: 实现工具函数**

创建 `app/src/main/java/me/rerere/rikkahub/utils/OutlineUtils.kt`：

```kotlin
package me.rerere.rikkahub.utils

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart

// 大纲消息元数据键：记录生成大纲所用的用户原始输入
const val OUTLINE_META_KEY = "originalInput"

/**
 * 将大纲流程的用户原始输入写入第一个文本部件的元数据
 *
 * @param originalInput 用户原始输入；null/空白时原样返回
 * @return 携带元数据的新部件列表；无文本部件时原样返回
 */
fun List<UIMessagePart>.attachOutlineOriginalInput(originalInput: String?): List<UIMessagePart> {
    if (originalInput.isNullOrBlank()) return this
    val index = indexOfFirst { it is UIMessagePart.Text }
    if (index < 0) return this
    val textPart = this[index] as UIMessagePart.Text
    // 保留部件已有的元数据键，再写入原始输入
    val metadata = buildJsonObject {
        textPart.metadata?.forEach { (key, value) -> put(key, value) }
        put(OUTLINE_META_KEY, originalInput)
    }
    return mapIndexed { i, part -> if (i == index) textPart.copy(metadata = metadata) else part }
}

/**
 * 读取文本部件中记录的大纲原始输入；无记录时返回 null
 */
fun UIMessagePart.Text.readOutlineOriginalInput(): String? {
    // 防御非字符串元数据导致的解析异常
    return runCatching {
        metadata?.get(OUTLINE_META_KEY)?.jsonPrimitive?.contentOrNull
    }.getOrNull()
}
```

- [ ] **Step 4: 运行测试确认通过**

同 Step 2 命令。预期：5 个测试 PASS。

- [ ] **Step 5: 提交**

```powershell
git add app/src/main/java/me/rerere/rikkahub/utils/OutlineUtils.kt app/src/test/java/me/rerere/rikkahub/utils/OutlineUtilsTest.kt
git commit -m "feat(outline): 新增大纲原始输入元数据读写工具"
```

---

## Task 3: ChatService 大纲生成链路

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt`（无）
- Modify: `app/src/main/res/values/strings.xml`

无单元测试（ChatService 重依赖 Application/网络，项目现无其测试先例），以编译 + 手动验证收尾。

- [ ] **Step 1: 添加 strings 资源**

`app/src/main/res/values/strings.xml` 中 `<resources>` 内追加：

```xml
  <string name="error_title_generate_outline">Generate outline failed</string>
```

- [ ] **Step 2: 定义 OutlineState**

`ChatService.kt` 中，在 `ChatError` 数据类（约 L96-103）之后添加：

```kotlin
// 大纲生成状态（按会话隔离，仅内存态；确认大纲后即消费，不持久化）
data class OutlineState(
    val rawInput: String = "",          // 生成大纲所用的用户原始输入
    val draft: String = "",             // 流式累积的大纲草稿
    val isGenerating: Boolean = false,  // 是否正在流式生成
    val error: Throwable? = null,       // 生成失败时的异常
)
```

- [ ] **Step 3: 添加状态与 Job 容器**

`ChatService` 类内，在 `_errors` / `errors` 属性（约 L146-147）之后添加：

```kotlin
    // 大纲生成：状态按会话隔离流 + 进行中的任务表
    private val outlineJobs = ConcurrentHashMap<Uuid, Job>()
    private val _outlineStates = MutableStateFlow<Map<Uuid, OutlineState>>(emptyMap())
    val outlineStates: StateFlow<Map<Uuid, OutlineState>> = _outlineStates.asStateFlow()

    // 更新指定会话的大纲状态
    private fun setOutlineState(conversationId: Uuid, update: (OutlineState) -> OutlineState) {
        _outlineStates.update { map ->
            map + (conversationId to update(map[conversationId] ?: OutlineState()))
        }
    }
```

- [ ] **Step 4: 实现 generateOutline / stopOutlineGeneration**

`ChatService` 类内，在 `compressConversation` 方法（约 L801-885）之后、"// ---- 通知 ----" 注释之前添加：

```kotlin
    // ---- 生成大纲 ----

    /**
     * 生成大纲草稿：provider 直调 + 流式，独立于正文生成管线
     *
     * 消息组装：助手 system prompt（允许时含会话级覆盖）+ 对话历史（尊重上下文限制）
     * + 大纲提示词（{input}/{locale} 占位符已渲染）。不带工具/注入/记忆。
     *
     * @param rawInput 用户原始输入（仅用于生成大纲，不进入上下文）
     */
    fun generateOutline(conversationId: Uuid, rawInput: String): Job {
        // 同一会话重复触发（如重新生成）时先取消旧任务
        outlineJobs[conversationId]?.cancel()

        setOutlineState(conversationId) {
            OutlineState(rawInput = rawInput, isGenerating = true)
        }

        val job = appScope.launch {
            try {
                val settings = settingsStore.settingsFlow.first()
                val conversation = getConversationFlow(conversationId).value
                val assistant = settings.getAssistantById(conversation.assistantId)
                    ?: settings.getCurrentAssistant()

                // 模型：大纲专用模型优先，否则跟随当前聊天模型
                val model =
                    settings.findModelById(assistant.outlineConfig.modelId ?: assistant.chatModelId ?: settings.chatModelId)
                        ?: throw IllegalStateException(
                            context.getString(R.string.error_title_generate_outline)
                        )
                val provider = model.findProvider(settings.providers)
                    ?: throw IllegalStateException("Provider not found")

                // 大纲请求的 system prompt 与正文一致（允许会话级覆盖），但不带注入/记忆/工具
                val systemPrompt = if (
                    assistant.allowConversationSystemPrompt && !conversation.customSystemPrompt.isNullOrBlank()
                ) {
                    conversation.customSystemPrompt
                } else {
                    assistant.systemPrompt
                }

                val prompt = assistant.outlineConfig.prompt.applyPlaceholders(
                    "input" to rawInput,
                    "locale" to Locale.getDefault().displayName,
                )
                val messages = buildList {
                    if (systemPrompt.isNotBlank()) {
                        add(UIMessage.system(prompt = systemPrompt))
                    }
                    // 尊重助手配置的上下文窗口限制
                    addAll(
                        conversation.currentMessages.limitContext(assistant.contextMessageSize)
                    )
                    add(UIMessage.user(prompt = prompt))
                }

                val providerImpl = providerManager.getProviderByType(provider)
                providerImpl.streamText(
                    providerSetting = provider,
                    messages = messages,
                    params = TextGenerationParams(
                        model = model,
                        reasoningLevel = ReasoningLevel.OFF,
                    ),
                ).collect { chunk ->
                    // 只累积文本增量作为草稿（忽略 reasoning 等其它部件）
                    val text = chunk.choices.getOrNull(0)?.delta?.parts
                        ?.filterIsInstance<UIMessagePart.Text>()
                        ?.joinToString("") { it.text }
                        ?: ""
                    if (text.isNotEmpty()) {
                        setOutlineState(conversationId) { it.copy(draft = it.draft + text) }
                    }
                }

                setOutlineState(conversationId) { it.copy(isGenerating = false) }
            } catch (e: CancellationException) {
                // 用户停止：保留已生成草稿，不记为错误
                setOutlineState(conversationId) { it.copy(isGenerating = false) }
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                setOutlineState(conversationId) { it.copy(isGenerating = false, error = e) }
            } finally {
                outlineJobs.remove(conversationId)
            }
        }
        outlineJobs[conversationId] = job
        return job
    }

    /**
     * 停止大纲生成（保留已生成草稿，转入可编辑态）
     */
    fun stopOutlineGeneration(conversationId: Uuid) {
        outlineJobs[conversationId]?.cancel()
    }
```

- [ ] **Step 5: 补 import 与会话回收清理**

`ChatService.kt` 顶部 import 区补：

```kotlin
import me.rerere.ai.ui.limitContext
```

（`Me.rerere.ai.ui.UIMessagePart`、`UIMessage`、`ReasoningLevel`、`TextGenerationParams`、`Locale`、`CancellationException`、`ConcurrentHashMap` 均已 import，无需重复添加。）

`removeSession` 方法（约 L216-227）中，`session.cleanup()` 之前添加一行：

```kotlin
        // 清理该会话的大纲草稿内存态
        _outlineStates.update { it - conversationId }
        outlineJobs.remove(conversationId)?.cancel()
```

- [ ] **Step 6: 编译验证**

```powershell
.\gradlew :app:compileDebugKotlin
```

预期：BUILD SUCCESSFUL。

- [ ] **Step 7: 提交**

```powershell
git add app/src/main/java/me/rerere/rikkahub/service/ChatService.kt app/src/main/res/values/strings.xml
git commit -m "feat(outline): 实现独立的流式大纲生成链路"
```

---

## Task 4: ChatVM 暴露大纲能力

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt`

- [ ] **Step 1: 扩展 handleMessageSend 增加元数据写入**

`ChatVM.kt` 的 `handleMessageSend`（约 L175-180）整体替换为：

```kotlin
    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     * @param outlineOriginalInput 大纲流程的用户原始输入；写入消息文本部件元数据（不进入上下文）
     */
    fun handleMessageSend(
        content: List<UIMessagePart>,
        answer: Boolean = true,
        outlineOriginalInput: String? = null,
    ) {
        if (content.isEmptyInputMessage()) return
        // 大纲流程单独埋点，便于区分使用量
        if (outlineOriginalInput != null) {
            analytics.logEvent("ai_send_outline_message", null)
        } else {
            analytics.logEvent("ai_send_message", null)
        }
        // 原始输入随消息持久化（气泡上可查看），但不会作为上下文发送
        val processedContent = content.attachOutlineOriginalInput(outlineOriginalInput)
        chatService.sendMessage(_conversationId, processedContent, answer)
    }
```

- [ ] **Step 2: 添加大纲状态与方法**

`ChatVM` 类内，`processingStatus` 属性（约 L73-75）之后添加：

```kotlin
    // 大纲生成状态（当前会话）
    val outlineState: StateFlow<OutlineState?> =
        chatService.outlineStates.map { it[_conversationId] }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
```

`stopGeneration` 方法（约 L248-252）之后添加：

```kotlin
    // 触发大纲初稿生成（或重新生成）
    fun generateOutline(rawInput: String) {
        chatService.generateOutline(_conversationId, rawInput)
    }

    // 停止大纲生成（保留已生成草稿）
    fun stopOutlineGeneration() {
        chatService.stopOutlineGeneration(_conversationId)
    }
```

- [ ] **Step 3: 补 import**

`ChatVM.kt` 顶部补（`map`/`stateIn`/`StateFlow`/`SharingStarted`/`UIMessagePart` 均已有）：

```kotlin
import me.rerere.rikkahub.service.OutlineState
import me.rerere.rikkahub.utils.attachOutlineOriginalInput
```

- [ ] **Step 4: 编译验证**

```powershell
.\gradlew :app:compileDebugKotlin
```

预期：BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```powershell
git add app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt
git commit -m "feat(outline): ChatVM 接入大纲生成与消息元数据"
```

---

## Task 5: 输入框大纲开关按钮

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: 添加 strings 资源**

`strings.xml` 追加：

```xml
  <string name="chat_input_outline">Outline</string>
  <string name="chat_input_outline_enabled">Outline flow enabled</string>
  <string name="chat_input_outline_disabled">Outline flow disabled</string>
```

- [ ] **Step 2: 按钮行添加大纲开关**

`ChatInput.kt` 按钮行中，`ReasoningButton` 代码块（约 L442-452）之后（仍在 `Row(weight(1f), horizontalScroll)` 内）追加：

```kotlin
                            // 大纲开关
                            OutlineToggle(
                                enabled = assistant.outlineConfig.enabled,
                                onToggle = { enabled ->
                                    onUpdateAssistant(
                                        assistant.copy(
                                            outlineConfig = assistant.outlineConfig.copy(
                                                enabled = enabled
                                            )
                                        )
                                    )
                                    val enabledMsg = stringResource(R.string.chat_input_outline_enabled)
                                    val disabledMsg = stringResource(R.string.chat_input_outline_disabled)
                                    toaster.show(
                                        message = if (enabled) enabledMsg else disabledMsg,
                                        duration = 1.seconds,
                                        type = if (enabled) ToastType.Success else ToastType.Normal
                                    )
                                },
                            )
```

- [ ] **Step 3: 定义 OutlineToggle 组件**

`ChatInput.kt` 文件末尾（最后一个函数之后）追加：

```kotlin
/**
 * 大纲功能切换按钮（仿 ReasoningButton 的 ToggleSurface 形态）
 */
@Composable
private fun OutlineToggle(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onToggle: (Boolean) -> Unit,
) {
    ToggleSurface(
        checked = enabled,
        onClick = { onToggle(!enabled) },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = HugeIcons.Menu03,
                    contentDescription = stringResource(R.string.chat_input_outline),
                )
            }
        }
    }
}
```

- [ ] **Step 4: 补 import**

`ChatInput.kt` 顶部补：

```kotlin
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
```

（`kotlin.time.Duration` 相关的 `1.seconds`：确认 import `kotlin.time.Duration.Companion.seconds` 已存在——搜索按钮 toast 已用过 `duration = 1.seconds`，如无则补。）

- [ ] **Step 5: 编译验证**

```powershell
.\gradlew :app:compileDebugKotlin
```

预期：BUILD SUCCESSFUL。

- [ ] **Step 6: 提交**

```powershell
git add app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt app/src/main/res/values/strings.xml
git commit -m "feat(outline): 输入框新增大纲功能开关"
```

---

## Task 6: OutlineSheet 与发送拦截

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/OutlineSheet.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: 添加 strings 资源**

`strings.xml` 追加：

```xml
  <string name="outline_sheet_title">Outline</string>
  <string name="outline_sheet_original_input">Original input</string>
  <string name="outline_sheet_draft">Outline draft</string>
  <string name="outline_sheet_generating">Generating outline...</string>
  <string name="outline_sheet_regenerate">Regenerate</string>
  <string name="outline_sheet_confirm">Confirm</string>
  <string name="outline_sheet_retry">Retry</string>
  <string name="outline_sheet_error_prefix">Generate failed:</string>
```

（`R.string.stop`、`R.string.cancel` 已存在，直接复用。）

- [ ] **Step 2: 创建 OutlineSheet 组件**

创建 `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/OutlineSheet.kt`：

```kotlin
package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.OutlineState

/**
 * 大纲编辑底部弹层
 *
 * 上区为原始输入（可编辑后重新生成），下区为大纲草稿：
 * 生成中流式只读展示；完成后以草稿为初值转入可编辑。
 * 确认后草稿原文作为用户消息发送（原始输入写入消息元数据）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlineSheet(
    state: OutlineState?,
    onDismiss: () -> Unit,
    onRegenerate: (rawInput: String) -> Unit,
    onStop: () -> Unit,
    onConfirm: (draft: String, rawInput: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    // 原始输入编辑态：以生成时记录的原文初始化
    val rawInputState = remember(state?.rawInput) {
        TextFieldState(initialText = state?.rawInput ?: "")
    }
    // 草稿编辑态：仅在生成开始/结束时重建，完成后草稿稳定可自由编辑
    val draftState = remember(state?.isGenerating) {
        TextFieldState(initialText = state?.draft ?: "")
    }
    val isGenerating = state?.isGenerating ?: false

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.outline_sheet_title),
                style = MaterialTheme.typography.titleMedium,
            )

            // 原始输入（可编辑，供重新生成使用）
            Text(
                text = stringResource(R.string.outline_sheet_original_input),
                style = MaterialTheme.typography.labelLarge,
            )
            OutlinedTextField(
                state = rawInputState,
                modifier = Modifier.fillMaxWidth(),
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 5),
            )

            // 大纲草稿标题行（生成中提供停止，完成后提供重新生成）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.outline_sheet_draft),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (isGenerating) {
                    TextButton(onClick = onStop) {
                        Text(stringResource(R.string.stop))
                    }
                } else {
                    TextButton(onClick = { onRegenerate(rawInputState.text.toString()) }) {
                        Text(stringResource(R.string.outline_sheet_regenerate))
                    }
                }
            }

            // 草稿区：生成中流式只读；完成后可编辑
            if (isGenerating) {
                Text(
                    text = state.draft.ifBlank { stringResource(R.string.outline_sheet_generating) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                )
            } else {
                OutlinedTextField(
                    state = draftState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(),
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 4, maxHeightInLines = 20),
                )
                state?.error?.let { error ->
                    Text(
                        text = "${stringResource(R.string.outline_sheet_error_prefix)} ${error.message ?: error.localizedMessage ?: error.javaClass.simpleName}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { onRegenerate(rawInputState.text.toString()) }) {
                        Text(stringResource(R.string.outline_sheet_retry))
                    }
                }
            }

            // 操作行：取消 / 确认生成正文
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    enabled = !isGenerating && draftState.text.isNotBlank(),
                    onClick = {
                        onConfirm(
                            draft = draftState.text.toString(),
                            rawInput = rawInputState.text.toString(),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.outline_sheet_confirm))
                }
            }
        }
    }
}
```

- [ ] **Step 3: ChatPage 增加拦截与弹层挂载**

`ChatPage.kt`：

1. 在 `ChatPageContent` 中现有组件状态附近（如 `previewMode`/`hazeState` 定义区域，`Scaffold` 之前）添加：

```kotlin
    // 大纲编辑弹层开关
    var showOutlineSheet by remember { mutableStateOf(false) }
```

2. `ChatInput` 的 `onSendClick`（约 L295-312）整体替换为（保持原缩进 `20.sp` 内层级）：

```kotlin
                    onSendClick = {
                        if (currentChatModel == null) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                            inputState.clearInput()
                        } else if (setting.getCurrentAssistant().outlineConfig.enabled) {
                            // 大纲功能开启：拦截发送，先弹大纲界面生成初稿；输入框内容保留，确认后统一清空
                            showOutlineSheet = true
                            vm.generateOutline(rawInput = inputState.textContent.text.toString())
                        } else {
                            vm.handleMessageSend(inputState.getContents())
                            scope.launch {
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                            inputState.clearInput()
                        }
                    },
```

3. 在 `Scaffold` 的 `bottomBar = { ChatInput(...) }` 参数闭合之后、`containerColor` 参数之前（即 `bottomBar` lambda 结束处 `},` 之后）不挂 Sheet —— Sheet 挂在 `ChatPage` 顶层组合中（`Scaffold` 调用之后）：

```kotlin
    // 大纲编辑弹层
    if (showOutlineSheet) {
        OutlineSheet(
            state = outlineSheetState,
            onDismiss = {
                // 取消：停止生成、丢弃草稿；输入框原文保留
                showOutlineSheet = false
                vm.stopOutlineGeneration()
            },
            onRegenerate = { rawInput ->
                vm.generateOutline(rawInput)
            },
            onStop = {
                vm.stopOutlineGeneration()
            },
            onConfirm = { draft, rawInput ->
                showOutlineSheet = false
                // 大纲原文作为用户消息进入上下文；原始输入写入消息元数据
                vm.handleMessageSend(
                    content = listOf(UIMessagePart.Text(draft)),
                    outlineOriginalInput = rawInput,
                )
                inputState.clearInput()
                scope.launch {
                    chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                }
            },
        )
    }
```

其中 `outlineSheetState` 在 `ChatPage` 内 `showOutlineSheet` 定义处一并声明：

```kotlin
    // 当前会话的大纲生成状态
    val outlineSheetState by vm.outlineState.collectAsStateWithLifecycle()
```

4. 补 import（已存在者跳过）：

```kotlin
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
```

（`OutlineSheet` 与 `ChatPage` 同包无需 import。）

- [ ] **Step 4: 编译验证**

```powershell
.\gradlew :app:compileDebugKotlin
```

预期：BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```powershell
git add app/src/main/java/me/rerere/rikkahub/ui/pages/chat/OutlineSheet.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt app/src/main/res/values/strings.xml
git commit -m "feat(outline): 大纲编辑弹层与发送拦截流程"
```

---

## Task 7: 气泡"查看原始输入"入口

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/components/message/OriginalInputDialog.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageActions.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: 添加 strings 资源**

`strings.xml` 追加：

```xml
  <string name="chat_message_view_original_input">View original input</string>
  <string name="copied_to_clipboard">Copied to clipboard</string>
```

- [ ] **Step 2: 创建 OriginalInputDialog**

创建 `app/src/main/java/me/rerere/rikkahub/ui/components/message/OriginalInputDialog.kt`：

```kotlin
package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalToaster

private const val COPIED_HINT = "copied"

/**
 * 查看大纲消息原始输入的对话框（支持复制）
 */
@Composable
fun OriginalInputDialog(
    text: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val toaster = LocalToaster.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_message_view_original_input)) },
        text = {
            Text(
                text = text,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 复制原始输入到剪贴板
                    clipboard.setText(AnnotatedString(text))
                    toaster.show(COPIED_HINT)
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
```

- [ ] **Step 3: ChatMessageActionButtons 增加入口**

`ChatMessageActions.kt` 中，`ChatMessageActionButtons` 函数内 `FlowRow` 的 `val actionIconColor = ...`（约 L93）之后、第一个 Copy `Icon`（约 L95）之前插入：

```kotlin
        // 大纲消息：提供查看原始输入入口（原始输入存于文本部件元数据）
        var showOriginalInputDialog by remember { mutableStateOf(false) }
        val outlineOriginalInput = message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .firstNotNullOfOrNull { it.readOutlineOriginalInput() }
        if (message.role == MessageRole.USER && outlineOriginalInput != null) {
            Icon(
                imageVector = HugeIcons.Clipboard,
                contentDescription = stringResource(R.string.chat_message_view_original_input),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { showOriginalInputDialog = true }
                    .padding(8.dp)
                    .size(16.dp),
                tint = actionIconColor
            )
        }
        if (showOriginalInputDialog && outlineOriginalInput != null) {
            OriginalInputDialog(
                text = outlineOriginalInput,
                onDismiss = { showOriginalInputDialog = false },
            )
        }
```

并补 import（已存在者跳过）：

```kotlin
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.rikkahub.utils.readOutlineOriginalInput
```

（`mutableStateOf`/`getValue`/`setValue`、`CircleShape`、`clip`、`clickable` 该文件大概率已有，编译时按提示补全。）

- [ ] **Step 4: 编译验证**

```powershell
.\gradlew :app:compileDebugKotlin
```

预期：BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```powershell
git add app/src/main/java/me/rerere/rikkahub/ui/components/message/OriginalInputDialog.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageActions.kt app/src/main/res/values/strings.xml
git commit -m "feat(outline): 消息气泡支持查看大纲原始输入"
```

---

## Task 8: 助手大纲设置页

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantOutlinePage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt`（Screen 定义约 L570-592；entry 注册约 L342-368；顶部 import 区）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantDetailPage.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: 添加 strings 资源**

`strings.xml` 追加：

```xml
  <string name="assistant_outline">Outline</string>
  <string name="assistant_detail_outline_desc">Outline-first generation flow for this assistant</string>
  <string name="assistant_outline_enable">Enable outline flow</string>
  <string name="assistant_outline_enable_desc">When enabled, sending a message first generates an outline to confirm before the final response</string>
  <string name="assistant_outline_prompt">Outline prompt</string>
  <string name="assistant_outline_prompt_desc">Template for outline generation. Placeholders: {input} for raw input, {locale} for system language</string>
  <string name="assistant_outline_model">Outline model</string>
  <string name="assistant_outline_model_desc">Leave empty to follow the current chat model</string>
```

- [ ] **Step 2: 注册路由**

`RouteActivity.kt`：

1. `Screen` sealed interface 内（`AssistantInjections` 定义约 L592 之后）追加：

```kotlin
    @Serializable
    data class AssistantOutline(val id: String) : Screen
```

2. `entryProvider` 中 `entry<Screen.AssistantInjections>`（约 L366-368）之后追加：

```kotlin
                            entry<Screen.AssistantOutline> { key ->
                                AssistantOutlinePage(key.id)
                            }
```

3. 顶部 import 区补 `AssistantOutlinePage`（与 `AssistantBasicPage` 等 import 同处）：

```kotlin
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantOutlinePage
```

- [ ] **Step 3: 详情页加入口卡片**

`AssistantDetailPage.kt` 的 `CardGroup` 中，`Screen.AssistantInjections` 项（约 L110-116）之后追加：

```kotlin
                    item(
                        onClick = { navController.navigate(Screen.AssistantOutline(id)) },
                        leadingContent = { Icon(HugeIcons.Menu03, null) },
                        supportingContent = { Text(stringResource(R.string.assistant_detail_outline_desc)) },
                        headlineContent = { Text(stringResource(R.string.assistant_outline)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                    )
```

并补 import：

```kotlin
import me.rerere.hugeicons.stroke.Menu03
```

- [ ] **Step 4: 创建大纲设置页**

创建 `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantOutlinePage.kt`：

```kotlin
package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.OutlinedTextField
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 助手大纲设置页：启用开关、大纲生成提示词、大纲生成模型
 */
@Composable
fun AssistantOutlinePage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val outlineConfig = assistant.outlineConfig

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_outline)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup {
                    // 启用大纲流程
                    FormItem(
                        label = { Text(stringResource(R.string.assistant_outline_enable)) },
                        description = { Text(stringResource(R.string.assistant_outline_enable_desc)) },
                        tail = {
                            Switch(
                                checked = outlineConfig.enabled,
                                onCheckedChange = { enabled ->
                                    vm.update(
                                        assistant.copy(outlineConfig = outlineConfig.copy(enabled = enabled))
                                    )
                                }
                            )
                        }
                    )
                }
            }
            item {
                CardGroup {
                    // 大纲生成提示词模板
                    FormItem(
                        label = { Text(stringResource(R.string.assistant_outline_prompt)) },
                        description = { Text(stringResource(R.string.assistant_outline_prompt_desc)) },
                        content = {
                            OutlinedTextField(
                                value = outlineConfig.prompt,
                                onValueChange = { prompt ->
                                    vm.update(
                                        assistant.copy(outlineConfig = outlineConfig.copy(prompt = prompt))
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 5,
                            )
                        }
                    )
                }
            }
            item {
                CardGroup {
                    // 大纲生成模型（不选则跟随当前聊天模型）
                    FormItem(
                        label = { Text(stringResource(R.string.assistant_outline_model)) },
                        description = { Text(stringResource(R.string.assistant_outline_model_desc)) },
                        content = {
                            ModelSelector(
                                modelId = outlineConfig.modelId,
                                providers = settings.providers,
                                type = ModelType.CHAT,
                                allowClear = true,
                                onSelect = { model ->
                                    // 选择结果在 providers 中不存在时视为"清除"，回退跟随聊天模型
                                    val exists =
                                        settings.providers.findModelById(model.id) != null
                                    vm.update(
                                        assistant.copy(
                                            outlineConfig = outlineConfig.copy(
                                                modelId = if (exists) model.id else null
                                            )
                                        )
                                    )
                                },
                            )
                        }
                    )
                }
            }
        }
    }
}
```

注意：步骤中的 `OutlinedTextField` 使用 material3 的 compose 单行参数版本（value/onValueChange），import 应为 `androidx.compose.material3.OutlinedTextField`（若上面 import 行写成了 `me.rerere.rikkahub.ui.components.ui.OutlinedTextField` 而该组件不存在，则删掉该 import 并使用 material3 版本）。

- [ ] **Step 5: 编译验证**

```powershell
.\gradlew :app:compileDebugKotlin
```

预期：BUILD SUCCESSFUL。若 `OutlinedTextField` 的 import 冲突，按注意行修正后重编。

- [ ] **Step 6: 提交**

```powershell
git add app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantOutlinePage.kt app/src/main/java/me/rerere/rikkahub/RouteActivity.kt app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantDetailPage.kt app/src/main/res/values/strings.xml
git commit -m "feat(outline): 助手级大纲设置页与入口"
```

---

## Task 9: 全量验证

**Files:** 无新改动（仅验证与清单核对）

- [ ] **Step 1: 全量编译与单测**

```powershell
.\gradlew :app:compileDebugKotlin
.\gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.model.OutlineConfigTest" --tests "me.rerere.rikkahub.utils.OutlineUtilsTest"
```

预期：BUILD SUCCESSFUL，7 个测试全部 PASS。

- [ ] **Step 2: 手动验证清单**（需真机/模拟器，逐项核对设计文档第 8 节）

1. 助手设置 → 大纲 → 开启开关；
2. 聊天输入文字点发送 → 弹出大纲 Sheet，流式出现草稿；
3. 编辑原始输入后点"重新生成" → 草稿按新输入重建；
4. 停止 → 草稿保留可编辑、可确认；
5. 确认 → 消息列表出现用户气泡（内容=大纲），正文流式生成，联网搜索等照常生效；
6. 气泡操作行出现"查看原始输入"入口 → Dialog 显示原文可复制；普通消息不出现该入口；
7. 下一轮再发送 → 大纲请求的消息上下文中上一轮用户消息为大纲原文（无原始输入字样）；
8. 长按发送、消息编辑状态、大纲关闭时 → 行为与现状完全一致（不弹 Sheet）；
9. fork 对话 → 查看原始输入仍可用（元数据随消息复制）；
10. 助手大纲设置页：开关、提示词编辑、模型选择/清除→跟随聊天模型，均生效。

- [ ] **Step 3: 若手动验证发现缺陷，修复后单独提交**

```powershell
git add -A
git commit -m "fix(outline): 手动验证问题修复"
```

---

## Self-Review 记录

1. **Spec 覆盖**：设计文档 §4.1→Task1；§4.3→Task3；§4.4 开关→Task5、拦截+Sheet+元数据→Task4/6、消息呈现入口→Task7；§4.5→Task8；§5 边界→Task3/6 内联处理；§8 测试→Task9。无缺口。
2. **占位符扫描**：无 TBD/TODO；所有代码步骤含完整代码；import 清单列出，冲突时以编译提示为准（已注明处理方式）。
3. **类型一致性**：`OutlineState`（Task3 定义/Task4、6 使用）一致；`handleMessageSend(content, answer, outlineOriginalInput)`（Task4 定义/Task6 调用）一致；`attachOutlineOriginalInput`/`readOutlineOriginalInput`/`OUTLINE_META_KEY`（Task2 定义/Task4、7 使用）一致；`OutlineConfig.DEFAULT_OUTLINE_PROMPT`（Task1 定义/Task1 测试使用）一致。