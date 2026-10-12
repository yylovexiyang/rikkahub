package me.rerere.rikkahub.utils

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonPrimitiveOrNull

// 大纲消息元数据键：记录生成大纲所用的用户原始输入；带 outline 前缀避免与其他元数据键冲突，键名随消息持久化即成契约
const val OUTLINE_META_KEY = "outline_original_input"

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
 * 读取文本部件中记录的大纲原始输入；无记录或元数据类型异常时返回 null
 */
fun UIMessagePart.Text.readOutlineOriginalInput(): String? {
    // jsonPrimitiveOrNull 安全转换：非字符串原语（对象/数组等）直接得出 null，无需异常防护
    return metadata?.get(OUTLINE_META_KEY)?.jsonPrimitiveOrNull?.contentOrNull
}
