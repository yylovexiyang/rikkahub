package me.rerere.rikkahub.data.ai.prompts

// 大纲生成默认提示词：先产出大纲而非正文，占位符经 applyPlaceholders 渲染
internal const val DEFAULT_OUTLINE_PROMPT = "在完成任务前，请基于对话上下文与系统语言（{locale}），针对以下请求先生成一份清晰的大纲：\n{input}\n\n要求：只输出大纲本身，用编号条目组织，覆盖任务的关键步骤/章节，保持简洁；大纲内容使用 {locale} 语言书写。"

// 大纲润色提示词默认模板：基于上下文改写当前大纲草稿，占位符经 applyPlaceholders 渲染
// {input} = 润色指令（意见；空白时渲染下方通用指令），{locale} = 系统语言
// 润色输入结构同正文润色：对话历史末尾先放当前草稿（assistant 消息），再放本指令（user 消息），
// 因此以"本指令前最后一条助手消息"指代草稿
internal const val DEFAULT_OUTLINE_POLISH_PROMPT = "基于上方的对话上下文与系统语言（{locale}），在保持原始请求意图不变的前提下，对当前大纲（本指令前最后一条助手消息）进行润色改写。\n\n润色指令：\n{input}\n\n要求：只输出润色后的完整大纲，不要输出任何解释、标注或元信息；保持编号条目组织，覆盖任务的关键步骤/章节；润色后的大纲使用 {locale} 语言书写。"

// 通用大纲润色指令：用户润色意见为空时渲染进 {input} 占位符（可在助手大纲配置中自定义）
internal const val DEFAULT_OUTLINE_POLISH_INSTRUCTION = "无特殊要求。请对大纲进行全面润色：优化条目结构与顺序，拆分或合并粒度不当的条目，补齐缺失的关键步骤，使表述更清晰具体，同时保持原始意图与覆盖范围不变。"
