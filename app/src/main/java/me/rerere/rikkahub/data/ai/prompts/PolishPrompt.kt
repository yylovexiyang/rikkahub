package me.rerere.rikkahub.data.ai.prompts

// 润色提示词默认模板：基于上下文对助手正文做润色改写，占位符经 applyPlaceholders 渲染
// {input} = 润色指令（用户意见；意见为空时渲染通用润色指令），{locale} = 系统语言
internal const val DEFAULT_POLISH_PROMPT = "基于上方的对话上下文与系统语言（{locale}），在不改变故事走向的前提下，对助手所写的正文（本指令前最后一条助手消息）进行润色改写。\n\n润色指令：\n{input}\n\n要求：只输出润色后的完整正文，不要输出任何解释、标注或元信息；保持情节、事实与人物与对话上下文一致；润色后的正文使用 {locale} 语言书写。"

// 通用润色指令：用户润色意见为空时渲染进 {input} 占位符（可在助手润色配置中自定义）
internal const val DEFAULT_POLISH_INSTRUCTION = "无特殊要求。请对正文进行全面润色：优化用词、表达、句式衔接与节奏，去除冗余，增强代入感，同时保持原意、情节与信息不变。"