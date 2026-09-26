package io.github.mangi.eta.ui.screens.characters

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.components.movo.BlockTone
import io.github.mangi.eta.ui.components.movo.CardFooter
import io.github.mangi.eta.ui.components.movo.CardTitle
import io.github.mangi.eta.ui.components.movo.MovoBlockButton
import io.github.mangi.eta.ui.components.movo.MovoCard
import io.github.mangi.eta.ui.components.movo.MovoIconButton
import io.github.mangi.eta.ui.components.movo.MovoListPage
import io.github.mangi.eta.ui.components.movo.MovoPillButton
import io.github.mangi.eta.ui.theme.MovoColors
import io.github.mangi.eta.ui.theme.MovoIcon
import io.github.mangi.eta.ui.theme.MovoIcons
import io.github.mangi.eta.ui.theme.MovoSize
import io.github.mangi.eta.ui.theme.MovoSpacing
import top.yukonga.miuix.kmp.basic.TextField

/**
 * 创建 / 编辑角色（规范 8.7 二级页）：顶栏 ✓ 保存；「角色设定」卡（名称、设定）；「开场白」卡（默认 + 备用，可移除、可添加）；
 * 「高级设置」展开后依次为角色细节、提示词、作者信息、世界书；底部整行「保存角色」。输入框暂用 Miuix TextField，外层为新卡片。
 */
@Composable
internal fun CharacterEditorScreen(
    id: String?,
    store: CharacterLibraryStore,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var worldbookExpanded by rememberSaveable { mutableStateOf(false) }
    var worldbookEntry by rememberSaveable { mutableStateOf<Int?>(null) }
    val card = store.draft
    val canSave = !store.busy && store.draftName.isNotBlank()
    MovoListPage(
        title = if (id == null) "创建角色" else "编辑角色",
        onBack = onBack,
        modifier = Modifier.imePadding(),
        actions = {
            MovoIconButton(
                icon = MovoIcons.Check,
                contentDescription = "保存角色",
                onClick = { store.saveEditor(onSaved) },
                enabled = canSave,
            )
        },
    ) {
        if (card == null) {
            item { CharacterPageMessage(if (store.busy) "正在读取…" else "无法读取角色，请返回重试") }
            return@MovoListPage
        }
        item(key = "profile") {
            CharacterFieldCard(title = "角色设定") {
                CharacterTextField("名称", store.draftName, store::updateName, !store.busy, singleLine = true)
                CharacterTextField("外貌、性格与经历", card.description, { value -> store.updateDraft { it.withEdits(description = value) } }, !store.busy, minLines = 5)
            }
        }
        item(key = "greetings") {
            MovoCard(bottomPadding = MovoSpacing.md) {
                CardTitle("开场白")
                CharacterTextField("默认开场白", card.firstMessage, { value -> store.updateDraft { it.withEdits(firstMessage = value) } }, !store.busy)
                card.alternateGreetings.forEachIndexed { index, value ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = MovoSpacing.lg, end = MovoSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextField(
                            value = value,
                            onValueChange = { updated ->
                                store.updateDraft { it.withEdits(alternateGreetings = it.alternateGreetings.toMutableList().apply { this[index] = updated }) }
                            },
                            label = "开场白 ${index + 2}",
                            enabled = !store.busy,
                            minLines = 2,
                            maxLines = 14,
                            modifier = Modifier.weight(1f).padding(vertical = MovoSpacing.xs),
                        )
                        MovoIconButton(
                            icon = MovoIcons.X,
                            contentDescription = "移除此开场白",
                            onClick = {
                                store.updateDraft { it.withEdits(alternateGreetings = it.alternateGreetings.filterIndexed { i, _ -> i != index }) }
                            },
                            enabled = !store.busy,
                            iconSize = MovoSize.iconMedium,
                            tint = MovoColors.textSecondary,
                        )
                    }
                }
                MovoPillButton(
                    label = "添加备用开场白",
                    icon = MovoIcons.Plus,
                    onClick = { store.updateDraft { it.withEdits(alternateGreetings = it.alternateGreetings + "") } },
                    enabled = !store.busy,
                    modifier = Modifier.padding(start = MovoSpacing.lg, top = MovoSpacing.xs),
                )
            }
        }
        item(key = "advanced") {
            MovoCard {
                CharacterRow(
                    title = "高级设置",
                    subtitle = "性格、背景、示例对话、提示词、作者信息与世界书",
                    showDivider = false,
                    onClick = { advanced = !advanced },
                ) {
                    MovoIcon(
                        if (advanced) MovoIcons.ChevronUp else MovoIcons.ChevronDown,
                        null,
                        size = MovoSize.iconSmall,
                        tint = MovoColors.textTertiary,
                    )
                }
                if (advanced) {
                    CardFooter(
                        listOf(
                            "角色卡中的第三方脚本与扩展界面不会执行。",
                            "相关数据会保留在导出的角色卡中。",
                        ),
                    )
                }
            }
        }
        if (advanced) {
            item(key = "details") {
                CharacterFieldCard(title = stringResource(R.string.movo_character_group_details)) {
                    CharacterTextField("性格与说话风格", card.personality, { value -> store.updateDraft { it.withEdits(personality = value) } }, !store.busy)
                    CharacterTextField("故事背景", card.scenario, { value -> store.updateDraft { it.withEdits(scenario = value) } }, !store.busy)
                    CharacterTextField("示例对话", card.exampleMessages, { value -> store.updateDraft { it.withEdits(exampleMessages = value) } }, !store.busy, minLines = 4)
                }
            }
            item(key = "prompts") {
                CharacterFieldCard(title = "提示词") {
                    CharacterTextField("系统提示词", card.systemPrompt, { value -> store.updateDraft { it.withEdits(systemPrompt = value) } }, !store.busy)
                    CharacterTextField("对话后置指令", card.postHistoryInstructions, { value -> store.updateDraft { it.withEdits(postHistoryInstructions = value) } }, !store.busy)
                }
            }
            item(key = "credits") {
                CharacterFieldCard(title = "作者信息") {
                    CharacterTextField("作者备注", card.creatorNotes, { value -> store.updateDraft { it.withEdits(creatorNotes = value) } }, !store.busy)
                    CharacterTextField("标签（每行一个）", card.tags.joinToString("\n"), { value -> store.updateDraft { it.withEdits(tags = value.lines()) } }, !store.busy)
                    CharacterTextField("作者", card.creator, { value -> store.updateDraft { it.withEdits(creator = value) } }, !store.busy, singleLine = true)
                    CharacterTextField("角色版本", card.version, { value -> store.updateDraft { it.withEdits(version = value) } }, !store.busy, singleLine = true)
                }
            }
            characterWorldbookEditor(
                store = store, expanded = worldbookExpanded, expandedEntry = worldbookEntry,
                onToggleExpanded = { worldbookExpanded = !worldbookExpanded },
                onExpandEntry = { worldbookEntry = it },
            )
        }
        item(key = "save") {
            MovoBlockButton(
                label = if (store.busy) "正在处理…" else "保存角色",
                onClick = { store.saveEditor(onSaved) },
                enabled = canSave,
                tone = BlockTone.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 卡内输入框：左右 16（对齐内容线 36）、上下 4；暂用 Miuix TextField（颜色已映射到 Movo 色板）。 */
@Composable
internal fun CharacterTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    singleLine: Boolean = false,
    minLines: Int = 2,
) {
    TextField(
        value = value, onValueChange = onChange, label = label, enabled = enabled,
        singleLine = singleLine, minLines = if (singleLine) 1 else minLines,
        maxLines = if (singleLine) 1 else 14,
        modifier = Modifier.fillMaxWidth().padding(horizontal = MovoSpacing.lg, vertical = MovoSpacing.xs),
    )
}

/** 卡内小分组标题（与 `Card/Title` 同样式）。 */
@Composable
internal fun CharacterFieldGroupLabel(text: String) {
    CardTitle(text)
}
