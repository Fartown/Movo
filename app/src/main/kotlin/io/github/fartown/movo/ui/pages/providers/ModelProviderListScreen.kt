package io.github.fartown.movo.ui.pages.providers

import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.MovoApp
import io.github.fartown.movo.R
import io.github.fartown.movo.data.auth.ChatGptAccountState
import io.github.fartown.movo.data.auth.ChatGptAuth
import io.github.fartown.movo.data.auth.ChatGptLoginManager
import io.github.fartown.movo.data.model.Model
import io.github.fartown.movo.data.model.ProviderSetting
import io.github.fartown.movo.data.model.ProviderSourceTypes
import io.github.fartown.movo.data.provider.BuiltinProviders
import io.github.fartown.movo.data.provider.ProviderCatalog
import io.github.fartown.movo.data.repository.ProviderRepository
import io.github.fartown.movo.data.repository.RuntimeConfigRepository
import io.github.fartown.movo.ui.components.movo.CardTitle
import io.github.fartown.movo.ui.components.movo.MovoBlockButton
import io.github.fartown.movo.ui.components.movo.MovoCard
import io.github.fartown.movo.ui.components.movo.MovoConfirmDialog
import io.github.fartown.movo.ui.components.movo.MovoDivider
import io.github.fartown.movo.ui.components.movo.MovoListPage
import io.github.fartown.movo.ui.components.movo.MovoPillButton
import io.github.fartown.movo.ui.components.movo.PressKind
import io.github.fartown.movo.ui.components.movo.movoAnimateItem
import io.github.fartown.movo.ui.components.movo.movoClickable
import io.github.fartown.movo.ui.navigation.AppRoute
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text

/** 服务商行 Logo 28（圆形 + 0.5 描边）；ChatGPT 卡 Logo 40（Figma「15」）。 */
private val ProviderRowLogo = 28.dp
private val ChatGptLogo = 40.dp

/** ChatGPT 是品牌名，各语言一致。 */
private const val CHATGPT_NAME = "ChatGPT"

/** 服务商行文字起点（卡内 16 + Logo 28 + 12）：分隔线从文字起。 */
private val ProviderRowTextStart = 56.dp

/**
 * 设置 · 模型（服务商页，规范「设置 → 模型（服务商页）」，Figma「15」）：从上到下三张卡——
 * ① ChatGPT（未登录 = 说明 + 整行「登录 ChatGPT」；已登录 = 一行 68，点行进详情）；
 * ② 我的服务商（只列已配置的：自己添加的与 .env 打包的，后者挂「预置」）；
 * ③ 可以添加（未添加的常用模板前 4 个 +「更多服务商与自定义接口」）。
 * 这一页点行是进详情、不是切换：不用单选的浅紫整行，全页只有一个浅紫「当前」小标签。
 */
@Composable
internal fun ModelProviderListScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // flow 只建一次；初始值 null = 还在读取，读到之前不先闪「还没有添加服务商」（B8）。
    val loadedProviders by remember { ProviderRepository.providersFlow() }.collectAsState(initial = null)
    val providers = loadedProviders.orEmpty()
    val selectedProviderId by remember { RuntimeConfigRepository.selectedProviderIdFlow() }.collectAsState(initial = null)
    val selectedModelId by remember { RuntimeConfigRepository.selectedModelIdFlow() }.collectAsState(initial = null)
    val account by ChatGptAuth.accountState.collectAsState()
    var providerToDelete by remember { mutableStateOf<ProviderSetting?>(null) }
    // 对话框退场动画期间仍显示被删除的服务商名称。
    var lastProviderToDelete by remember { mutableStateOf<ProviderSetting?>(null) }
    if (providerToDelete != null) lastProviderToDelete = providerToDelete
    var loginError by remember { mutableStateOf<String?>(null) }
    val failPrefix = stringResource(R.string.page_fail_3e3c80)
    val login = rememberChatGptLoginController(
        scope = scope,
        // 成功不另外提示（卡片直接变成已登录）；只把失败留在卡内。
        onStatus = { message -> loginError = message.takeIf { it.startsWith(failPrefix) } },
        // 登录后 ChatGPT 变为可用：没有可用的当前模型时由 repairSelection 选上它，并同步给运行时。
        onSucceeded = { scope.launch { RuntimeConfigRepository.ensureDefaults(MovoApp.serviceInstance) } },
    )

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(MovoApp.serviceInstance)
    }

    val chatGpt = remember(providers) { providers.firstOrNull(ProviderCatalog::isChatGpt) }
    val configured = remember(providers) { ProviderCatalog.configuredProviders(providers) }
    val templates = remember(providers) { ProviderCatalog.availableTemplates(providers).take(4) }
    val openTemplate: (String) -> Unit = { templateId ->
        onNavigate(templateRoute(templateId, providers))
    }

    MovoListPage(title = stringResource(R.string.provider_page_title), onBack = onBack) {
        item(key = "chatgpt") {
            ChatGptCard(
                account = account,
                provider = chatGpt,
                isCurrent = selectedProviderId == BuiltinProviders.CHATGPT_ID,
                selectedModelId = selectedModelId,
                login = login,
                loginError = loginError,
                onLogin = {
                    loginError = null
                    login.start()
                },
                onOpen = { onNavigate(AppRoute.ModelProviderDetail(BuiltinProviders.CHATGPT_ID)) },
                modifier = movoAnimateItem(),
            )
        }

        if (loadedProviders == null) return@MovoListPage
        item(key = "mine") {
            // 列表增删（B7）：卡片高度 `standard`，行按 id 保持身份；空状态 ↔ 列表交叉淡化 `fast`。
            MovoCard(modifier = movoAnimateItem()) {
                CardTitle(
                    text = stringResource(R.string.provider_mine_title),
                    // Figma 15-1 只有一个时不显示数量，多个时右侧「N 个」。
                    trailing = configured.size.takeIf { it > 1 }?.let {
                        pluralStringResource(R.plurals.provider_mine_count, it, it)
                    },
                )
                Crossfade(targetState = configured.isEmpty(), animationSpec = MovoMotion.fast(), label = "providerMineEmpty") { empty ->
                    if (empty) {
                        Text(
                            text = stringResource(R.string.provider_mine_empty),
                            style = MovoTypography.labelRegular,
                            color = MovoColors.textSecondary,
                            modifier = Modifier.padding(horizontal = MovoSpacing.lg, vertical = MovoSpacing.md),
                        )
                    } else {
                        Column {
                            configured.forEachIndexed { index, provider ->
                                key(provider.id) {
                                    ConfiguredProviderRow(
                                        provider = provider,
                                        isCurrent = provider.id == selectedProviderId,
                                        selectedModelId = selectedModelId,
                                        showDivider = index != configured.lastIndex,
                                        onOpen = { onNavigate(AppRoute.ModelProviderDetail(provider.id)) },
                                        // .env 打包的不能长按删除；其余（含添加过的预设）删除后回到「可以添加」。
                                        onDelete = if (ProviderCatalog.isPackaged(provider)) {
                                            null
                                        } else {
                                            { providerToDelete = provider }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "available") {
            MovoCard(modifier = movoAnimateItem()) {
                CardTitle(
                    text = stringResource(R.string.provider_available_title),
                    trailing = stringResource(R.string.provider_available_hint),
                )
                templates.forEach { template ->
                    key(template.id) {
                        TemplateProviderRow(
                            template = template,
                            showDivider = true,
                            onOpen = { openTemplate(template.id) },
                            trailing = {
                                MovoPillButton(
                                    label = stringResource(R.string.provider_add),
                                    onClick = { openTemplate(template.id) },
                                )
                            },
                        )
                    }
                }
                ProviderPageRow(
                    title = stringResource(R.string.provider_more_entry),
                    subtitle = null,
                    leading = { ProviderIconTile(MovoIcons.Plug, size = ProviderRowLogo) },
                    showDivider = false,
                    onClick = { onNavigate(AppRoute.ModelProviderMore) },
                )
            }
        }
    }

    ChatGptLoginDialog(login)

    MovoConfirmDialog(
        show = providerToDelete != null,
        title = stringResource(R.string.ui_remove_provider_9f848f),
        message = stringResource(R.string.provider_delete_summary, lastProviderToDelete?.name.orEmpty()),
        confirmText = stringResource(R.string.ui_delete_3755f5),
        destructive = true,
        onDismissRequest = { providerToDelete = null },
        onConfirm = {
            scope.launch {
                providerToDelete?.let { provider ->
                    ProviderRepository.deleteProvider(provider.id)
                    RuntimeConfigRepository.syncToRemotePreferences(MovoApp.serviceInstance)
                }
                providerToDelete = null
            }
        },
    )
}

/**
 * 打开模板：没落库的走模板详情（填 Key 保存后才写入）；旧版本留下的同 id 占位记录（没 Key 但当时是当前选中）
 * 直接进它的详情，补上 Key 即成为「我的服务商」。
 */
internal fun templateRoute(templateId: String, providers: List<ProviderSetting>): AppRoute =
    if (providers.any { it.id == templateId }) {
        AppRoute.ModelProviderDetail(templateId)
    } else {
        AppRoute.ModelProviderTemplate(templateId)
    }

/**
 * ChatGPT 卡：未登录 = 40 Logo +「ChatGPT」`Title/Section` +「推荐」+ 说明，下方卡内整行 `Button/Block`「登录 ChatGPT」；
 * 已登录 = 一行 68（当前时挂「当前」，说明「正在用 某模型 · 账号」，否则「账号 · N 个模型」），点行进 ChatGPT 详情。
 */
@Composable
private fun ChatGptCard(
    account: ChatGptAccountState,
    provider: ProviderSetting?,
    isCurrent: Boolean,
    selectedModelId: String?,
    login: ChatGptLoginController,
    loginError: String?,
    onLogin: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MovoCard(modifier = modifier) {
        Crossfade(targetState = account.loggedIn, animationSpec = MovoMotion.fast(), label = "chatGptLoggedIn") { loggedIn ->
            if (loggedIn) {
                Column {
                    Spacer(Modifier.height(MovoSpacing.xs))
                    ProviderPageRow(
                        title = CHATGPT_NAME,
                        titleStyleSection = true,
                        subtitle = chatGptSummary(account, provider, isCurrent, selectedModelId),
                        leading = { ProviderBrandIcon(ProviderSourceTypes.CHATGPT, size = ChatGptLogo) },
                        tags = {
                            if (isCurrent) ProviderPageTag(stringResource(R.string.provider_tag_current), ProviderTagTone.Current)
                        },
                        showDivider = false,
                        onClick = onOpen,
                    )
                }
            } else {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = MovoSpacing.lg, end = MovoSpacing.lg, top = MovoSpacing.xl),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProviderBrandIcon(ProviderSourceTypes.CHATGPT, size = ChatGptLogo)
                        Spacer(Modifier.width(MovoSpacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            ProviderTitleLine(
                                title = CHATGPT_NAME,
                                sectionStyle = true,
                                tags = {
                                    ProviderPageTag(stringResource(R.string.provider_tag_recommended), ProviderTagTone.Muted)
                                },
                            )
                            Spacer(Modifier.height(MovoSpacing.xxs))
                            Text(
                                text = stringResource(R.string.provider_chatgpt_intro),
                                style = MovoTypography.labelRegular,
                                color = MovoColors.textSecondary,
                            )
                        }
                    }
                    MovoBlockButton(
                        label = stringResource(
                            if (login.login == ChatGptLoginManager.State.Exchanging) R.string.chatgpt_login_exchanging
                            else R.string.provider_chatgpt_sign_in,
                        ),
                        enabled = !login.busy,
                        onClick = onLogin,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = MovoSpacing.lg, end = MovoSpacing.lg, top = MovoSpacing.lg, bottom = MovoSpacing.md),
                    )
                    loginError?.let { message ->
                        ProviderStatusLine(
                            message = message,
                            isError = true,
                            modifier = Modifier.padding(start = MovoSpacing.lg, end = MovoSpacing.lg, bottom = MovoSpacing.md),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun chatGptSummary(
    account: ChatGptAccountState,
    provider: ProviderSetting?,
    isCurrent: Boolean,
    selectedModelId: String?,
): String {
    val identity = account.email.ifBlank { account.planType.uppercase() }
    val enabledModels = provider?.enabledModels().orEmpty()
    return if (isCurrent) {
        val model = enabledModels.firstOrNull { it.id == selectedModelId } ?: enabledModels.firstOrNull()
        when {
            model == null -> identity.ifBlank { stringResource(R.string.provider_no_models) }
            identity.isBlank() -> stringResource(R.string.provider_in_use, model.label)
            else -> stringResource(R.string.provider_in_use_with_account, model.label, identity)
        }
    } else if (identity.isBlank()) {
        pluralStringResource(R.plurals.provider_models_count, enabledModels.size, enabledModels.size)
    } else {
        pluralStringResource(R.plurals.provider_account_models, enabledModels.size, identity, enabledModels.size)
    }
}

/**
 * 「我的服务商」行 68：28 Logo → 12 → 名称 `Body/Strong` + 标签（预置 / 当前 / 已停用）→ 说明 `Label/Regular` 次要色
 * （当前：「正在用 某模型」；否则「某模型 等 N 个模型」）。点行进详情，长按删除；停用的整体降为 60% 不透明度。
 */
@Composable
private fun ConfiguredProviderRow(
    provider: ProviderSetting,
    isCurrent: Boolean,
    selectedModelId: String?,
    showDivider: Boolean,
    onOpen: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val enabledModels = provider.enabledModels()
    val currentModel = if (isCurrent) {
        enabledModels.firstOrNull { it.id == selectedModelId } ?: enabledModels.firstOrNull()
    } else {
        null
    }
    ProviderPageRow(
        title = provider.name,
        subtitle = if (currentModel != null) {
            stringResource(R.string.provider_in_use, currentModel.label)
        } else {
            modelsSummary(enabledModels)
        },
        leading = { ProviderIcon(provider, size = ProviderRowLogo) },
        tags = {
            if (ProviderCatalog.isPackaged(provider)) {
                ProviderPageTag(stringResource(R.string.provider_tag_packaged), ProviderTagTone.Muted)
            }
            if (isCurrent) ProviderPageTag(stringResource(R.string.provider_tag_current), ProviderTagTone.Current)
            if (!provider.isEnabled) ProviderPageTag(stringResource(R.string.ui_disabled_0fe5a9), ProviderTagTone.Muted)
        },
        contentAlpha = if (provider.isEnabled) 1f else 0.6f,
        showDivider = showDivider,
        onLongClick = onDelete,
        onClick = onOpen,
    )
}

/** 模板行：28 Logo → 名称 → 一句介绍（常用模型 / 定位）；右侧由调用方决定（「添加」按钮或 ›）。 */
@Composable
internal fun TemplateProviderRow(
    template: ProviderSetting,
    showDivider: Boolean,
    onOpen: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    ProviderPageRow(
        title = template.name,
        subtitle = providerTemplateTagline(template.id)?.let { stringResource(it) },
        leading = { ProviderIcon(template, size = ProviderRowLogo) },
        trailing = trailing,
        showDivider = showDivider,
        onClick = onOpen,
    )
}

@Composable
private fun modelsSummary(models: List<Model>): String =
    when (models.size) {
        0 -> stringResource(R.string.provider_no_models)
        1 -> models.single().label
        else -> pluralStringResource(R.plurals.provider_models_summary, models.size, models.first().label, models.size)
    }

private fun ProviderSetting.enabledModels(): List<Model> =
    models.filter(Model::isEnabled).sortedBy(Model::sortOrder)

private val Model.label: String
    get() = displayName.ifBlank { modelId }

/** 模板的一句介绍（Figma「15」可以添加 / 更多服务商）。 */
@StringRes
internal fun providerTemplateTagline(templateId: String): Int? =
    when (templateId) {
        BuiltinProviders.OPENAI_ID -> R.string.provider_tagline_openai
        BuiltinProviders.ANTHROPIC_ID -> R.string.provider_tagline_anthropic
        BuiltinProviders.DEEPSEEK_ID -> R.string.provider_tagline_deepseek
        BuiltinProviders.KIMI_ID -> R.string.provider_tagline_kimi
        BuiltinProviders.BAILIAN_ID -> R.string.provider_tagline_bailian
        BuiltinProviders.MINIMAX_ID -> R.string.provider_tagline_minimax
        BuiltinProviders.SILICONFLOW_ID -> R.string.provider_tagline_siliconflow
        BuiltinProviders.OPENROUTER_ID -> R.string.provider_tagline_openrouter
        BuiltinProviders.STEPFUN_ID -> R.string.provider_tagline_stepfun
        BuiltinProviders.MIMO_ID -> R.string.provider_tagline_mimo
        else -> null
    }

/**
 * 服务商页的行（Figma「15」`Row/Provider`）：左右 16，Logo（28 / 40）→ 12 → 标题行（名称 + 标签）→ 说明；
 * 有说明时最小高 68，否则 56。右侧默认 16 箭头（三级色），可换成「添加」按钮。分隔线从文字起、右侧内缩 16。
 */
@Composable
internal fun ProviderPageRow(
    title: String,
    subtitle: String?,
    leading: @Composable () -> Unit,
    showDivider: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    titleStyleSection: Boolean = false,
    tags: @Composable RowScope.() -> Unit = {},
    trailing: (@Composable () -> Unit)? = null,
    contentAlpha: Float = 1f,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .movoClickable(kind = PressKind.Row, onLongClick = onLongClick, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (subtitle != null) 68.dp else 56.dp)
                .padding(horizontal = MovoSpacing.lg, vertical = MovoSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.graphicsLayer { alpha = contentAlpha }) { leading() }
            Spacer(Modifier.width(MovoSpacing.md))
            Column(modifier = Modifier.weight(1f).graphicsLayer { alpha = contentAlpha }) {
                ProviderTitleLine(title = title, sectionStyle = titleStyleSection, tags = tags)
                if (subtitle != null) {
                    Spacer(Modifier.height(MovoSpacing.xxs))
                    Text(
                        text = subtitle,
                        style = MovoTypography.labelRegular,
                        color = MovoColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(MovoSpacing.md))
            if (trailing != null) {
                trailing()
            } else {
                MovoIcon(MovoIcons.ChevronRight, null, size = MovoSize.iconSmall, tint = MovoColors.textTertiary)
            }
        }
        if (showDivider) {
            MovoDivider(modifier = Modifier.align(Alignment.BottomStart), start = ProviderRowTextStart)
        }
    }
}

/** 标题行：名称（`Body/Strong`，ChatGPT 卡用 `Title/Section`）单行省略，标签跟在后面、间距 6。 */
@Composable
private fun ProviderTitleLine(
    title: String,
    sectionStyle: Boolean,
    tags: @Composable RowScope.() -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = if (sectionStyle) MovoTypography.titleSection else MovoTypography.bodyStrong,
            color = MovoColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        tags()
    }
}

internal enum class ProviderTagTone { Current, Muted }

/**
 * 服务商页小标签（Figma「15」）：`Micro/Medium`、圆角 6、内边距 6×1，与名称间距 6。
 * 「当前」= `accent/indigo-bg` + `accent/indigo-fg`（全页只有一个）；「预置」「推荐」「已停用」= `bg/surface-muted` + `text/secondary`。
 */
@Composable
internal fun ProviderPageTag(
    text: String,
    tone: ProviderTagTone,
) {
    val (background, foreground) = when (tone) {
        ProviderTagTone.Current -> MovoColors.indigoBg to MovoColors.indigoFg
        ProviderTagTone.Muted -> MovoColors.bgSurfaceMuted to MovoColors.textSecondary
    }
    Spacer(Modifier.width(6.dp))
    Text(
        text = text,
        style = MovoTypography.microMedium,
        color = foreground,
        maxLines = 1,
        modifier = Modifier
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
