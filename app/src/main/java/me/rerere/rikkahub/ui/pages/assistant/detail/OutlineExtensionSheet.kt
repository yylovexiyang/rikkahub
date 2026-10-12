package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.OutlineConfig
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.ai.LorebooksContent
import me.rerere.rikkahub.ui.components.ai.ModeInjectionsContent
import me.rerere.rikkahub.ui.components.ai.QuickMessagesContent
import me.rerere.rikkahub.ui.components.ai.SkillsContent
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster

/**
 * 大纲扩展勾选弹窗（与聊天输入栏的扩展勾选弹窗同款：4 Tab + HorizontalPager）
 *
 * 结构上复用聊天版 ExtensionSelector 的 Tab 布局与 ExtensionContent 的 4 个内容组件，
 * 但勾选结果写入 Assistant.outlineConfig 的独立字段
 * （quickMessageIds / modeInjectionIds / lorebookIds / enabledSkills），
 * 与助手聊天的扩展勾选互不影响。
 *
 * 大纲配置没有"会话级绑定"概念，因此不包含聊天版本的会话级覆盖（override）逻辑。
 *
 * @param assistant           当前助手实例（从中读取 outlineConfig 已勾选集合）
 * @param settings            全局设置（快捷消息 / 模式注入 / 世界书的数据源）
 * @param skills              Skills 列表（来自 AssistantDetailVM.skills 流）
 * @param onUpdateOutlineConfig outlineConfig 更新回调（页面侧转成 vm.update(assistant.copy(...))）
 * @param onDismiss           弹窗关闭回调
 */
@Composable
fun OutlineExtensionSheet(
    assistant: Assistant,
    settings: Settings,
    skills: List<SkillMetadata>,
    onUpdateOutlineConfig: (OutlineConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    // 弹窗期间读取的 outlineConfig 快照（勾选回调基于它做 set 增删）
    val outlineConfig = assistant.outlineConfig
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        // 仅允许 收起/展开 两档，避免半展开状态（与聊天版弹窗一致）
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    // 在 composable 作用域预先解析提示文案，供回调内使用（回调中不能调用 stringResource）
    val tipMessage = stringResource(R.string.outline_extension_tip)

    // 统一的"管理扩展"跳转：先收起弹窗并关闭，再跳助手扩展管理页（带助手 id）
    // 显式标注 () -> Unit：否则 lambda 尾部的 scope.launch(Job) 会让类型推断变成 () -> Job
    val navigateToExtensions: () -> Unit = {
        scope.launch {
            sheetState.hide()
            onDismiss()
            navController.navigate(Screen.AssistantInjections(assistant.id.toString()))
            // 提示：大纲扩展勾选独立保存，与聊天扩展互不影响
            toaster.show(
                message = tipMessage,
                type = ToastType.Info,
            )
        }
    }

    // 4 个 Tab：快捷消息 / 模式注入 / 世界书 / Skills
    val pagerState = rememberPagerState { 4 }
    val tabTitles = listOf(
        R.string.extension_selector_tab_quick_messages,
        R.string.extension_selector_tab_mode_injections,
        R.string.extension_selector_tab_lorebooks,
        R.string.extension_selector_tab_skills,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f) // 与聊天版弹窗一致的最大高度
                .padding(horizontal = 16.dp),
        ) {
            SecondaryScrollableTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = Color.Transparent,
                modifier = Modifier.fillMaxWidth(),
                edgePadding = 4.dp,
            ) {
                tabTitles.forEachIndexed { index, titleRes ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(stringResource(titleRes)) }
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                when (page) {
                    0 -> {
                        // 快捷消息勾选 -> outlineConfig.quickMessageIds
                        if (settings.quickMessages.isNotEmpty()) {
                            QuickMessagesContent(
                                quickMessages = settings.quickMessages,
                                selectedIds = outlineConfig.quickMessageIds,
                                onToggle = { id, checked ->
                                    val newIds = if (checked) {
                                        outlineConfig.quickMessageIds + id
                                    } else {
                                        outlineConfig.quickMessageIds - id
                                    }
                                    onUpdateOutlineConfig(outlineConfig.copy(quickMessageIds = newIds))
                                },
                                onManage = navigateToExtensions,
                            )
                        } else {
                            // 空态：提示无快捷消息并提供"管理扩展"跳转
                            ExtensionEmptyState(
                                message = stringResource(R.string.extension_selector_quick_messages_empty),
                                buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                                onAction = navigateToExtensions,
                            )
                        }
                    }

                    1 -> {
                        // 模式注入勾选 -> outlineConfig.modeInjectionIds
                        if (settings.modeInjections.isNotEmpty()) {
                            ModeInjectionsContent(
                                modeInjections = settings.modeInjections,
                                selectedIds = outlineConfig.modeInjectionIds,
                                onToggle = { id, checked ->
                                    val newIds = if (checked) {
                                        outlineConfig.modeInjectionIds + id
                                    } else {
                                        outlineConfig.modeInjectionIds - id
                                    }
                                    onUpdateOutlineConfig(outlineConfig.copy(modeInjectionIds = newIds))
                                },
                                onManage = navigateToExtensions,
                            )
                        } else {
                            ExtensionEmptyState(
                                message = stringResource(R.string.extension_selector_mode_injections_empty),
                                buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                                onAction = navigateToExtensions,
                            )
                        }
                    }

                    2 -> {
                        // 世界书勾选 -> outlineConfig.lorebookIds
                        if (settings.lorebooks.isNotEmpty()) {
                            LorebooksContent(
                                lorebooks = settings.lorebooks,
                                selectedIds = outlineConfig.lorebookIds,
                                onToggle = { id, checked ->
                                    val newIds = if (checked) {
                                        outlineConfig.lorebookIds + id
                                    } else {
                                        outlineConfig.lorebookIds - id
                                    }
                                    onUpdateOutlineConfig(outlineConfig.copy(lorebookIds = newIds))
                                },
                                onManage = navigateToExtensions,
                            )
                        } else {
                            ExtensionEmptyState(
                                message = stringResource(R.string.extension_selector_lorebooks_empty),
                                buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                                onAction = navigateToExtensions,
                            )
                        }
                    }

                    3 -> {
                        // Skills 勾选（按 skill.name 作为键）-> outlineConfig.enabledSkills
                        if (skills.isNotEmpty()) {
                            SkillsContent(
                                skills = skills,
                                enabledSkills = outlineConfig.enabledSkills,
                                onToggle = { name, checked ->
                                    val newSkills = if (checked) {
                                        outlineConfig.enabledSkills + name
                                    } else {
                                        outlineConfig.enabledSkills - name
                                    }
                                    onUpdateOutlineConfig(outlineConfig.copy(enabledSkills = newSkills))
                                },
                                onManage = navigateToExtensions,
                            )
                        } else {
                            ExtensionEmptyState(
                                message = stringResource(R.string.extension_selector_skills_empty),
                                buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                                onAction = navigateToExtensions,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}