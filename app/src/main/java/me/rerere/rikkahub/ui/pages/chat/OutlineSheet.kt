package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
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
    val sheetScope = rememberCoroutineScope()

    // 隐藏后再执行动作（带收起动画）
    fun hideThen(action: () -> Unit) {
        sheetScope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    // 原始输入编辑态：以生成时记录的原文初始化
    val rawInputState = remember(state?.rawInput) {
        TextFieldState(initialText = state?.rawInput ?: "")
    }
    // 草稿编辑态：仅在生成开始/结束时重建，完成后草稿稳定可自由编辑
    val draftState = remember(state?.isGenerating) {
        TextFieldState(initialText = state?.draft ?: "")
    }
    val isGenerating = state?.isGenerating ?: false
    // 生成中的草稿快照：state 可能整个为 null，无法 smart cast，先取局部值
    val generatingDraft = state?.draft ?: ""
    // 大纲草稿滚动状态（流式自动跟底）
    val draftScrollState = rememberScrollState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
                    text = generatingDraft.ifBlank { stringResource(R.string.outline_sheet_generating) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(draftScrollState),
                )
                // 流式草稿增长时自动滚动到底部
                LaunchedEffect(generatingDraft) {
                    draftScrollState.animateScrollTo(draftScrollState.maxValue)
                }
            } else {
                OutlinedTextField(
                    state = draftState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
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
                    onClick = { hideThen { onDismiss() } },
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
        }
    }
}
