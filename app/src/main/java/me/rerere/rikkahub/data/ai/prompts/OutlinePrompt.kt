package me.rerere.rikkahub.data.ai.prompts

// 大纲生成默认提示词：先产出大纲而非正文，占位符经 applyPlaceholders 渲染
internal const val DEFAULT_OUTLINE_PROMPT = "在完成任务前，请基于对话上下文与系统语言（{locale}），针对以下请求先生成一份清晰的大纲：\n{input}\n\n要求：只输出大纲本身，用编号条目组织，覆盖任务的关键步骤/章节，保持简洁；大纲内容使用 {locale} 语言书写。"
