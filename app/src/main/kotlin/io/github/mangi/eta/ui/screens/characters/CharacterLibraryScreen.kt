package io.github.mangi.eta.ui.screens.characters

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.components.movo.BlockTone
import io.github.mangi.eta.ui.components.movo.CardFooter
import io.github.mangi.eta.ui.components.movo.CardTitle
import io.github.mangi.eta.ui.components.movo.MovoBlockButton
import io.github.mangi.eta.ui.components.movo.MovoCard
import io.github.mangi.eta.ui.components.movo.MovoDialogHost
import io.github.mangi.eta.ui.components.movo.MovoDivider
import io.github.mangi.eta.ui.components.movo.MovoIconButton
import io.github.mangi.eta.ui.components.movo.MovoListPage
import io.github.mangi.eta.ui.components.movo.MovoPillButton
import io.github.mangi.eta.ui.components.movo.PressKind
import io.github.mangi.eta.ui.components.movo.RowTrailing
import io.github.mangi.eta.ui.components.movo.SettingsRow
import io.github.mangi.eta.ui.components.movo.movoClickable
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.theme.LocalReducedMotion
import io.github.mangi.eta.ui.theme.MovoColors
import io.github.mangi.eta.ui.theme.MovoIcon
import io.github.mangi.eta.ui.theme.MovoIcons
import io.github.mangi.eta.ui.theme.MovoMotion
import io.github.mangi.eta.ui.theme.MovoRadius
import io.github.mangi.eta.ui.theme.MovoSize
import io.github.mangi.eta.ui.theme.MovoSpacing
import io.github.mangi.eta.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Text

/**
 * 角色库（规范 8.7 二级页）：顶栏导入角色卡 / 创建角色；搜索框（规范 8.6 搜索框样式）；
 * 「角色库」卡片列出角色（名称 + 简介或标签），空库 / 无结果在卡内说明；「我的」卡片进入我的人设。
 */
@Composable
internal fun CharacterLibraryScreen(
    store: CharacterLibraryStore,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) store.importCard(uri) { onNavigate(AppRoute.CharacterDetail(it)) }
    }
    val importCard = {
        importer.launch(arrayOf("image/png", "application/json", "text/plain", "application/octet-stream"))
    }
    val createCharacter = {
        store.discardEditor()
        onNavigate(AppRoute.CharacterEditor())
    }
    MovoListPage(
        title = "角色",
        onBack = onBack,
        actions = {
            MovoIconButton(
                icon = MovoIcons.Upload,
                contentDescription = "导入角色卡",
                onClick = importCard,
                enabled = !store.busy,
            )
            MovoIconButton(
                icon = MovoIcons.Plus,
                contentDescription = "创建角色",
                onClick = createCharacter,
                enabled = !store.busy,
            )
        },
    ) {
        item(key = "search") {
            CharacterSearchField(
                query = store.query,
                onQueryChange = { store.query = it },
                placeholder = "搜索名称或标签",
            )
        }
        when {
            store.busy && store.characters.isEmpty() -> {
                item(key = "loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = MovoSpacing.section + MovoSpacing.lg),
                        contentAlignment = Alignment.Center,
                    ) {
                        CharacterSpinner(size = MovoSize.iconLarge)
                    }
                }
            }
            store.characters.isEmpty() -> {
                item(key = "empty-library") {
                    MovoCard {
                        CharacterEmptyBlock(
                            title = "还没有角色",
                            summary = "创建一个角色，或导入 PNG、JSON 角色卡开始对话",
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(MovoSpacing.sm)) {
                                MovoPillButton(
                                    label = "恢复默认角色",
                                    onClick = { store.restoreDefaultCharacter() },
                                    enabled = !store.busy,
                                )
                                MovoPillButton(
                                    label = "创建角色",
                                    onClick = createCharacter,
                                    icon = MovoIcons.Plus,
                                    primary = true,
                                )
                            }
                        }
                    }
                }
            }
            store.filteredCharacters.isEmpty() -> {
                item(key = "empty-search") {
                    MovoCard {
                        CharacterEmptyBlock(
                            title = "没有找到匹配的角色",
                            summary = "换个关键词试试，搜索会匹配名称与标签",
                        )
                    }
                }
            }
        }
        val characters = store.filteredCharacters
        if (characters.isNotEmpty()) {
            item(key = "characters") {
                MovoCard {
                    CardTitle(stringResource(R.string.movo_character_group_library), trailing = characters.size.toString())
                    characters.forEachIndexed { index, profile ->
                        CharacterRow(
                            title = profile.card.name,
                            subtitle = profile.card.description.ifBlank {
                                profile.card.tags.joinToString(" · ")
                            }.takeIf { it.isNotBlank() },
                            showDivider = index != characters.lastIndex,
                            onClick = { if (!store.busy) onNavigate(AppRoute.CharacterDetail(profile.id)) },
                        ) {
                            MovoIcon(MovoIcons.ChevronRight, null, size = MovoSize.iconSmall, tint = MovoColors.textTertiary)
                        }
                    }
                }
            }
        }
        item(key = "persona") {
            MovoCard {
                CardTitle("我的")
                SettingsRow(
                    title = "我的人设",
                    subtitle = "设置角色如何称呼你，以及你在故事中的身份",
                    trailing = RowTrailing.Arrow(),
                    showDivider = false,
                    onClick = { onNavigate(AppRoute.CharacterPersona) },
                )
            }
        }
    }
}

/** 读取中 / 读取失败等页面状态：放在卡片内（规范 8.7 卡片外不放文字）。 */
@Composable
internal fun CharacterPageMessage(text: String) {
    MovoCard {
        Text(
            text = text,
            style = MovoTypography.bodyRegular,
            color = MovoColors.textSecondary,
            modifier = Modifier.fillMaxWidth().padding(MovoSpacing.lg),
        )
    }
}

/**
 * 输入卡片：可选卡内标题（无标题时顶部留 12）→ 输入框 → 可选页脚（一句一行）。
 */
@Composable
internal fun CharacterFieldCard(
    title: String? = null,
    footer: List<String>? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    MovoCard(bottomPadding = if (footer == null) MovoSpacing.md else MovoSpacing.xs) {
        if (title != null) CardTitle(title) else Spacer(Modifier.height(MovoSpacing.md))
        content()
        if (footer != null) {
            Spacer(Modifier.height(MovoSpacing.sm))
            CardFooter(footer)
        }
    }
}

/**
 * 二级页行（icon=false，规范 8.7）：左右 16、上下 14，最小高 56 / 68；标题 15 Medium，说明 13 Regular 次要色、最多两行。
 * 与公共 `SettingsRow` 同样式，额外支持说明行数限制、标题颜色（删除用 Rose）与自定义右侧。
 */
@Composable
internal fun CharacterRow(
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showDivider: Boolean = true,
    role: Role = Role.Button,
    titleColor: Color = MovoColors.textPrimary,
    subtitleMaxLines: Int = 2,
    below: (@Composable ColumnScope.() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.movoClickable(kind = PressKind.Row, enabled = enabled, role = role, onClick = onClick)
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (subtitle != null) 68.dp else 56.dp)
                .padding(horizontal = MovoSpacing.lg, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MovoTypography.bodyStrong, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MovoTypography.labelRegular,
                        color = MovoColors.textSecondary,
                        maxLines = subtitleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                below?.invoke(this)
            }
            Spacer(Modifier.width(MovoSpacing.md))
            trailing()
        }
        if (showDivider) MovoDivider(modifier = Modifier.align(Alignment.BottomStart), start = MovoSpacing.lg)
    }
}

/** 卡内空状态：标题 Body/Strong + 说明 Label/Regular 次要色，对齐内容线 36；可带操作。 */
@Composable
internal fun CharacterEmptyBlock(
    title: String,
    summary: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(MovoSpacing.lg)) {
        Text(title, style = MovoTypography.bodyStrong, color = MovoColors.textPrimary)
        Text(summary, style = MovoTypography.labelRegular, color = MovoColors.textSecondary)
        if (action != null) {
            Spacer(Modifier.height(MovoSpacing.md))
            action()
        }
    }
}

/** 「阅读全文 ›」文字链接（Label/Regular 次要色 + 16 箭头，按压不透明度 60%）。 */
@Composable
internal fun CharacterReadMoreLink(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.movoClickable(PressKind.Link, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("阅读全文", style = MovoTypography.labelRegular, color = MovoColors.textSecondary)
        MovoIcon(MovoIcons.ChevronRight, null, size = MovoSize.iconSmall, tint = MovoColors.textSecondary)
    }
}

/** 长文本对话框：标题 + 可滚动正文 + 「关闭」（规范 8.11 容器）。 */
@Composable
internal fun CharacterTextDialog(
    show: Boolean,
    title: String,
    text: String,
    onDismiss: () -> Unit,
) {
    MovoDialogHost(show = show, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(MovoSpacing.xxl)) {
            Text(title, style = MovoTypography.titleSection, color = MovoColors.textPrimary)
            Spacer(Modifier.height(MovoSpacing.sm))
            Text(
                text,
                style = MovoTypography.bodyRegular,
                color = MovoColors.textSecondary,
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
        MovoBlockButton(
            label = "关闭",
            onClick = onDismiss,
            tone = BlockTone.Secondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(MovoSpacing.xs),
        )
    }
}

/**
 * 搜索框（规范 8.6 `Drawer/Header` 同款）：高 40、圆角 20、bg/surface-muted；16 搜索图标次要色；
 * 占位 Body/Regular 三级色；有内容时右端出现 ✕ 清空。输入即过滤。
 */
@Composable
private fun CharacterSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(MovoRadius.lg)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MovoSize.controlMedium)
            .clip(shape)
            .background(MovoColors.bgSurfaceMuted)
            .padding(start = MovoSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MovoIcon(MovoIcons.Search, null, size = MovoSize.iconSmall, tint = MovoColors.textSecondary)
        Spacer(Modifier.width(MovoSpacing.sm))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    placeholder,
                    style = MovoTypography.bodyRegular,
                    color = MovoColors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MovoTypography.bodyRegular.copy(color = MovoColors.textPrimary),
                cursorBrush = SolidColor(MovoColors.actionPrimaryFg),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    onQueryChange(query)
                    keyboard?.hide()
                }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(MovoSize.controlMedium)
                    .movoClickable(PressKind.Icon, shape = CircleShape) { onQueryChange("") },
                contentAlignment = Alignment.Center,
            ) {
                MovoIcon(
                    MovoIcons.X,
                    contentDescription = stringResource(R.string.movo_action_clear_search),
                    size = MovoSize.iconSmall,
                    tint = MovoColors.textSecondary,
                )
            }
        } else {
            Spacer(Modifier.width(MovoSpacing.md))
        }
    }
}

/** Indigo 加载圈：800ms 一圈 linear；减少动画时为静态完整圆环（规范 9.3、9.8）。 */
@Composable
internal fun CharacterSpinner(size: Dp = MovoSize.iconSmall) {
    if (LocalReducedMotion.current) {
        Canvas(Modifier.size(size)) {
            drawCircle(color = MovoColors.indigoFg, radius = this.size.minDimension * 0.375f, style = Stroke(width = 1.5.dp.toPx()))
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "characterSpinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(MovoMotion.SPINNER_PERIOD, easing = MovoMotion.EasingLinear)),
        label = "characterSpinnerAngle",
    )
    MovoIcon(
        MovoIcons.LoaderCircle,
        contentDescription = null,
        size = size,
        tint = MovoColors.indigoFg,
        modifier = Modifier.graphicsLayer { rotationZ = angle },
    )
}

internal class CharacterLastValue<T : Any> {
    var value: T? = null
}

/** 返回 [value]；为 null 时返回上一次的非空值（给对话框退场动画用）。 */
@Composable
internal fun <T : Any> rememberCharacterLastNonNull(value: T?): T? {
    val holder = remember { CharacterLastValue<T>() }
    if (value != null) holder.value = value
    return value ?: holder.value
}
