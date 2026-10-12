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
import androidx.compose.material3.Switch
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
 * 助手大纲设置页：启用开关、大纲生成提示词、大纲生成模型
 */
@Composable
fun AssistantOutlinePage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val outlineConfig = assistant.outlineConfig

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_outline)) },
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
                // 启用大纲流程开关（采用 CardGroup 列表项，与其它助手开关设置一致）
                CardGroup {
                    item(
                        headlineContent = { Text(stringResource(R.string.assistant_outline_enable)) },
                        supportingContent = { Text(stringResource(R.string.assistant_outline_enable_desc)) },
                        trailingContent = {
                            Switch(
                                checked = outlineConfig.enabled,
                                onCheckedChange = { enabled ->
                                    vm.update(
                                        assistant.copy(outlineConfig = outlineConfig.copy(enabled = enabled))
                                    )
                                }
                            )
                        }
                    )
                }
            }
            item {
                // 大纲生成提示词模板（OutlinedTextField 直接绑定，参考 messageTemplate 先例）
                Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                    FormItem(
                        modifier = Modifier.padding(8.dp),
                        label = { Text(stringResource(R.string.assistant_outline_prompt)) },
                        description = { Text(stringResource(R.string.assistant_outline_prompt_desc)) },
                        content = {
                            OutlinedTextField(
                                value = outlineConfig.prompt,
                                onValueChange = { prompt ->
                                    vm.update(
                                        assistant.copy(outlineConfig = outlineConfig.copy(prompt = prompt))
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
                // 大纲生成模型（清除后回退为跟随当前聊天模型）
                Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                    FormItem(
                        modifier = Modifier.padding(8.dp),
                        label = { Text(stringResource(R.string.assistant_outline_model)) },
                        description = { Text(stringResource(R.string.assistant_outline_model_desc)) },
                        content = {
                            ModelSelector(
                                modelId = outlineConfig.modelId,
                                providers = settings.providers,
                                type = ModelType.CHAT,
                                allowClear = true,
                                onSelect = { model ->
                                    // 清除回调传入空 id 的 Model()，不在 providers 中则视为未设置
                                    val exists = settings.providers.findModelById(model.id) != null
                                    vm.update(
                                        assistant.copy(
                                            outlineConfig = outlineConfig.copy(
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
        }
    }
}
