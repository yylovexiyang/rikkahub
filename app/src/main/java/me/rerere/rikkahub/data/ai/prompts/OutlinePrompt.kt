package me.rerere.rikkahub.data.ai.prompts

// 大纲生成默认提示词：先产出大纲而非正文，占位符经 applyPlaceholders 渲染
internal const val DEFAULT_OUTLINE_PROMPT = "Before completing the task, based on the conversation context and system locale ({locale}), first create a clear outline for the following request:\n{input}\n\nRequirements: output only the outline, organized as numbered items covering the key steps/sections of the task, keep it concise. Write the outline content in {locale} language."
