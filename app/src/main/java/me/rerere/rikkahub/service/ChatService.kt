package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.ai.ui.limitContext
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.filterEnabledLorebooks
import me.rerere.rikkahub.data.ai.transformers.filterEnabledModeInjections
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.isTriggered
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.utils.sendNotification
import me.rerere.rikkahub.utils.cancelNotification
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

// 大纲草稿撤销栈上限：润色前的旧稿与撤销暂存的版本各保留最近 5 版，防止无限膨胀
private const val OUTLINE_DRAFT_HISTORY_LIMIT = 5

// 大纲生成状态（按会话隔离，仅内存态；确认大纲后即消费，不持久化）
data class OutlineState(
    val rawInput: String = "",          // 生成大纲所用的用户原始输入
    val draft: String = "",             // 流式累积的大纲草稿
    val reasoning: String = "",         // 流式累积的思维链（模型推理过程，弹窗内展示）
    val isGenerating: Boolean = false,  // 是否正在流式生成
    val error: Throwable? = null,       // 生成失败时的异常
    val draftHistory: List<String> = emptyList(),  // 旧版草稿撤销栈（润色开始时压入上一版），撤销时弹出
    val redoDrafts: List<String> = emptyList(),    // 撤销后暂存的被撤版本，重做时弹回
    // 最近一次润色意见（供失败重试复跑同意见）；null = 当前错误态非润色遗留，重试回退重新生成
    val lastPolishOpinion: String? = null,
    // 草稿编辑态重建计数：撤销/重做弹出不同版本时 +1，弹窗以它为键重建草稿输入框
    val editEpoch: Int = 0,
)

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
) {
    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

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

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    // 前台状态管理
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> _isForeground.value = true
            Lifecycle.Event.ON_STOP -> _isForeground.value = false
            else -> {}
        }
    }

    init {
        // 添加生命周期观察者
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    fun cleanup() = runCatching {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) }
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            // 清理该会话的大纲草稿内存态
            _outlineStates.update { it - conversationId }
            outlineJobs.remove(conversationId)?.cancel()
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        getOrCreateSession(conversationId) // 确保 session 存在
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            updateConversation(conversationId, conversation)
            settingsStore.updateAssistant(conversation.assistantId)
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    // ---- 发送消息 ----

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job = appScope.launch {
            try {
                runCatching { previousJob?.join() }
                finishInterruptedPendingTools(conversationId)

                val currentConversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)

                // 添加消息到列表
                val newConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(
                        role = MessageRole.USER,
                        parts = processedContent,
                    ).toMessageNode(),
                )
                saveConversation(conversationId, newConversation)

                // 开始补全
                if (answer) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        session.setJob(job)
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val conversation = session.state.value

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    // ---- 润色消息 ----

    /**
     * 润色已生成的正文（assistant 消息）
     *
     * 管线与正文生成完全一致（generationHandler.generateText）：相同的 system prompt
     * 组装（助手/会话覆盖 + 记忆 + 工具提示词）、inputTransformers（模式注入/世界书/
     * 模板等）、tools 与推理等级、上下文截断 limitContext。输入消息在正文之外
     * 额外追加"原正文 + 润色指令"两条，让模型基于原正文与意见改写。
     *
     * 写回表现与重新生成一致：流式内容作为目标节点的新增分支（selectIndex 切至
     * 新分支渲染），原正文保留可切回；意见为空时使用 polishConfig.defaultInstruction
     * 做通用润色，模型/提示词均取自 assistant.polishConfig。
     *
     * @param message 要润色的正文消息（其所在节点的新增分支承载润色结果）
     * @param opinion 用户润色意见；空白时回退通用润色指令
     */
    fun polishMessage(
        conversationId: Uuid,
        message: UIMessage,
        opinion: String,
    ) {
        // 仅正文（assistant 消息）可润色
        if (message.role != MessageRole.ASSISTANT) return

        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val conversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(conversation.assistantId)
                    ?: settings.getCurrentAssistant()

                // 定位目标节点
                val targetNode = conversation.getMessageNodeByMessage(message) ?: return@launch
                val nodeIndex = conversation.messageNodes.indexOf(targetNode)

                // 模型：润色专用模型优先，否则跟随当前聊天模型（与正文生成同口径）
                // 先校验模型再写入占位分支，避免失败时遗留空分支
                val model =
                    settings.findModelById(assistant.polishConfig.modelId ?: assistant.chatModelId ?: settings.chatModelId)
                        ?: throw IllegalStateException(
                            context.getString(R.string.error_polish_model_not_found)
                        )

                // 新分支占位消息（空文本载体），流式期间按此 id 回写增量内容
                val polishedMessage = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("")),
                )
                // 目标节点追加分支并切换选中，气泡内渲染流式（与重新生成一致）
                updateConversation(
                    conversationId,
                    conversation.copy(
                        messageNodes = conversation.messageNodes.mapIndexed { index, node ->
                            if (index == nodeIndex) {
                                node.copy(
                                    messages = node.messages + polishedMessage,
                                    selectIndex = node.messages.size,
                                )
                            } else {
                                node
                            }
                        }
                    )
                )

                // 润色输入：目标节点之前的上下文（正文生成同源）+ 原正文 + 润色指令用户消息
                val polishInput = buildPolishInstruction(assistant, opinion)
                val inputMessages = conversation.currentMessages.take(nodeIndex) +
                    message +
                    listOf(UIMessage.user(prompt = polishInput))

                val modelSenderName = if (assistant.useAssistantAvatar) {
                    assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
                } else {
                    model.displayName
                }

                generationHandler.generateText(
                    settings = settings,
                    model = model,
                    messages = inputMessages,
                    // 与正文生成相同的输入管线：模式注入/世界书等扩展随助手与会话勾选自动生效
                    inputTransformers = buildList {
                        addAll(inputTransformers)
                        add(templateTransformer)
                    },
                    outputTransformers = outputTransformers,
                    assistant = assistant,
                    conversationSystemPrompt = conversation.customSystemPrompt,
                    conversationModeInjectionIds = conversation.modeInjectionIds,
                    conversationLorebookIds = conversation.lorebookIds,
                    memories = getAssistantMemories(assistant),
                    tools = buildGenerationTools(settings, assistant),
                    processingStatus = session.processingStatus,
                ).onCompletion {
                    // 与正文生成路径相同的收尾：取消通知 + finishReasoning 兜底更新
                    cancelLiveUpdateNotification(conversationId)

                    val updatedConversation = getConversationFlow(conversationId).value.copy(
                        messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                            node.copy(messages = node.messages.map { it.finishReasoning() })
                        },
                        updateAt = Instant.now()
                    )
                    updateConversation(conversationId, updatedConversation)

                    // 应用不在前台时发送完成通知（与正文生成一致）
                    if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration) {
                        sendGenerationDoneNotification(conversationId, modelSenderName)
                    }
                }.collect { chunk ->
                    when (chunk) {
                        is GenerationChunk.Messages -> {
                            // chunk.messages.last() 即流式中的润色正文；按占位 id 写回目标节点分支
                            val polished = chunk.messages.lastOrNull() ?: return@collect
                            if (polished.role != MessageRole.ASSISTANT) return@collect
                            writeBackPolishedMessage(
                                conversationId = conversationId,
                                nodeIndex = nodeIndex,
                                branchMessageId = polishedMessage.id,
                                newMessage = polished,
                            )
                        }
                    }
                }

                // 生成成功：持久化
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 失败时保留占位分支中已生成的部分内容（与重新生成被停止的行为一致）
                addError(e, conversationId, title = context.getString(R.string.error_title_polish_message))
            }
        }

        session.setJob(job)
    }

    /**
     * 构建润色指令：意见非空原文使用；空白时回退助手配置的通用润色指令，
     * 并以 polishConfig.prompt 为模板渲染 {input}/{locale} 占位符
     */
    private fun buildPolishInstruction(
        assistant: Assistant,
        opinion: String,
    ): String {
        val instruction = opinion.ifBlank { assistant.polishConfig.defaultInstruction }
        return assistant.polishConfig.prompt.applyPlaceholders(
            "input" to instruction,
            "locale" to Locale.getDefault().displayName,
        )
    }

    /**
     * 将润色结果写回目标节点的新分支（按分支消息 id 替换，selectIndex 指向该分支）。
     * 每次取最新会话态写回，避免与标题生成等并发更新互相覆盖。
     */
    private fun writeBackPolishedMessage(
        conversationId: Uuid,
        nodeIndex: Int,
        branchMessageId: Uuid,
        newMessage: UIMessage,
    ) {
        val latest = getConversationFlow(conversationId).value
        val updatedConversation = latest.copy(
            messageNodes = latest.messageNodes.mapIndexed { index, node ->
                if (index == nodeIndex) {
                    val messages = node.messages.map { message ->
                        if (message.id == branchMessageId) {
                            // 保留占位消息的分支身份（id），内容/元数据取流式结果
                            newMessage.copy(id = branchMessageId)
                        } else {
                            message
                        }
                    }
                    node.copy(
                        messages = messages,
                        selectIndex = messages.indexOfFirst { it.id == branchMessageId }
                            .takeIf { it >= 0 } ?: node.selectIndex,
                    )
                } else {
                    node
                }
            }
        )
        updateConversation(conversationId, updatedConversation)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val conversation = session.state.value
                val newApprovalState = when {
                    answer != null -> ToolApprovalState.Answered(answer)
                    approved -> ToolApprovalState.Approved
                    else -> ToolApprovalState.Denied(reason)
                }

                // Update the tool approval state
                val updatedNodes = conversation.messageNodes.map { node ->
                    node.copy(
                        messages = node.messages.map { msg ->
                            msg.copy(
                                parts = msg.parts.map { part ->
                                    when {
                                        part is UIMessagePart.Tool && part.toolCallId == toolCallId -> {
                                            part.copy(approvalState = newApprovalState)
                                        }

                                        else -> part
                                    }
                                }
                            )
                        }
                    )
                }
                val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                saveConversation(conversationId, updatedConversation)

                // Check if there are still pending tools
                val hasPendingTools = updatedNodes.any { node ->
                    node.currentMessage.parts.any { part ->
                        part is UIMessagePart.Tool && part.isPending
                    }
                }

                // Only continue generation when all pending tools are handled
                if (!hasPendingTools) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null
    ) {
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }

        runCatching {

            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (settings.enableWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value

            // start generating
            val session = getOrCreateSession(conversationId)
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = conversation.currentMessages.let {
                    if (messageRange != null) {
                        it.subList(messageRange.start, messageRange.endInclusive + 1)
                    } else {
                        it
                    }
                },
                assistant = assistant,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                memories = getAssistantMemories(assistant),
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                },
                outputTransformers = outputTransformers,
                tools = buildGenerationTools(settings, assistant),
            ).onCompletion {
                // 取消 Live Update 通知
                cancelLiveUpdateNotification(conversationId)

                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation)

                // Show notification if app is not in foreground
                if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration) {
                    sendGenerationDoneNotification(conversationId, senderName)
                }
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val updatedConversation = getConversationFlow(conversationId).value
                            .updateCurrentMessages(chunk.messages)
                        updateConversation(conversationId, updatedConversation)

                        // 如果应用不在前台，发送 Live Update 通知
                        if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration && settings.displaySetting.enableLiveUpdateNotification) {
                            sendLiveUpdateNotification(conversationId, chunk.messages, senderName)
                        }
                    }
                }
            }
        }.onFailure {
            // 取消 Live Update 通知
            cancelLiveUpdateNotification(conversationId)

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
            saveConversation(conversationId, finalConversation)

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
        }
    }

    // ---- 检查无效消息 ----

    // 按助手配置获取记忆（全局共享或助手隔离），正文生成与润色共用
    private suspend fun getAssistantMemories(assistant: Assistant): List<AssistantMemory> {
        return if (assistant.useGlobalMemory) {
            memoryRepository.getGlobalMemories()
        } else {
            memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
        }
    }

    // 构建正文生成完整工具集（搜索/本地工具/Skills/MCP），正文生成与润色共用，保持管线一致
    private suspend fun buildGenerationTools(
        settings: Settings,
        assistant: Assistant,
    ): List<Tool> {
        return buildList {
            if (settings.enableWebSearch) {
                addAll(createSearchTools(settings))
            }
            addAll(localTools.getTools(assistant.localTools))
            if (assistant.enabledSkills.isNotEmpty()) {
                addAll(
                    createSkillTools(
                        enabledSkills = assistant.enabledSkills,
                        allSkills = skillManager.listSkills(),
                        skillManager = skillManager,
                    )
                )
            }
            mcpManager.getAllAvailableTools().forEach { (serverId, tool) ->
                add(
                    Tool(
                        name = "mcp__" + tool.name,
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = tool.needsApproval,
                        execute = {
                            mcpManager.callTool(serverId, tool.name, it.jsonObject)
                        },
                    )
                )
            }
        }
    }

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user")
        )
    }

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText() })
                    ),
                ),
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.OFF,
                ),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                saveConversation(
                    conversationId,
                    it.copy(title = result.choices[0].message?.toText()?.trim() ?: "")
                )
            }
        }.onFailure {
            it.printStackTrace()
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckTitleModelSettings,
            )
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.suggestionModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            sessions[conversationId]?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText() }),
                    )
                ),
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.OFF,
                ),
            )
            val suggestions =
                result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()

            val latestConversation = conversationRepo.getConversationById(conversationId)
                ?: sessions[conversationId]?.state?.value
                ?: conversation
            saveConversation(
                conversationId,
                latestConversation.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            )
        }.onFailure {
            it.printStackTrace()
        }
    }

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        val maxMessagesPerChunk = 256
        val allMessages = conversation.currentMessages

        // Split messages into those to compress and those to keep
        val messagesToCompress: List<UIMessage>
        val messagesToKeep: List<UIMessage>

        if (keepRecentMessages > 0 && allMessages.size > keepRecentMessages) {
            messagesToCompress = allMessages.dropLast(keepRecentMessages)
            messagesToKeep = allMessages.takeLast(keepRecentMessages)
        } else if (keepRecentMessages > 0) {
            // Not enough messages to compress while keeping recent ones
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        } else {
            messagesToCompress = allMessages
            messagesToKeep = emptyList()
        }

        fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= maxMessagesPerChunk) return listOf(messages)
            val mid = messages.size / 2
            val left = splitMessages(messages.subList(0, mid))
            val right = splitMessages(messages.subList(mid, messages.size))
            return left + right
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText() }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = TextGenerationParams(
                    model = model,
                ),
            )

            return result.choices[0].message?.toText()?.trim()
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        val compressedSummaries = coroutineScope {
            splitMessages(messagesToCompress)
                .map { chunk -> async { compressMessages(chunk) } }
                .awaitAll()
        }

        // Create new conversation with compressed history as multiple user messages + kept messages
        val newMessageNodes = buildList {
            compressedSummaries.forEach { summary ->
                add(UIMessage.user(summary).toMessageNode())
            }
            addAll(messagesToKeep.map { it.toMessageNode() })
        }
        val newConversation = conversation.copy(
            messageNodes = newMessageNodes,
            chatSuggestions = emptyList(),
        )

        saveConversation(conversationId, newConversation)
    }

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
            // 本协程自身的 Job(与外层 launch 返回值是同一对象,经运行时验证),
            // 供 catch/finally 阶段做"是否仍是当前任务"的身份判断
            val self = coroutineContext[Job] ?: error("No Job in outline coroutine context")
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

                // 大纲请求的 base system prompt 与正文一致（允许会话级覆盖），不带记忆/工具
                val baseSystemPrompt = if (
                    assistant.allowConversationSystemPrompt && !conversation.customSystemPrompt.isNullOrBlank()
                ) {
                    conversation.customSystemPrompt
                } else {
                    assistant.systemPrompt
                }

                // 构建大纲专属的扩展注入附加段（模式注入/世界书/Skills）。
                // 勾选来源是 outlineConfig 的独立字段，与助手聊天的同名勾选互不影响，
                // 因此绝不触碰聊天管线的 PromptInjectionTransformer 路径。
                val injectionPrompt = buildOutlineInjectionPrompt(
                    assistant = assistant,
                    settings = settings,
                    rawInput = rawInput,
                )
                // 注入附加段统一并入 system prompt 末尾；无命中内容时保持原样（行为与现状完全一致）
                val systemPrompt = buildString {
                    append(baseSystemPrompt)
                    if (injectionPrompt.isNotBlank()) {
                        if (isNotEmpty()) append("\n\n") // 与基础提示词空一行分隔
                        append(injectionPrompt)
                    }
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
                        // 思维链：跟随助手聊天的推理等级（与 GenerationHandler 聊天路径一致），
                        // 助手关闭推理时这里仍为 OFF，行为与旧版逐字节一致
                        reasoningLevel = assistant.reasoningLevel,
                    ),
                ).collect { chunk ->
                    val deltaParts = chunk.choices.firstOrNull()?.delta?.parts ?: emptyList()
                    // 文本增量 → 草稿
                    val text = deltaParts
                        .filterIsInstance<UIMessagePart.Text>()
                        .joinToString("") { it.text }
                    if (text.isNotEmpty()) {
                        setOutlineState(conversationId) { it.copy(draft = it.draft + text) }
                    }
                    // 推理增量 → 思维链（弹窗内以思考卡片流式展示）
                    val reasoning = deltaParts
                        .filterIsInstance<UIMessagePart.Reasoning>()
                        .joinToString("") { it.reasoning }
                    if (reasoning.isNotEmpty()) {
                        setOutlineState(conversationId) { it.copy(reasoning = it.reasoning + reasoning) }
                    }
                }

                setOutlineState(conversationId) { it.copy(isGenerating = false) }
            } catch (e: CancellationException) {
                // 用户停止/重触发：仅当自己仍是该会话的当前任务时才清理状态，
                // 防止旧任务在重触发后把新状态 isGenerating 覆写回 false
                if (outlineJobs[conversationId] == self) {
                    setOutlineState(conversationId) { it.copy(isGenerating = false) }
                }
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                // 仅当自己仍是当前任务才记录错误（防止旧任务异常收尾污染重触发后的新状态）
                if (outlineJobs[conversationId] == self) {
                    setOutlineState(conversationId) { it.copy(isGenerating = false, error = e) }
                }
            } finally {
                // 仅当自己是当前任务才移除，避免删除重触发写入的新任务
                outlineJobs.remove(conversationId, self)
            }
        }
        outlineJobs[conversationId] = job
        return job
    }

    /**
     * 按润色意见改写当前大纲草稿（大纲润色）
     *
     * 复用大纲轻量管线与弹窗流式展示：system prompt（含大纲扩展注入段）与大纲生成
     * 同口径；对话历史末尾追加"当前草稿（assistant 消息）+ 润色指令（user 消息）"，
     * 与正文润色的输入结构一致。开始前把旧草稿压入撤销栈并清空重做栈，意见空白时
     * 回退大纲润色通用指令；模型跟随大纲生成模型（modelId ?: 聊天模型）。
     *
     * 与大纲生成共用 outlineJobs / OutlineState（生成与润色互斥，重复触发取消旧任务）。
     *
     * @param opinion 用户润色意见；空白时使用 outlineConfig.polishDefaultInstruction
     */
    fun polishOutline(conversationId: Uuid, opinion: String) {
        // 与大纲生成/上一轮润色互斥：重复触发先取消旧任务
        outlineJobs[conversationId]?.cancel()

        // 草稿为空时无润色标的（弹窗入口已禁用，此处为服务层防御）
        val stateBefore = _outlineStates.value[conversationId] ?: OutlineState()
        if (stateBefore.draft.isBlank()) return
        // 润色标的：开始时刻的草稿快照，作为上下文末尾的 assistant 消息
        val currentDraft = stateBefore.draft

        setOutlineState(conversationId) {
            // 旧草稿压入撤销栈（上限 5，超出挤掉最老），清空重做侧；
            // 记录本轮意见供失败重试；草稿/思维链重置由流式增量重建
            it.copy(
                isGenerating = true,
                draft = "",
                reasoning = "",
                error = null,
                lastPolishOpinion = opinion,
                draftHistory = (it.draftHistory + currentDraft).takeLast(OUTLINE_DRAFT_HISTORY_LIMIT),
                redoDrafts = emptyList(),
            )
        }

        val job = appScope.launch {
            // 与 generateOutline 相同的"是否仍是当前任务"身份判断供收尾阶段使用
            val self = coroutineContext[Job] ?: error("No Job in outline coroutine context")
            try {
                val settings = settingsStore.settingsFlow.first()
                val conversation = getConversationFlow(conversationId).value
                val assistant = settings.getAssistantById(conversation.assistantId)
                    ?: settings.getCurrentAssistant()

                // 模型与大纲生成同口径：大纲专用模型优先，否则跟随当前聊天模型
                val model =
                    settings.findModelById(assistant.outlineConfig.modelId ?: assistant.chatModelId ?: settings.chatModelId)
                        ?: throw IllegalStateException(
                            context.getString(R.string.error_outline_polish_model_not_found)
                        )
                val provider = model.findProvider(settings.providers)
                    ?: throw IllegalStateException("Provider not found")

                // system prompt 与大纲生成一致：助手基础提示词（允许会话级覆盖）+ 扩展注入段
                // 注入段触发扫描沿用润色标的对应的原始输入，保证润色与生成看到相同的扩展内容
                val baseSystemPrompt = if (
                    assistant.allowConversationSystemPrompt && !conversation.customSystemPrompt.isNullOrBlank()
                ) {
                    conversation.customSystemPrompt
                } else {
                    assistant.systemPrompt
                }
                val injectionPrompt = buildOutlineInjectionPrompt(
                    assistant = assistant,
                    settings = settings,
                    rawInput = stateBefore.rawInput,
                )
                val systemPrompt = buildString {
                    append(baseSystemPrompt)
                    if (injectionPrompt.isNotBlank()) {
                        if (isNotEmpty()) append("\n\n")
                        append(injectionPrompt)
                    }
                }

                // 润色指令：意见空白回退通用大纲润色指令，再按模板渲染 {input}/{locale} 占位符
                val instruction = opinion.ifBlank { assistant.outlineConfig.polishDefaultInstruction }
                val polishPrompt = assistant.outlineConfig.polishPrompt.applyPlaceholders(
                    "input" to instruction,
                    "locale" to Locale.getDefault().displayName,
                )
                val messages = buildList {
                    if (systemPrompt.isNotBlank()) {
                        add(UIMessage.system(prompt = systemPrompt))
                    }
                    // 尊重助手配置的上下文窗口限制（与大纲生成一致）
                    addAll(
                        conversation.currentMessages.limitContext(assistant.contextMessageSize)
                    )
                    // 当前草稿以助手消息身份收尾，润色指令随其后（模板中"本指令前最后一条助手消息"）
                    add(UIMessage.assistant(prompt = currentDraft))
                    add(UIMessage.user(prompt = polishPrompt))
                }

                val providerImpl = providerManager.getProviderByType(provider)
                providerImpl.streamText(
                    providerSetting = provider,
                    messages = messages,
                    params = TextGenerationParams(
                        model = model,
                        // 推理等级与大纲生成一致，跟随助手聊天配置
                        reasoningLevel = assistant.reasoningLevel,
                    ),
                ).collect { chunk ->
                    val deltaParts = chunk.choices.firstOrNull()?.delta?.parts ?: emptyList()
                    // 润色结果增量 → 草稿（弹窗同一草稿区流式替换）
                    val text = deltaParts
                        .filterIsInstance<UIMessagePart.Text>()
                        .joinToString("") { it.text }
                    if (text.isNotEmpty()) {
                        setOutlineState(conversationId) { it.copy(draft = it.draft + text) }
                    }
                    // 推理增量 → 思维链（弹窗内以思考卡片流式展示）
                    val reasoning = deltaParts
                        .filterIsInstance<UIMessagePart.Reasoning>()
                        .joinToString("") { it.reasoning }
                    if (reasoning.isNotEmpty()) {
                        setOutlineState(conversationId) { it.copy(reasoning = it.reasoning + reasoning) }
                    }
                }

                setOutlineState(conversationId) { it.copy(isGenerating = false) }
            } catch (e: CancellationException) {
                // 停止/重触发：仅当自己仍是该会话的当前任务时才清理状态（防止覆盖新任务状态）；
                // 未完成的半截稿保留在草稿区，旧版在撤销栈内可撤回
                if (outlineJobs[conversationId] == self) {
                    setOutlineState(conversationId) { it.copy(isGenerating = false) }
                }
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                // 仅当自己仍是当前任务才记录错误（防止旧任务异常收尾污染重触发后的新状态）
                if (outlineJobs[conversationId] == self) {
                    setOutlineState(conversationId) { it.copy(isGenerating = false, error = e) }
                }
            } finally {
                // 仅当自己是当前任务才移除，避免删除重触发写入的新任务
                outlineJobs.remove(conversationId, self)
            }
        }
        outlineJobs[conversationId] = job
    }

    /**
     * 撤销大纲草稿到上一版本（润色前的旧稿）：当前被撤下的版本转入重做侧，
     * 可重做回退。仅生成结束（非流式中）时有意义，流式中与服务层静默忽略。
     */
    fun undoOutlineDraft(conversationId: Uuid) {
        setOutlineState(conversationId) {
            // 流式中版本态不稳定，拒绝操作；无旧版可撤时保持原状
            if (it.isGenerating || it.draftHistory.isEmpty()) return@setOutlineState it
            it.copy(
                redoDrafts = it.redoDrafts + it.draft,
                draft = it.draftHistory.last(),
                draftHistory = it.draftHistory.dropLast(1),
                // 版本切换时 bump 编辑态重建计数，弹窗据此重建草稿输入框内容
                editEpoch = it.editEpoch + 1,
            )
        }
    }

    /**
     * 重做最近一次撤销的大纲草稿版本：弹回的版本重新压回撤销栈，
     * 撤销/重做可往返切换。仅生成结束后可用，流式中静默忽略。
     */
    fun redoOutlineDraft(conversationId: Uuid) {
        setOutlineState(conversationId) {
            if (it.isGenerating || it.redoDrafts.isEmpty()) return@setOutlineState it
            it.copy(
                draftHistory = (it.draftHistory + it.draft).takeLast(OUTLINE_DRAFT_HISTORY_LIMIT),
                draft = it.redoDrafts.last(),
                redoDrafts = it.redoDrafts.dropLast(1),
                editEpoch = it.editEpoch + 1,
            )
        }
    }

    /**
     * 构建大纲专属的扩展注入附加段（模式注入 + 世界书 + Skills）
     *
     * 大纲是独立轻量管线（不走 inputTransformers 管线），PromptInjectionTransformer
     * 的"按 InjectionPosition 往消息流插桩"逻辑不适用；且大纲输出是结构草稿而非
     * 聊天回复，无需区分注入方位，因此把三类扩展内容统一并入大纲 system prompt
     * 末尾附加段——这是对聊天路径"按位置注入"的简化，原因：
     * 1. 大纲生成的消息结构固定（system + 历史上下文 + 一条大纲请求），位置语义意义有限；
     * 2. 统一附加段实现最简单、对模型指引清晰、额外 token 开销最小。
     *
     * 复用说明：勾选/启用过滤复用 PromptInjectionTransformer 中抽取的共用纯函数；
     * 世界书条目触发扫描复用模型层纯函数 RegexInjection.isTriggered，仅扫描上下文
     * 由"聊天路径的最近 N 条消息"换成 rawInput（大纲阶段只有本次原始输入可用）。
     *
     * @param assistant 当前助手，其 outlineConfig 持有大纲专用勾选（与助手聊天勾选相互独立）
     * @param settings 全局设置（提供 modeInjections / lorebooks 全量列表）
     * @param rawInput 用户原始输入，作为世界书关键词触发扫描的上下文
     * @return 附加段文本；无任何命中内容时返回空字符串，调用方据此不追加任何文本
     */
    private fun buildOutlineInjectionPrompt(
        assistant: Assistant,
        settings: Settings,
        rawInput: String,
    ): String {
        val outlineConfig = assistant.outlineConfig
        // 三类勾选全部为空时直接短路：零开销且保证与未开启勾选时的行为完全一致
        if (
            outlineConfig.modeInjectionIds.isEmpty()
            && outlineConfig.lorebookIds.isEmpty()
            && outlineConfig.enabledSkills.isEmpty()
        ) {
            return ""
        }
        val sections = mutableListOf<String>()

        // 1. 模式注入：按大纲专用勾选 + enabled 过滤，按优先级降序（与聊天路径排序一致）
        val modeInjections = filterEnabledModeInjections(
            modeInjections = settings.modeInjections,
            selectedIds = outlineConfig.modeInjectionIds,
        )
        if (modeInjections.isNotEmpty()) {
            sections += buildString {
                appendLine("<assistant_injections>")
                modeInjections.sortedByDescending { it.priority }.forEach { injection ->
                    // 每条注入标出名称，便于模型理解注入来源（聊天路径按位置插入，此处统一并入 system）
                    appendLine("<injection name=\"${injection.name}\">")
                    appendLine(injection.content)
                    appendLine("</injection>")
                }
                append("</assistant_injections>")
            }.trimEnd()
        }

        // 2. 世界书：按大纲专用勾选 + enabled 过滤全书后，用 rawInput 做关键词触发扫描，
        //    命中条目按优先级降序合并（与聊天路径 getTriggeredInjections 的排序一致）
        val triggeredEntries = filterEnabledLorebooks(
            lorebooks = settings.lorebooks,
            selectedIds = outlineConfig.lorebookIds,
        ).flatMap { lorebook ->
            lorebook.entries
                .filter { entry -> entry.isTriggered(rawInput) }
                .sortedByDescending { it.priority }
        }
        if (triggeredEntries.isNotEmpty()) {
            sections += buildString {
                appendLine("<worldbook_entries>")
                triggeredEntries.forEach { entry ->
                    appendLine("<entry name=\"${entry.name}\">")
                    appendLine(entry.content)
                    appendLine("</entry>")
                }
                append("</worldbook_entries>")
            }.trimEnd()
        }

        // 3. Skills：按大纲专用勾选的 skill 名称过滤，直接把 SKILL.md 正文注入 system prompt。
        //    与聊天路径的差异：聊天通过 createSkillTools 的 use_skill 工具让模型按需加载；
        //    大纲管线不注册任何工具，故直接内联正文（readSkillBody 已剔除 frontmatter），
        //    正文拉取失败/为空时静默跳过该 skill，不中断大纲生成。
        if (outlineConfig.enabledSkills.isNotEmpty()) {
            skillManager.listSkills()
                .filter { it.name in outlineConfig.enabledSkills }
                .forEach { skill ->
                    val body = skillManager.readSkillBody(skill.name) ?: return@forEach
                    if (body.isBlank()) return@forEach
                    sections += buildString {
                        appendLine("<skill name=\"${skill.name}\">")
                        append(body)
                        append("\n</skill>")
                    }
                }
        }

        return sections.joinToString("\n\n")
    }

    /**
     * 停止大纲生成（保留已生成草稿，转入可编辑态）
     */
    fun stopOutlineGeneration(conversationId: Uuid) {
        outlineJobs[conversationId]?.cancel()
    }

    // ---- 通知 ----

    private fun sendGenerationDoneNotification(conversationId: Uuid, senderName: String) {
        // 先取消 Live Update 通知
        cancelLiveUpdateNotification(conversationId)

        val conversation = getConversationFlow(conversationId).value
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 1
        ) {
            title = senderName
            content = conversation.currentMessages.lastOrNull()?.toText()?.take(50)?.trim() ?: ""
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun getLiveUpdateNotificationId(conversationId: Uuid): Int {
        return conversationId.hashCode() + 10000
    }

    private fun sendLiveUpdateNotification(
        conversationId: Uuid,
        messages: List<UIMessage>,
        senderName: String
    ) {
        val lastMessage = messages.lastOrNull() ?: return
        val parts = lastMessage.parts

        // 确定当前状态
        val (chipText, statusText, contentText) = determineNotificationContent(parts)

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = getLiveUpdateNotificationId(conversationId)
        ) {
            title = senderName
            content = contentText
            subText = statusText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            useBigTextStyle = true
            contentIntent = getPendingIntent(context, conversationId)
            requestPromotedOngoing = true
            shortCriticalText = chipText
        }
    }

    private fun determineNotificationContent(parts: List<UIMessagePart>): Triple<String, String, String> {
        // 检查最近的 part 来确定状态
        val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()

        return when {
            // 正在执行工具
            lastTool != null && !lastTool.isExecuted -> {
                val toolName = lastTool.toolName.removePrefix("mcp__")
                Triple(
                    context.getString(R.string.notification_live_update_chip_tool),
                    context.getString(R.string.notification_live_update_tool, toolName),
                    lastTool.input.take(100)
                )
            }
            // 正在思考（Reasoning 未结束）
            lastReasoning != null && lastReasoning.finishedAt == null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_thinking),
                    context.getString(R.string.notification_live_update_thinking),
                    lastReasoning.reasoning.takeLast(200)
                )
            }
            // 正在写回复
            lastText != null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_writing),
                    lastText.text.takeLast(200)
                )
            }
            // 默认状态
            else -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_title),
                    ""
                )
            }
        }
    }

    private fun cancelLiveUpdateNotification(conversationId: Uuid) {
        context.cancelNotification(getLiveUpdateNotificationId(conversationId))
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        val updatedConversation = conversation.copy()
        updateConversation(conversationId, updatedConversation)

        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val forkConversation = Conversation(
            id = Uuid.random(),
            assistantId = currentConversation.assistantId,
            messageNodes = copiedNodes,
            customSystemPrompt = currentConversation.customSystemPrompt,
            modeInjectionIds = currentConversation.modeInjectionIds,
            lorebookIds = currentConversation.lorebookIds,
        )

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val job = sessions[conversationId]?.getJob() ?: return
        job.cancel()
        runCatching { job.join() }
        finishInterruptedPendingTools(conversationId)
    }
}
