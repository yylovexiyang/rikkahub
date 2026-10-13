package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R

/**
 * 润色意见输入对话框
 *
 * 对已生成正文发起润色的入口：输入润色意见/要求后确认；意见可留空，
 * 留空时按助手配置的通用润色指令执行。产物以新分支写回消息节点（与重新生成一致）。
 * 大纲润色也复用本对话框，通过 title/description 参数定制文案（覆盖草稿+可撤销语义）。
 *
 * @param onDismiss 关闭对话框（取消润色）
 * @param onConfirm 确认润色，参数为用户意见（可能为空白字符串）
 * @param title 对话框标题；缺省为正文润色文案
 * @param description 意见输入区说明；缺省为正文润色文案
 */
@Composable
fun PolishInputDialog(
    onDismiss: () -> Unit,
    onConfirm: (opinion: String) -> Unit,
    title: String = stringResource(R.string.polish_dialog_title),
    description: String = stringResource(R.string.polish_dialog_desc),
) {
    // 意见输入状态：确认时随回调传出，可为空
    val opinionState = rememberTextFieldState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 说明：意见可留空，留空按通用润色执行
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    state = opinionState,
                    modifier = Modifier.fillMaxWidth(),
                    // 多行输入，最大 8 行超出内部滚动，避免键盘/内容过长撑爆对话框
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 8),
                )
            }
        },
        confirmButton = {
            // 空意见也可确认（通用润色）
            TextButton(onClick = { onConfirm(opinionState.text.toString()) }) {
                Text(stringResource(R.string.polish_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}