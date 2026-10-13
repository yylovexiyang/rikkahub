package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 助手润色设置页：润色提示词模板、通用润色指令、润色专用模型
 *
 * 对话操作栏的润色按钮对所有 assistant 消息可用；此处仅定制润色行为，
 * 扩展内容（模式注入/世界书等）直接随正文生成管线生效，无独立勾选。
 */
@Composable
fun AssistantPolishPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val polishConfig = assistant.polishConfig

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_polish)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(), // 键盘弹出时避免遮挡编辑区
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                // 润色提示词模板：{input}=润色指令(意见或通用润色指令) / {locale}=系统语言
                Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                    FormItem(
                        modifier = Modifier.padding(8.dp),
                        label = { Text(stringResource(R.string.assistant_polish_prompt)) },
                        description = { Text(stringResource(R.string.assistant_polish_prompt_desc)) },
                        content = {
                            OutlinedTextField(
                                value = polishConfig.prompt,
                                onValueChange = { prompt ->
                                    vm.update(
                                        assistant.copy(polishConfig = polishConfig.copy(prompt = prompt))
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 5,
                                maxLines = 15,
                            )
                        }
                    )
                }
            }
            item {
                // 通用润色指令：润色意见留空时使用
                Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                    FormItem(
                        modifier = Modifier.padding(8.dp),
                        label = { Text(stringResource(R.string.assistant_polish_instruction)) },
                        description = { Text(stringResource(R.string.assistant_polish_instruction_desc)) },
                        content = {
                            OutlinedTextField(
                                value = polishConfig.defaultInstruction,
                                onValueChange = { instruction ->
                                    vm.update(
                                        assistant.copy(polishConfig = polishConfig.copy(defaultInstruction = instruction))
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 4,
                                maxLines = 10,
                            )
                        }
                    )
                }
            }
            item {
                // 润色专用模型：清除后回退为跟随当前聊天模型
                Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                    FormItem(
                        modifier = Modifier.padding(8.dp),
                        label = { Text(stringResource(R.string.assistant_polish_model)) },
                        description = { Text(stringResource(R.string.assistant_polish_model_desc)) },
                        content = {
                            ModelSelector(
                                modelId = polishConfig.modelId,
                                providers = settings.providers,
                                type = ModelType.CHAT,
                                allowClear = true,
                                onSelect = { model ->
                                    // 清除回调传入空 id 的 Model()，不在 providers 中则视为未设置
                                    val exists = settings.providers.findModelById(model.id) != null
                                    vm.update(
                                        assistant.copy(
                                            polishConfig = polishConfig.copy(
                                                modelId = if (exists) model.id else null
                                            )
                                        )
                                    )
                                },
                            )
                        }
                    )
                }
            }
            item {
                // 行为说明卡片（纯展示）：润色与生成的上下文/扩展口径一致
                CardGroup {
                    item(
                        headlineContent = { Text(stringResource(R.string.assistant_polish_behavior_title)) },
                        supportingContent = { Text(stringResource(R.string.assistant_polish_behavior_desc)) },
                    )
                }
            }
        }
    }
}