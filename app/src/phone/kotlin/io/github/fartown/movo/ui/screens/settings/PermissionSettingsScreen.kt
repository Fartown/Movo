package io.github.fartown.movo.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.tools.core.ApprovalCategory
import io.github.fartown.movo.agent.tools.core.ApprovalSettings
import io.github.fartown.movo.agent.tools.core.PermissionMode
import io.github.fartown.movo.ui.components.movo.CardFooter
import io.github.fartown.movo.ui.components.movo.CardTitle
import io.github.fartown.movo.ui.components.movo.MovoAnimatedRows
import io.github.fartown.movo.ui.components.movo.MovoCard
import io.github.fartown.movo.ui.components.movo.MovoDivider
import io.github.fartown.movo.ui.components.movo.MovoListPage
import io.github.fartown.movo.ui.components.movo.MovoPillButton
import io.github.fartown.movo.ui.components.movo.PressKind
import io.github.fartown.movo.ui.components.movo.RowTrailing
import io.github.fartown.movo.ui.components.movo.SettingsRow
import io.github.fartown.movo.ui.components.movo.movoAnimateItem
import io.github.fartown.movo.ui.components.movo.movoClickable
import io.github.fartown.movo.ui.components.movo.movoSelectedRow
import io.github.fartown.movo.ui.components.movo.movoSelectedTextColor
import io.github.fartown.movo.ui.navigation.AppRoute
import io.github.fartown.movo.ui.pages.providers.CardSegment
import io.github.fartown.movo.ui.pages.providers.MovoSearchField
import io.github.fartown.movo.ui.pages.providers.movoCardSegment
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoRadius
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text

/**
 * 设置 · 工具 · 权限（定稿 19，方案见 docs/research/tool-redesign/Movo 权限模式方案.md）：
 * 权限模式二选一（整行浅紫单选）→ 手动审批时总会先问的四类（只列出、不能关）→ 你加的规则（默认都关）→
 * 在这些应用里每一步都先问我（默认没有）。规则区一直显示，切模式时页面不跳。
 */
@Composable
internal fun PermissionSettingsScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val policy by remember { ApprovalSettings.state(context) }.collectAsState()

    MovoListPage(title = stringResource(R.string.permission_title), onBack = onBack) {
        item(key = "mode") {
            MovoCard {
                CardTitle(stringResource(R.string.permission_mode))
                ModeRow(
                    title = stringResource(R.string.permission_mode_yolo),
                    subtitle = stringResource(R.string.permission_mode_yolo_desc),
                    selected = policy.mode == PermissionMode.YOLO,
                    onClick = { ApprovalSettings.setMode(context, PermissionMode.YOLO) },
                )
                ModeRow(
                    title = stringResource(R.string.permission_mode_manual),
                    subtitle = stringResource(R.string.permission_mode_manual_desc),
                    selected = policy.mode == PermissionMode.MANUAL,
                    onClick = { ApprovalSettings.setMode(context, PermissionMode.MANUAL) },
                )
            }
        }
        item(key = "fixed") {
            MovoCard {
                CardTitle(stringResource(R.string.permission_fixed_title))
                val always = stringResource(R.string.permission_fixed_value)
                val rows = listOf(
                    R.string.permission_fixed_payment to R.string.permission_fixed_payment_desc,
                    R.string.permission_fixed_password to R.string.permission_fixed_password_desc,
                    R.string.permission_fixed_delete to R.string.permission_fixed_delete_desc,
                    R.string.permission_fixed_send to R.string.permission_fixed_send_desc,
                )
                rows.forEachIndexed { index, (title, subtitle) ->
                    SettingsRow(
                        title = stringResource(title),
                        subtitle = stringResource(subtitle),
                        trailing = RowTrailing.Value(always),
                        showDivider = index != rows.lastIndex,
                    )
                }
                CardFooter(listOf(stringResource(R.string.permission_fixed_footer)))
            }
        }
        item(key = "rules") {
            MovoCard {
                CardTitle(stringResource(R.string.permission_rules_title))
                RULE_ROWS.forEachIndexed { index, rule ->
                    SettingsRow(
                        title = stringResource(rule.title),
                        subtitle = stringResource(rule.subtitle),
                        trailing = RowTrailing.Switch(
                            checked = rule.category in policy.categories,
                            onCheckedChange = { ask -> ApprovalSettings.setCategory(context, rule.category, ask) },
                        ),
                        showDivider = index != RULE_ROWS.lastIndex,
                    )
                }
                CardFooter(listOf(stringResource(R.string.permission_rules_footer)))
            }
        }
        item(key = "apps") {
            val apps = remember(policy.apps) { policy.apps.sortedBy { appLabelOf(context, it) } }
            MovoCard {
                CardTitle(
                    stringResource(R.string.permission_apps_title),
                    trailing = apps.takeIf { it.isNotEmpty() }
                        ?.let { stringResource(R.string.permission_apps_count, it.size) },
                )
                // 移除按「列表增删」收起（规范 9.3），不用二次确认：随时能再加回来。
                MovoAnimatedRows(items = apps, key = { it }) { pkg ->
                    AppRow(packageName = pkg, label = appLabelOf(context, pkg)) {
                        MovoPillButton(
                            label = stringResource(R.string.permission_apps_remove),
                            onClick = { ApprovalSettings.removeApp(context, pkg) },
                        )
                    }
                }
                AddAppRow(onClick = { onNavigate(AppRoute.PermissionAddApp) })
                CardFooter(listOf(stringResource(R.string.permission_apps_footer)))
            }
        }
    }
}

/**
 * 权限 · 添加应用（定稿 19 F-1）：列出能在桌面打开的应用，按名称排序，顶部可搜索。
 * 点「添加」原地变成灰色「已添加」，不跳动；返回后出现在权限页的应用卡里。
 */
@Composable
internal fun PermissionAddAppScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val policy by remember { ApprovalSettings.state(context) }.collectAsState()
    val installed by produceState<List<InstalledApp>?>(null) {
        value = withContext(Dispatchers.IO) { launchableApps(context) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(installed, query) {
        val q = query.trim()
        installed.orEmpty().filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
    }

    MovoListPage(title = stringResource(R.string.permission_add_title), onBack = onBack, itemSpacing = 0.dp) {
        item(key = "search", contentType = "search") {
            MovoSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.permission_add_search),
                modifier = Modifier.padding(bottom = MovoSpacing.lg),
            )
        }
        // 还在读已安装的应用时只显示搜索框，读完整张卡一起出现。
        if (installed == null) return@MovoListPage
        item(key = "title", contentType = "section_title") {
            CardTitle(
                text = stringResource(R.string.permission_add_section),
                trailing = stringResource(R.string.permission_add_sorted),
                modifier = movoAnimateItem().movoCardSegment(CardSegment.Top),
            )
        }
        if (matches.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                Text(
                    text = stringResource(R.string.permission_add_empty),
                    style = MovoTypography.labelRegular,
                    color = MovoColors.textSecondary,
                    modifier = movoAnimateItem()
                        .movoCardSegment(CardSegment.Bottom)
                        .padding(horizontal = MovoSpacing.lg, vertical = MovoSpacing.md),
                )
            }
        } else {
            items(matches, key = { it.packageName }, contentType = { "app" }) { app ->
                val last = app === matches.last()
                val added = app.packageName in policy.apps
                AppRow(
                    packageName = app.packageName,
                    label = app.label,
                    showDivider = !last,
                    modifier = movoAnimateItem()
                        .movoCardSegment(if (last) CardSegment.Bottom else CardSegment.Middle)
                        .padding(bottom = if (last) MovoSpacing.xs else 0.dp),
                ) {
                    AddPill(added = added, onAdd = { ApprovalSettings.addApp(context, app.packageName) })
                }
            }
        }
    }
}

private class RuleRow(val category: ApprovalCategory, val title: Int, val subtitle: Int)

private val RULE_ROWS = listOf(
    RuleRow(ApprovalCategory.SYSTEM, R.string.permission_rule_system, R.string.permission_rule_system_desc),
    RuleRow(ApprovalCategory.ROOT, R.string.permission_rule_root, R.string.permission_rule_root_desc),
    RuleRow(ApprovalCategory.INSTALL, R.string.permission_rule_install, R.string.permission_rule_install_desc),
    RuleRow(ApprovalCategory.OUTBOUND, R.string.permission_rule_outbound, R.string.permission_rule_outbound_desc),
    RuleRow(ApprovalCategory.FILES, R.string.permission_rule_files, R.string.permission_rule_files_desc),
)

/** 卡内单选行（规范 8.11「单选」整行浅紫，不放 ✓）：标题 + 一行说明。 */
@Composable
private fun ModeRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .movoSelectedRow(selected)
            .semantics { this.selected = selected }
            .movoClickable(kind = PressKind.Row, role = Role.RadioButton, onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(horizontal = MovoSpacing.lg, vertical = 14.dp),
        ) {
            Text(title, style = MovoTypography.bodyStrong, color = movoSelectedTextColor(selected), maxLines = 1)
            Text(subtitle, style = MovoTypography.labelRegular, color = MovoColors.textSecondary)
        }
    }
}

/** 应用行：图标 28 → 12 → 应用名，右侧放操作；分隔线从文字起。 */
@Composable
private fun AppRow(
    packageName: String,
    label: String,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
    trailing: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = MovoSpacing.lg, vertical = MovoSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(packageName)
            Spacer(Modifier.width(MovoSpacing.md))
            Text(
                label,
                style = MovoTypography.bodyStrong,
                color = MovoColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(MovoSpacing.sm))
            trailing()
        }
        if (showDivider) MovoDivider(modifier = Modifier.align(Alignment.BottomStart), start = APP_TEXT_START)
    }
}

/** 「添加应用」行：浅底 + 号块 28，右侧箭头。 */
@Composable
private fun AddAppRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .movoClickable(kind = PressKind.Row, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = MovoSpacing.lg, vertical = MovoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(APP_ICON)
                .clip(RoundedCornerShape(MovoRadius.xs))
                .background(MovoColors.bgSurfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            MovoIcon(MovoIcons.Plus, contentDescription = null, size = MovoSize.iconSmall, tint = MovoColors.textPrimary)
        }
        Spacer(Modifier.width(MovoSpacing.md))
        Text(
            stringResource(R.string.permission_apps_add),
            style = MovoTypography.bodyStrong,
            color = MovoColors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        MovoIcon(MovoIcons.ChevronRight, contentDescription = null, size = MovoSize.iconSmall, tint = MovoColors.textTertiary)
    }
}

/** 「添加」→「已添加」原地换字换色（`fast`），宽度不变、不跳动。 */
@Composable
private fun AddPill(added: Boolean, onAdd: () -> Unit) {
    val shape = RoundedCornerShape(MovoRadius.md)
    val bg by animateColorAsState(
        if (added) MovoColors.bgSurfaceMuted.copy(alpha = 0.5f) else MovoColors.bgSurfaceMuted,
        MovoMotion.fast(),
        label = "addPillBg",
    )
    val fg by animateColorAsState(
        if (added) MovoColors.textTertiary else MovoColors.textPrimary,
        MovoMotion.fast(),
        label = "addPillFg",
    )
    Box(
        modifier = Modifier
            .height(MovoSize.controlSmall)
            .widthIn(min = 64.dp)
            .movoClickable(PressKind.Solid, shape = shape, enabled = !added, onClick = onAdd)
            .clip(shape)
            .background(bg)
            .padding(horizontal = MovoSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(if (added) R.string.permission_add_added else R.string.permission_add_action),
            style = MovoTypography.labelMedium,
            color = fg,
            maxLines = 1,
        )
    }
}

/** 应用图标 28、圆角 8；后台加载，加载完淡入（位置和大小不变）。 */
@Composable
private fun AppIcon(packageName: String, size: Dp = APP_ICON) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val icon by produceState(AppIconCache.get(packageName), packageName) {
        if (value == null) value = withContext(Dispatchers.IO) { AppIconCache.load(context, packageName, px) }
    }
    val alpha by animateFloatAsState(if (icon != null) 1f else 0f, MovoMotion.fast(), label = "appIcon")
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(MovoRadius.xs))
            .background(MovoColors.bgSurfaceMuted),
    ) {
        icon?.let {
            Image(it, contentDescription = null, modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha })
        }
    }
}

private val APP_ICON = 28.dp
private val APP_TEXT_START = 56.dp

internal data class InstalledApp(val packageName: String, val label: String)

/** 能在桌面打开的应用（不含 Movo 自己），按名称排序。 */
private fun launchableApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val infos = runCatching { pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L)) }.getOrDefault(emptyList())
    val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
    return infos.mapNotNull { info ->
        val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
        if (pkg == context.packageName) return@mapNotNull null
        InstalledApp(pkg, info.loadLabel(pm).toString().trim().ifBlank { pkg })
    }
        .distinctBy { it.packageName }
        .sortedWith { a, b -> collator.compare(a.label, b.label) }
}

/** 包名 → 应用名；卸载了就显示包名。 */
private fun appLabelOf(context: Context, pkg: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

private object AppIconCache {
    private val cache = android.util.LruCache<String, ImageBitmap>(200)

    fun get(pkg: String): ImageBitmap? = cache.get(pkg)

    fun load(context: Context, pkg: String, px: Int): ImageBitmap? = runCatching {
        context.packageManager.getApplicationIcon(pkg).toBitmap(px, px).asImageBitmap()
    }.getOrNull()?.also { cache.put(pkg, it) }
}
