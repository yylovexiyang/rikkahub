# 大纲功能设计文档

- 日期：2026-10-12
- 状态：已与用户逐节确认
- 分支：`dev-2.2.6.1`

## 1. 背景与目标

RikkaHub 是原生 Android LLM 聊天客户端。本功能为聊天流程增加可选的"先大纲、后正文"生成方式：

- 关闭大纲功能时，聊天行为与现状完全一致，零影响。
- 开启后，用户点发送会被"拦截"：先弹出生成大纲的界面（自动流式生成初稿），用户可编辑、重新生成大纲，确认后再以大纲为用户消息生成正文。
- 大纲内容是用户消息本体（进入上下文的只有大纲），原始用户输入仅用于生成大纲，**不进入上下文**，但保留在消息元数据中可随时查看/复制。
- 大纲功能作为助手级"扩展"独立管理（开关、提示词模板、生成模型）。

## 2. 需求基线（澄清确认记录）

以下决策均已在设计前与用户逐一确认：

| # | 问题 | 结论 |
|---|------|------|
| 1 | 触发方式 | **发送时拦截**：开关开启后点发送 → 弹大纲界面（自动生成初稿）→ 编辑/重新生成 → 确认后生成正文 |
| 2 | 发给 AI 的内容 | **进入上下文的只有大纲**：确认后，大纲原文直接作为用户消息发送（不做包装模板） |
| 3 | 消息呈现 | **气泡 = 大纲**，原始输入藏在消息 metadata 中，点开可查看/复制 |
| 4 | 扩展管理形态 | **助手级单配置**：每个助手一套大纲配置（开关/提示词/模型），不做模板列表 |
| 5 | 大纲生成请求 | **携带对话历史上下文** + 助手 system prompt；不带工具/注入/记忆管线 |
| 6 | 大纲生成模型 | **可选专用模型，默认跟随当前聊天模型** |

## 3. 方案选择记录

评估过的方案：

- **方案 A（采纳）：BottomSheet + 大纲独立直调** — 正文完全复用现有 `sendMessage` 链路（搜索/工具/注入照常生效）；大纲请求仿 `generateTitle`/`compressConversation` 的 provider 直调模式单独实现，流式可取消。
- **方案 B（未采纳）：全屏大纲编辑页** — 数据流同 A，编辑空间更大，但打断对话体验，与"发送→快速确认"轻量交互不符。
- **方案 C（未采纳）：大纲请求走 GenerationHandler 完整管线** — 注入/记忆/工具全生效，但语义不对（大纲请求不是对话轮次），输出变换可能干扰大纲纯文本，复杂度高。

方案 A 是项目已有先例（标题生成、压缩上下文、聊天建议）的自然延伸，改动面与风险最小。

## 4. 详细设计

### 4.1 数据模型

`app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt` 新增：

```kotlin
/**
 * 大纲功能配置（助手级单配置）
 */
@Serializable
data class OutlineConfig(
    val enabled: Boolean = false,                  // 总开关
    val prompt: String = DEFAULT_OUTLINE_PROMPT,   // 大纲生成提示词，含 {input}、{locale} 占位符
    val modelId: Uuid? = null,                     // 大纲生成模型，null = 跟随当前聊天模型
)

// Assistant 增加字段
val outlineConfig: OutlineConfig = OutlineConfig()
```

要点：

- 所有字段带默认值，kotlinx-serialization 反序列化老数据自动补默认值，**无需数据库/DataStore 迁移**。
- 原始输入存储：确认大纲后发送用户消息时，`ChatVM.handleMessageSend` 将原始输入写入消息 Text part 的 `metadata: JsonObject`（键 `originalInput`）。Part 的 `metadata` 字段已存在（`ai/src/main/java/me/rerere/ai/ui/Message.kt` 中 `UIMessagePart` 抽象属性），随消息持久化，fork/导出天然保留；发送给 API 时 provider DTO 只取 text，metadata 不参与请求。

### 4.2 默认大纲提示词模板

内置常量（用户可在助手大纲设置中修改），通用版语义（不限定写作场景）：占位符 `{input}`（用户原始输入）、可选 `{locale}`（系统语言，复用 `applyPlaceholders`），引导模型基于对话上下文给出条目化大纲，禁止直接写正文。默认草案（实现阶段可微调措辞）：

```
请先不要直接完成任务，而是基于对话上下文与语言环境（{locale}），针对以下需求制定一份条目清晰的大纲：
{input}

要求：只输出大纲本身，按编号条目组织，涵盖任务的关键步骤/章节，简明扼要。
```

模板经 `applyPlaceholders` 渲染，`{input}` 替换为用户原始输入，`{locale}` 替换为系统语言名（与标题/摘要生成模板同机制）。

### 4.3 大纲生成链路

`app/src/main/java/me/rerere/rikkahub/service/ChatService.kt` 新增：

```kotlin
// 大纲生成状态（按会话隔离）
data class OutlineState(
    val rawInput: String = "",
    val draft: String = "",          // 流式累积的大纲草稿
    val isGenerating: Boolean = false,
    val error: Throwable? = null,
)

// 新增成员
private val _outlineStates = MutableStateFlow<Map<Uuid, OutlineState>>(emptyMap())
val outlineStates: StateFlow<Map<Uuid, OutlineState>>

// 新增方法
fun generateOutline(conversationId: Uuid, rawInput: String): Job
fun stopOutlineGeneration(conversationId: Uuid)
```

生成过程（仿 `compressConversation` 直调模式，但带历史）：

1. 读取助手配置：`modelId ?: 当前聊天模型` → `providerManager.getProviderByType(provider)`；
2. 组装消息：助手 system prompt（含会话级 `customSystemPrompt`，如有）+ `conversation.currentMessages` + 一条临时 user 消息（`outlineConfig.prompt` 经 `applyPlaceholders` 替换 `{input}`/`{locale}`）；
3. provider 流式调用（`TextGenerationParams` 中 `reasoningLevel = ReasoningLevel.OFF`，与标题生成一致），delta 追加进 `OutlineState.draft`；
4. **不携带**：工具（搜索/MCP/本地工具）、PromptInjection、memory、TemplateTransformer 管线。大纲请求保持纯粹；
5. 异常写入 `OutlineState.error`，已生成部分文本保留。

并发与生命周期：

- 大纲生成 Job 与正文生成 Job 相互独立，互不取消对方；
- 同一会话重复触发 `generateOutline`（如重新生成）先取消旧 Job；
- 会话关闭时取消进行中的大纲 Job（现有 session 清理路径即可覆盖，实现时核对）。

### 4.4 UI 交互流程

#### 开关按钮

`app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt` 按钮行（模型 / 搜索 / 思考同排）新增"大纲"切换按钮：

- 点击 → `onUpdateAssistant(assistant.copy(outlineConfig = outlineConfig.copy(enabled = !enabled)))`，写入助手配置（与思考等级按钮同模式）；
- 按钮高亮态反映开关状态。

#### 发送拦截

`app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt` 的 `onSendClick`：

```
点击发送（非长按）且 非编辑模式（!inputState.isEditing()）且 assistant.outlineConfig.enabled
  → 不清空输入框、不调 handleMessageSend
  → 拉起 OutlineSheet，同时调用 vm.generateOutline(inputState 原始输入)
关闭开关 / 长按发送（answer=false 仅入库）/ 编辑消息中 → 完全走原流程
```

#### OutlineSheet（新文件 `ui/pages/chat/OutlineSheet.kt`）

BottomSheet（与项目 `InjectionQuickConfigSheet` 交互模式一致）：

- 上区：原始输入展示 + 复制按钮；文本可编辑（编辑后"重新生成"以新输入重建草稿）；
- 下区：大纲草稿编辑区（流式追加显示 → 生成完成后可自由编辑、复制）；生成中有"停止"按钮（保留已生成部分转入可编辑态）；
- 操作按钮：**取消**（丢弃草稿，原始输入写回输入框防丢失）｜**确认**（草稿为空或仍在生成时 disabled）；
- 确认动作：以当前草稿文本作为用户消息 Text part 调用 `vm.handleMessageSend(content, outlineOriginalInput = rawInput)` → Sheet 关闭、输入框清空、滚动到底（现有逻辑）；
- 生成失败：Sheet 内显示错误信息 + 重试按钮，已生成部分保留可编辑。

#### 元数据写入

`ChatVM.handleMessageSend` 增加可选参数：

```kotlin
fun handleMessageSend(
    content: List<UIMessagePart>,
    answer: Boolean = true,
    outlineOriginalInput: String? = null,  // 大纲流程携带的用户原始输入
)
```

VM 内组装 parts 时将 `outlineOriginalInput` 写入第一个 Text part 的 `metadata`（键 `originalInput`）。`ChatService.sendMessage` 与 `preprocessUserInputParts` 零改动（`Text.copy(text = ...)` 默认保留 metadata）。

#### 消息呈现

- 用户气泡正常渲染大纲文本（即消息本体）；
- 检测到 `metadata.originalInput` 存在时，气泡显示"查看原始输入"入口 → 新 Dialog（`OriginInputDialog.kt`）显示原文，支持复制。具体挂载点（UserMessage 组件位置）在实现计划中定位；
- 用户消息的既有操作全部按现有逻辑自然工作：重新生成 = 以上下文中的大纲重新生成正文；编辑 = 正常编辑消息（编辑产生的新分支视为普通消息，原始输入元数据不强求带入）；删除/分支切换无差异。

### 4.5 助手设置页（独立扩展管理）

- 入口：`AssistantDetailPage` 卡片列表新增"大纲"项；
- 新文件 `ui/pages/assistant/detail/AssistantOutlinePage.kt`（FormItem 表单，仿 `AssistantBasicPage`）：
  - 启用开关；
  - 大纲生成提示词（多行编辑，附占位符说明）；
  - 大纲生成模型选择器（默认"跟随当前聊天模型"）；
- 导航路由注册（与其他助手子页同模式）。

## 5. 边界与错误处理

| 场景 | 行为 |
|------|------|
| 输入为空 | 不能触发发送（现有逻辑），不会进入大纲流程 |
| 大纲生成失败/网络异常 | Sheet 显示错误 + 重试，历史与输入框内容不受影响 |
| 停止大纲生成 | 保留已生成草稿，转入可编辑 |
| 正文生成进行中 | 发送按钮处于停止态，天然无法并发触发 |
| 进程被杀 / Sheet 关闭 | 草稿即内存态，确认前不持久化（用户已确认可接受），取消时原始输入写回输入框防丢失 |
| 会话级 system prompt | 参与（作为大纲请求的 system），会话级注入绑定不参与（走管线才生效） |
| i18n | 按 CLAUDE.md 约定暂不做多语言，文案用默认资源 |

## 6. 明确不做（YAGNI）

- 多模板列表管理（用户确认助手级单配置）；
- 大纲草稿的跨进程/会话持久化；
- 大纲请求带工具/注入/记忆；
- 正文包装模板（用户确认大纲原文直接发送）；
- 大纲内容独立持久化字段。

## 7. 涉及文件清单

| 类型 | 文件 |
|------|------|
| 修改 | `data/model/Assistant.kt`（OutlineConfig + 默认提示词常量） |
| 修改 | `service/ChatService.kt`（OutlineState + generateOutline + stopOutlineGeneration） |
| 修改 | `ui/pages/chat/ChatVM.kt`（暴露生成/停止/发送带元数据） |
| 修改 | `ui/components/ai/ChatInput.kt`（开关按钮 + 透传） |
| 修改 | `ui/pages/chat/ChatPage.kt`（拦截 + Sheet 挂载） |
| 修改 | `ui/pages/assistant/detail/AssistantDetailPage.kt`（入口卡片）＋ 导航注册 |
| 修改 | 用户消息气泡组件（"查看原始输入"入口，实现计划中定位） |
| 新增 | `ui/pages/chat/OutlineSheet.kt`、`ui/pages/chat/OriginInputDialog.kt`、`ui/pages/assistant/detail/AssistantOutlinePage.kt`、可能的 `data/ai/prompts/OutlinePrompt.kt` |

## 8. 测试策略

- 单元测试：OutlineConfig 序列化回归（老 Settings JSON 反序列化补默认值）；`{input}`/`{locale}` 占位符替换；metadata 写入/读取。
- 手动验证清单：
  1. 关闭开关 → 行为与现状完全一致；
  2. 开启 → 输入需求发送 → Sheet 流式出大纲 → 编辑 → 重新生成 → 确认 → 正文生成正常（联网搜索等照常生效）；
  3. 气泡显示大纲 + "查看原始输入"Dialog 可复制；
  4. 上下文检查：后续轮次请求中用户消息 = 大纲原文（无原始输入、无注入包装）；
  5. 停止/重试/取消流程；
  6. 长按发送不走大纲；编辑模式不走大纲；
  7. fork 对话后原始输入元数据保留。