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

    // attach 的 null 输入分支：原样返回，不写元数据
    @Test
    fun `attach passes through null input`() {
        val parts = listOf(UIMessagePart.Text("t"))
        assertEquals(parts, parts.attachOutlineOriginalInput(null))
    }

    // read 防御分支：元数据值为对象时返回 null（非字符串原语）；为数字时返回其字面文本
    @Test
    fun `read handles non string primitive values`() {
        // 元数据值是 JsonObject：不是字符串原语，读取应安全返回 null
        val objectValue = UIMessagePart.Text(
            "t",
            metadata = buildJsonObject {
                put(OUTLINE_META_KEY, buildJsonObject { put("x", "y") })
            },
        )
        assertNull(objectValue.readOutlineOriginalInput())

        // 元数据值是数字 JsonPrimitive：content 属性是其字面文本 "123"
        val numberValue = UIMessagePart.Text(
            "t",
            metadata = buildJsonObject { put(OUTLINE_META_KEY, JsonPrimitive(123)) },
        )
        assertEquals("123", numberValue.readOutlineOriginalInput())
    }

    // 特殊字符 roundtrip：换行、引号、emoji 随元数据持久化后原样读回，且键名带 outline 前缀
    @Test
    fun `attach then read roundtrips special characters`() {
        val original = "带换行\n带引号\"、emoji 🦊"
        val attached = listOf(UIMessagePart.Text("t")).attachOutlineOriginalInput(original)
        assertEquals(
            "outline_original_input",
            (attached.first() as UIMessagePart.Text).metadata?.keys?.first(),
        )
        assertEquals(original, (attached.first() as UIMessagePart.Text).readOutlineOriginalInput())
    }
}
