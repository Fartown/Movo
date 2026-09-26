package io.github.fartown.movo.ui.screens.characters

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.roleplay.CharacterCardFormat
import io.github.fartown.movo.agent.roleplay.RoleplayBinding
import io.github.fartown.movo.ui.app.CharacterLibraryStore
import io.github.fartown.movo.ui.components.movo.BlockTone
import io.github.fartown.movo.ui.components.movo.CardTitle
import io.github.fartown.movo.ui.components.movo.MovoBlockButton
import io.github.fartown.movo.ui.components.movo.MovoCard
import io.github.fartown.movo.ui.components.movo.MovoConfirmDialog
import io.github.fartown.movo.ui.components.movo.MovoListPage
import io.github.fartown.movo.ui.components.movo.RowTrailing
import io.github.fartown.movo.ui.components.movo.SettingsRow
import io.github.fartown.movo.ui.navigation.AppRoute
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Text

private const val DescriptionPreviewChars = 220
private const val GreetingPreviewChars = 240

private data class CharacterTextPreview(val title: String, val text: String)

/**
 * 角色详情（规范 8.7 二级页）：资料卡（名称、标签、设定摘要 + 阅读全文）→「开始新对话」整行主按钮 →
 * 人设卡（使用我的人设开关、编辑我的人设）→ 开场白卡（单选，✓ 标记当前）→ 管理卡（编辑、剧情记忆、复制、导出 PNG / JSON）→
 * 删除角色单独一张卡（删除前确认）→ 兼容说明（可展开）。
 */
@Composable
internal fun CharacterDetailScreen(
    id: String,
    store: CharacterLibraryStore,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    onStart: (RoleplayBinding, String) -> Unit,
) {
    var showCompatibility by rememberSaveable(id) { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable(id) { mutableStateOf(false) }
    var preview by remember(id) { mutableStateOf<CharacterTextPreview?>(null) }
    val pngExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) {
        if (it != null) store.export(id, CharacterCardFormat.PNG, it)
    }
    val jsonExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        if (it != null) store.export(id, CharacterCardFormat.JSON, it)
    }
    val profile = store.selected?.takeIf { it.id == id }
    MovoListPage(title = profile?.card?.name ?: "角色详情", onBack = onBack) {
        if (profile == null) {
            item { CharacterPageMessage(if (store.busy) "正在读取角色…" else "无法读取角色，请返回后重试") }
            return@MovoListPage
        }
        item(key = "profile") {
            MovoCard {
                Column(modifier = Modifier.fillMaxWidth().padding(MovoSpacing.lg)) {
                    Text(profile.card.name, style = MovoTypography.titleSection, color = MovoColors.textPrimary)
                    if (profile.card.tags.isNotEmpty()) {
                        Text(
                            text = profile.card.tags.joinToString(" · "),
                            style = MovoTypography.labelRegular,
                            color = MovoColors.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (profile.card.description.isNotBlank()) {
                        Spacer(Modifier.height(MovoSpacing.md))
                        Text(
                            text = profile.card.description,
                            style = MovoTypography.bodyRegular,
                            color = MovoColors.textPrimary,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (profile.card.description.length > DescriptionPreviewChars) {
                            Spacer(Modifier.height(MovoSpacing.sm))
                            CharacterReadMoreLink(onClick = {
                                preview = CharacterTextPreview("角色设定", profile.card.description)
                            })
                        }
                    }
                }
            }
        }
        item(key = "start") {
            MovoBlockButton(
                label = "开始新对话",
                enabled = !store.busy,
                onClick = { store.startConversation(id, onStart) },
                tone = BlockTone.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "persona") {
            MovoCard {
                CardTitle(stringResource(R.string.movo_character_group_persona))
                SettingsRow(
                    title = "使用我的人设",
                    subtitle = store.persona.name.ifBlank { "未设置称呼" },
                    trailing = RowTrailing.Switch(store.usePersona) { store.usePersona = it },
                )
                SettingsRow(
                    title = "编辑我的人设",
                    trailing = RowTrailing.Arrow(),
                    showDivider = false,
                    onClick = { onNavigate(AppRoute.CharacterPersona) },
                )
            }
        }
        item(key = "greetings") {
            MovoCard {
                CardTitle("开场白")
                val greetings = listOf(profile.card.firstMessage) + profile.card.alternateGreetings
                greetings.forEachIndexed { index, greeting ->
                    val title = if (index == 0) "默认开场白" else "开场白 ${index + 1}"
                    val selected = store.greetingIndex == index
                    CharacterRow(
                        title = title,
                        subtitle = greeting.take(GreetingPreviewChars)
                            .let { if (greeting.length > GreetingPreviewChars) "$it…" else it }
                            .ifBlank { "没有预设开场白，由你先开口" },
                        subtitleMaxLines = Int.MAX_VALUE,
                        role = Role.RadioButton,
                        showDivider = index != greetings.lastIndex,
                        modifier = Modifier.semantics { this.selected = selected },
                        onClick = { store.greetingIndex = index },
                        below = if (greeting.length > GreetingPreviewChars) {
                            {
                                Spacer(Modifier.height(MovoSpacing.xs))
                                CharacterReadMoreLink(onClick = { preview = CharacterTextPreview(title, greeting) })
                            }
                        } else {
                            null
                        },
                    ) {
                        if (selected) {
                            MovoIcon(MovoIcons.Check, null, size = MovoSize.iconMedium, tint = MovoColors.indigoFg)
                        } else {
                            Spacer(Modifier.size(MovoSize.iconMedium))
                        }
                    }
                }
            }
        }
        item(key = "management") {
            MovoCard {
                CardTitle("管理")
                SettingsRow(
                    title = "编辑角色",
                    enabled = !store.busy,
                    onClick = {
                        store.discardEditor()
                        onNavigate(AppRoute.CharacterEditor(id))
                    },
                )
                SettingsRow(
                    title = "剧情记忆",
                    subtitle = "此角色各次对话共享的故事与关系记录",
                    onClick = { onNavigate(AppRoute.CharacterMemory(id)) },
                )
                SettingsRow(
                    title = "复制角色",
                    enabled = !store.busy,
                    onClick = { store.duplicate(id) { onNavigate(AppRoute.CharacterDetail(it)) } },
                )
                SettingsRow(
                    title = "导出 PNG",
                    enabled = !store.busy,
                    onClick = { pngExporter.launch(characterExportName(profile.card.name, "png")) },
                )
                SettingsRow(
                    title = "导出 JSON",
                    enabled = !store.busy,
                    showDivider = false,
                    onClick = { jsonExporter.launch(characterExportName(profile.card.name, "json")) },
                )
            }
        }
        item(key = "delete") {
            MovoCard {
                CharacterRow(
                    title = "删除角色",
                    subtitle = null,
                    titleColor = MovoColors.roseFg,
                    enabled = !store.busy,
                    showDivider = false,
                    onClick = { showDeleteConfirm = true },
                )
            }
        }
        if (store.compatibilityWarnings.isNotEmpty()) {
            item(key = "compatibility") {
                MovoCard {
                    CharacterRow(
                        title = "兼容说明",
                        subtitle = "${store.compatibilityWarnings.size} 项内容按兼容范围处理",
                        showDivider = showCompatibility,
                        onClick = { showCompatibility = !showCompatibility },
                    ) {
                        MovoIcon(
                            if (showCompatibility) MovoIcons.ChevronUp else MovoIcons.ChevronDown,
                            null,
                            size = MovoSize.iconSmall,
                            tint = MovoColors.textTertiary,
                        )
                    }
                    if (showCompatibility) {
                        Column(modifier = Modifier.padding(horizontal = MovoSpacing.lg, vertical = MovoSpacing.md)) {
                            store.compatibilityWarnings.forEach { warning ->
                                Text(
                                    text = warning,
                                    modifier = Modifier.padding(vertical = MovoSpacing.xs),
                                    style = MovoTypography.labelRegular,
                                    color = MovoColors.textSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val deletingName = rememberCharacterLastNonNull(profile?.card?.name)
    MovoConfirmDialog(
        show = showDeleteConfirm && profile != null,
        title = "删除角色",
        message = "「${deletingName.orEmpty()}」将从角色库移除，角色图片与剧情记忆一并删除；已有对话保留。此操作无法撤销。",
        confirmText = "删除",
        destructive = true,
        confirmEnabled = !store.busy,
        onDismissRequest = { showDeleteConfirm = false },
        onConfirm = {
            showDeleteConfirm = false
            store.delete(id) { onBack() }
        },
    )

    val shownPreview = rememberCharacterLastNonNull(preview)
    CharacterTextDialog(
        show = preview != null,
        title = shownPreview?.title.orEmpty(),
        text = shownPreview?.text.orEmpty(),
        onDismiss = { preview = null },
    )
}

private fun characterExportName(name: String, extension: String): String {
    val basename = name.map { if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it }
        .joinToString("").trim().trimEnd('.').take(64).ifBlank { "角色" }
    return "$basename.$extension"
}
