package me.rerere.rikkahub.utils

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonPrimitiveOrNull

// 润色产物消息元数据键：记录润色所用的用户意见；仅用于气泡 UI 展示，provider 转换层只读
// text 字段，不会进入发送上下文（与大纲原始输入 OUTLINE_META_KEY 同机制），键名随消息持久化即成契约
const val POLISH_META_KEY = "polish_opinion"

/**
 * 将润色用户意见写入第一个文本部件的元数据
 *
 * @param opinion 润色用户意见；null/空白时原样返回（空白意见走通用润色指令，无用户输入可保留）
 * @return 携带元数据的新部件列表；无文本部件时原样返回
 */
fun List<UIMessagePart>.attachPolishOpinion(opinion: String?): List<UIMessagePart> {
    if (opinion.isNullOrBlank()) return this
    val index = indexOfFirst { it is UIMessagePart.Text }
    if (index < 0) return this
    val textPart = this[index] as UIMessagePart.Text
    // 保留部件已有的元数据键（跳过本键），再写回意见；润色流式写回每个 chunk 都会调用，
    // 已有同名键时重复 put 会让 buildJsonObject 抛异常，故先行排除保证幂等
    val metadata = buildJsonObject {
        textPart.metadata?.forEach { (key, value) -> if (key != POLISH_META_KEY) put(key, value) }
        put(POLISH_META_KEY, opinion)
    }
    return mapIndexed { i, part -> if (i == index) textPart.copy(metadata = metadata) else part }
}

/**
 * 读取文本部件中记录的润色用户意见；无记录或元数据类型异常时返回 null
 */
fun UIMessagePart.Text.readPolishOpinion(): String? {
    // jsonPrimitiveOrNull 安全转换：非字符串原语（对象/数组等）直接得出 null，无需异常防护
    return metadata?.get(POLISH_META_KEY)?.jsonPrimitiveOrNull?.contentOrNull
}