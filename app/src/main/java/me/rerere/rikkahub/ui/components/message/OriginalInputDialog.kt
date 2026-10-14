package me.rerere.rikkahub.ui.components.message

import android.content.ClipData
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalToaster

/**
 * 查看消息保留的原始内容对话框（支持复制）
 *
 * 大纲消息展示原始输入，润色产物展示润色意见；内容存于文本部件元数据，仅供查看不进入上下文
 *
 * @param text 要展示的文本
 * @param title 弹窗标题；默认沿用大纲原始输入文案，润色意见入口传入专用标题
 * @param onDismiss 关闭对话框回调
 */
@Composable
fun OriginalInputDialog(
    text: String,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.chat_message_view_original_input),
) {
    val clipboard = LocalClipboard.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    // 复制提示文案提前解析，避免在点击回调（非 Composable 上下文）中调用 stringResource
    val copiedText = stringResource(R.string.copied_to_clipboard)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                text = text,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 写入剪贴板并提示完成后再关闭，避免协程随组合离开被取消
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText(null, text))
                        )
                        toaster.show(message = copiedText)
                        onDismiss()
                    }
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
