package io.github.fartown.movo.ui.screens.characters

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.fartown.movo.agent.roleplay.CharacterBookEntryDraft
import io.github.fartown.movo.agent.roleplay.CharacterWorldbook
import io.github.fartown.movo.ui.app.CharacterLibraryStore
import io.github.fartown.movo.ui.components.movo.CardTitle
import io.github.fartown.movo.ui.components.movo.MovoCard
import io.github.fartown.movo.ui.components.movo.MovoDivider
import io.github.fartown.movo.ui.components.movo.MovoPillButton
import io.github.fartown.movo.ui.components.movo.MovoSwitch
import io.github.fartown.movo.ui.components.movo.RowTrailing
import io.github.fartown.movo.ui.components.movo.SettingsRow
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Text

/**
 * 世界书编辑（角色编辑页「高级设置」内）：「世界书」卡（展开 / 收起；展开后名称、扫描深度、Token 预算、递归匹配）；
 * 「条目」卡（每条一行：点击展开编辑、右侧开关启停；底部「添加条目」）。
 */
internal fun LazyListScope.characterWorldbookEditor(
    store: CharacterLibraryStore,
    expanded: Boolean,
    expandedEntry: Int?,
    onToggleExpanded: () -> Unit,
    onExpandEntry: (Int?) -> Unit,
) {
    val card = store.draft ?: return
    val book = card.worldbookDraft()
    val enabled = !store.busy
    item(key = "worldbook") {
        MovoCard {
            CardTitle("世界书")
            CharacterRow(
                title = "内嵌世界书",
                subtitle = "${book.entries.size} 个条目 · ${if (expanded) "收起" else "展开编辑"}",
                showDivider = expanded,
                onClick = onToggleExpanded,
            ) {
                MovoIcon(
                    if (expanded) MovoIcons.ChevronUp else MovoIcons.ChevronDown,
                    null,
                    size = MovoSize.iconSmall,
                    tint = MovoColors.textTertiary,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(MovoSpacing.sm))
                CharacterTextField("世界书名称", book.name, { value -> store.updateWorldbook { it.copy(name = value) } }, enabled, singleLine = true)
                CharacterTextField("扫描最近消息数（留空使用默认值）", book.scanDepth?.toString().orEmpty(), { value ->
                    optionalNonNegativeInt(value) { number -> store.updateWorldbook { it.copy(scanDepth = number) } }
                }, enabled, singleLine = true)
                CharacterTextField("Token 预算（留空自动分配）", book.tokenBudget?.toString().orEmpty(), { value ->
                    optionalNonNegativeInt(value) { number -> store.updateWorldbook { it.copy(tokenBudget = number) } }
                }, enabled, singleLine = true)
                SettingsRow(
                    title = "递归匹配",
                    subtitle = "使用已匹配条目的内容继续寻找相关条目",
                    trailing = RowTrailing.Switch(book.recursiveScanning == true) { value ->
                        store.updateWorldbook { it.copy(recursiveScanning = value) }
                    },
                    enabled = enabled,
                    showDivider = false,
                )
            }
        }
    }
    if (!expanded) return
    val unsupported = CharacterWorldbook.unsupportedEntries(card).associate { it.index to it.reasons }
    item(key = "worldbook-entries") {
        MovoCard(bottomPadding = MovoSpacing.md) {
            CardTitle("条目", trailing = book.entries.size.toString())
            book.entries.forEachIndexed { index, entry ->
                CharacterRow(
                    title = entry.name.take(120).ifBlank { "条目 ${index + 1}" },
                    subtitle = when {
                        !entry.enabled -> "已停用"
                        !unsupported[index].isNullOrEmpty() -> "已跳过：${unsupported[index].orEmpty().joinToString("；")}"
                        entry.constant -> "始终参与上下文"
                        else -> entry.keys.take(4).joinToString("、").take(160).ifBlank { "尚未设置触发关键词" }
                    },
                    onClick = { onExpandEntry(if (expandedEntry == index) null else index) },
                ) {
                    MovoSwitch(
                        checked = entry.enabled,
                        enabled = enabled,
                        onCheckedChange = { value ->
                            store.updateWorldbook { current ->
                                current.copy(entries = current.entries.toMutableList().apply { this[index] = entry.copy(enabled = value) })
                            }
                        },
                    )
                }
                if (expandedEntry == index) {
                    CharacterWorldbookEntryEditor(
                        entry = entry,
                        enabled = enabled,
                        unsupported = unsupported[index].orEmpty(),
                        onChange = { replacement ->
                            store.updateWorldbook { current -> current.copy(entries = current.entries.toMutableList().apply { this[index] = replacement }) }
                        },
                        onDelete = {
                            onExpandEntry(null)
                            store.updateWorldbook { current -> current.copy(entries = current.entries.filterIndexed { i, _ -> i != index }) }
                        },
                    )
                    MovoDivider(start = MovoSpacing.lg)
                }
            }
            MovoPillButton(
                label = "添加条目",
                icon = MovoIcons.Plus,
                enabled = enabled,
                modifier = Modifier.padding(start = MovoSpacing.lg, top = MovoSpacing.md),
                onClick = {
                    onExpandEntry(book.entries.size)
                    store.updateWorldbook { it.copy(entries = it.entries + CharacterBookEntryDraft(insertionOrder = it.entries.size)) }
                },
            )
        }
    }
}

@Composable
private fun CharacterWorldbookEntryEditor(
    entry: CharacterBookEntryDraft,
    enabled: Boolean,
    unsupported: List<String>,
    onChange: (CharacterBookEntryDraft) -> Unit,
    onDelete: () -> Unit,
) {
    if (unsupported.isNotEmpty()) {
        Text(
            "此条目暂不参与匹配：${unsupported.joinToString("；")}",
            style = MovoTypography.labelRegular,
            modifier = Modifier.padding(horizontal = MovoSpacing.lg, vertical = MovoSpacing.sm),
            color = MovoColors.textSecondary,
        )
    }
    CharacterFieldGroupLabel("内容")
    CharacterTextField("条目标题", entry.name, { onChange(entry.copy(name = it)) }, enabled, singleLine = true)
    CharacterTextField("内容", entry.content, { onChange(entry.copy(content = it)) }, enabled, minLines = 4)
    CharacterFieldGroupLabel("触发")
    CharacterTextField("主关键词（每行一个）", entry.keys.joinToString("\n"), { onChange(entry.copy(keys = it.lines())) }, enabled)
    CharacterTextField("次级关键词（每行一个）", entry.secondaryKeys.joinToString("\n"), { onChange(entry.copy(secondaryKeys = it.lines())) }, enabled)
    SettingsRow(
        title = "同时匹配次级关键词",
        trailing = RowTrailing.Switch(entry.selective) { onChange(entry.copy(selective = it)) },
        enabled = enabled,
        showDivider = false,
    )
    CharacterFieldGroupLabel("插入")
    SettingsRow(
        title = "常驻上下文",
        trailing = RowTrailing.Switch(entry.constant) { onChange(entry.copy(constant = it)) },
        enabled = enabled,
    )
    SettingsRow(
        title = "放在角色设定之前",
        subtitle = "关闭时放在角色设定之后",
        trailing = RowTrailing.Switch(entry.position == "before_char") {
            onChange(entry.copy(position = if (it) "before_char" else "after_char"))
        },
        enabled = enabled,
        showDivider = false,
    )
    CharacterTextField("插入顺序", entry.insertionOrder.toString(), { value ->
        value.toIntOrNull()?.takeIf { it >= 0 }?.let { onChange(entry.copy(insertionOrder = it)) }
    }, enabled, singleLine = true)
    MovoPillButton(
        label = "移除条目",
        icon = MovoIcons.Trash2,
        enabled = enabled,
        onClick = onDelete,
        modifier = Modifier.padding(horizontal = MovoSpacing.lg, vertical = MovoSpacing.md),
    )
}

private fun optionalNonNegativeInt(value: String, onValue: (Int?) -> Unit) {
    if (value.isBlank()) onValue(null)
    else value.toIntOrNull()?.takeIf { it >= 0 }?.let(onValue)
}
