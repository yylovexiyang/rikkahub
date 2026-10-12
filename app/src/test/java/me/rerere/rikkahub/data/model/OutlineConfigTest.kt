package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OUTLINE_PROMPT
import me.rerere.rikkahub.utils.applyPlaceholders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

// 大纲配置序列化回归测试：老版本数据（无 outlineConfig 字段）反序列化后应补默认值
class OutlineConfigTest {
    private val json = Json { ignoreUnknownKeys = true }

    // 老数据缺字段时反序列化得到默认值，无需迁移
    @Test
    fun `missing outlineConfig deserializes with default values`() {
        val legacy = """{"systemPrompt":"你是助手","temperature":0.7}"""
        val assistant = json.decodeFromString<Assistant>(legacy)
        assertFalse(assistant.outlineConfig.enabled)
        assertEquals(DEFAULT_OUTLINE_PROMPT, assistant.outlineConfig.prompt)
        assertNull(assistant.outlineConfig.modelId)
    }

    // 修改后的 outlineConfig 可完整序列化/反序列化
    @Test
    fun `outlineConfig roundtrip keeps values`() {
        val origin = Assistant().copy(
            outlineConfig = OutlineConfig(
                enabled = true,
                prompt = "自定义提示词",
                modelId = Uuid.parse("00000000-0000-0000-0000-000000000001"),
            )
        )
        val restored = json.decodeFromString<Assistant>(json.encodeToString(origin))
        assertTrue(restored.outlineConfig.enabled)
        assertEquals("自定义提示词", restored.outlineConfig.prompt)
        // 非空的 modelId 应原样保留
        assertEquals(Uuid.parse("00000000-0000-0000-0000-000000000001"), restored.outlineConfig.modelId)
    }

    // 渲染后不应残留未替换的占位符字面量
    @Test
    fun `default prompt has no unresolved placeholders after render`() {
        val rendered = DEFAULT_OUTLINE_PROMPT
            .applyPlaceholders("input" to "REQ", "locale" to "English")
        assertEquals(false, rendered.contains("{input}"))
        assertEquals(false, rendered.contains("{locale}"))
        assertTrue(rendered.contains("REQ"))
    }
}