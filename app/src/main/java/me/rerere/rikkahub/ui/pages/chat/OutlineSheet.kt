package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.service.OutlineState
import me.rerere.rikkahub.ui.components.message.ChatMessageReasoningStep
import me.rerere.rikkahub.ui.components.message.PolishInputDialog
import me.rerere.rikkahub.ui.components.ui.ChainOfThought

/**
 * 大纲编辑底部弹层
 *
 * 上区为原始输入（可编辑后重新生成），下区为大纲草稿：
 * 生成/润色中流式只读展示（伴随思考卡片）；完成后以草稿为初值转入可编辑。
 * 中部内容区可垂直滚动，底部操作行固定——原始输入过长/键盘弹出导致
 * 内容超出可视高度时，草稿区与操作行不会被裁切遮挡，可滚动到达。
 * 确认后草稿原文作为用户消息发送（原始输入写入消息元数据）。
 *
 * 大纲润色：草稿标题行「润色」弹出意见对话框（可留空走通用润色指令），
 * 确认后复用本弹窗流式管道以润色结果覆盖草稿；润色前的旧稿压入撤销栈，
 * 标题行「撤销/重做」可在版本间往返切换。润色失败提示错误并支持重试
 * （复跑同意见），重新生成则整体重置草稿并清空版本栈。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlineSheet(
    state: OutlineState?,
    assistant: Assistant?, // 当前助手（思维链内容按助手可视化正则渲染）
    // 退出弹窗（取消/下滑）时携带原始输入框当前文本，供上层同步回聊天输入框，避免编辑丢失
    onDismiss: (latestRawInput: String) -> Unit,
    onRegenerate: (rawInput: String) -> Unit,
    onPolish: (opinion: String) -> Unit, // 按意见润色当前草稿（空白走通用润色指令）
    onUndo: () -> Unit,                  // 撤销草稿到上一版本
    onRedo: () -> Unit,                  // 重做最近一次撤销的版本
    onStop: () -> Unit,
    onConfirm: (draft: String, rawInput: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val sheetScope = rememberCoroutineScope()

    // 隐藏后再执行动作（带收起动画）
    fun hideThen(action: () -> Unit) {
        sheetScope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    // 原始输入编辑态：以生成时记录的原文初始化
    val rawInputState = remember(state?.rawInput) {
        TextFieldState(initialText = state?.rawInput ?: "")
    }
    // 草稿编辑态：生成开始/结束（isGenerating 翻转）或撤销/重做切换版本（editEpoch 变化）时重建
    val draftState = remember(state?.isGenerating, state?.editEpoch) {
        TextFieldState(initialText = state?.draft ?: "")
    }
    // 润色意见对话框显隐：点「润色」弹出，确认后以意见发起润色
    var showPolishDialog by remember { mutableStateOf(false) }
    val isGenerating = state?.isGenerating ?: false
    // 生成中的草稿/思维链快照：state 可能整个为 null，无法 smart cast，先取局部值
    val generatingDraft = state?.draft ?: ""
    val generatingReasoning = state?.reasoning ?: ""
    // 中部内容区滚动状态（原始输入/思维链/草稿超出可视高度时可滚动，流式自动跟底）
    val contentScrollState = rememberScrollState()

    // 思考卡片计时起点：本轮生成开始时刷新，保证计时从本轮算起
    var reasoningStart by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(isGenerating) {
        if (isGenerating) reasoningStart = Clock.System.now()
    }
    // 思维链部件：生成中 finishedAt=null 以进入流式计时预览态（与聊天行为一致），
    // 结束/停止后定格完成时刻，卡片自动折叠为"深度思考 X 秒"可回看
    val reasoningPart = remember(generatingReasoning, isGenerating) {
        UIMessagePart.Reasoning(
            reasoning = generatingReasoning,
            createdAt = reasoningStart,
            finishedAt = if (isGenerating) null else Clock.System.now(),
        )
    }

    ModalBottomSheet(
        // 下滑关闭也算一条退出路径，同样携带原始输入框当前文本回调，防止编辑内容丢失
        onDismissRequest = { onDismiss(rawInputState.text.toString()) },
        sheetState = sheetState,
        // 关闭弹窗默认 insets，交由内容自行消费 ime/导航栏 insets，避免键盘遮挡输入框与按钮
        // （material3 1.5+ 该参数为 composable lambda 形态，须以 { } 传入而非裸值）
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 先消费键盘 insets，再消费导航栏 insets；随后才按 0.85 占高——
                // 该比例的基准是扣除键盘/导航栏后的可用高度，键盘弹出时弹层整体自动收缩
                .imePadding()
                .navigationBarsPadding()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.outline_sheet_title),
                style = MaterialTheme.typography.titleMedium,
            )

            // 中部内容区：可垂直滚动，超出可视高度（原始输入多/键盘弹出）时可滚动到达
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(contentScrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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

                // 大纲草稿标题行（停止/撤销/重做/润色/重新生成；按钮压缩内边距避免行溢出）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.outline_sheet_draft),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isGenerating) {
                            TextButton(
                                onClick = onStop,
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Text(stringResource(R.string.stop))
                            }
                        } else {
                            // 撤销：有旧版可回退时显示；重做：有被撤版本时显示
                            if (!state?.draftHistory.isNullOrEmpty()) {
                                TextButton(
                                    onClick = onUndo,
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) {
                                    Text(stringResource(R.string.outline_sheet_undo))
                                }
                            }
                            if (!state?.redoDrafts.isNullOrEmpty()) {
                                TextButton(
                                    onClick = onRedo,
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) {
                                    Text(stringResource(R.string.outline_sheet_redo))
                                }
                            }
                            TextButton(
                                onClick = { showPolishDialog = true },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Text(stringResource(R.string.outline_sheet_polish))
                            }
                            TextButton(
                                onClick = { onRegenerate(rawInputState.text.toString()) },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Text(stringResource(R.string.outline_sheet_regenerate))
                            }
                        }
                    }
                }

                // 思维链：模型有推理输出时以聊天同款思考卡片流式展示
                if (generatingReasoning.isNotBlank()) {
                    ChainOfThought(
                        modifier = Modifier.fillMaxWidth(),
                        steps = listOf(reasoningPart),
                    ) { reasoning ->
                        ChatMessageReasoningStep(
                            reasoning = reasoning,
                            model = null,
                            assistant = assistant,
                        )
                    }
                }

                // 草稿区：生成中流式只读；完成后可编辑
                if (isGenerating) {
                    Text(
                        text = generatingDraft.ifBlank { stringResource(R.string.outline_sheet_generating) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        state = draftState,
                        modifier = Modifier.fillMaxWidth(),
                        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 4, maxHeightInLines = 20),
                    )
                    state?.error?.let { error ->
                        Text(
                            text = "${stringResource(R.string.outline_sheet_error_prefix)} ${error.message ?: error.localizedMessage ?: error.javaClass.simpleName}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // 重试：润色遗留态复跑同意见的润色，否则回退为用原始输入重新生成
                        // （error 非空已隐含 state 非空，此处无需安全调用）
                        TextButton(onClick = {
                            val retryOpinion = state.lastPolishOpinion
                            if (retryOpinion != null) onPolish(retryOpinion)
                            else onRegenerate(rawInputState.text.toString())
                        }) {
                            Text(stringResource(R.string.outline_sheet_retry))
                        }
                    }
                }
            }

            // 流式增长时（草稿或思维链任一）自动滚动到内容底部
            LaunchedEffect(generatingDraft, generatingReasoning) {
                if (isGenerating) {
                    contentScrollState.animateScrollTo(contentScrollState.maxValue)
                }
            }

            // 操作行：取消 / 确认生成正文（固定底部，不随内容滚动，始终可见可点）
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    // 取消按钮先收起弹窗再回调，带上原始输入框当前文本
                    onClick = { hideThen { onDismiss(rawInputState.text.toString()) } },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    enabled = !isGenerating && draftState.text.isNotBlank(),
                    onClick = {
                        hideThen {
                            // 函数类型调用禁止具名实参，改为按声明顺序传位置参数
                            onConfirm(
                                draftState.text.toString(),
                                rawInputState.text.toString(),
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.outline_sheet_confirm))
                }
            }

            // 润色意见对话框：意见可留空（走通用大纲润色指令），确认后流式覆盖草稿
            if (showPolishDialog) {
                PolishInputDialog(
                    title = stringResource(R.string.outline_sheet_polish_dialog_title),
                    description = stringResource(R.string.outline_sheet_polish_dialog_desc),
                    onDismiss = { showPolishDialog = false },
                    onConfirm = { opinion ->
                        showPolishDialog = false
                        onPolish(opinion)
                    },
                )
            }
        }
    }
}