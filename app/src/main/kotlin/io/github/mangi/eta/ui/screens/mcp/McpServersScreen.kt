package io.github.mangi.eta.ui.screens.mcp

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.mcp.McpServerManager
import io.github.mangi.eta.agent.mcp.validateMcpEndpoint
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.ui.components.movo.CardFooter
import io.github.mangi.eta.ui.components.movo.CardTitle
import io.github.mangi.eta.ui.components.movo.MovoCard
import io.github.mangi.eta.ui.components.movo.MovoConfirmDialog
import io.github.mangi.eta.ui.components.movo.MovoDivider
import io.github.mangi.eta.ui.components.movo.MovoIconButton
import io.github.mangi.eta.ui.components.movo.MovoListPage
import io.github.mangi.eta.ui.components.movo.MovoPillButton
import io.github.mangi.eta.ui.components.movo.MovoSwitch
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
import io.github.mangi.eta.ui.theme.MovoSize
import io.github.mangi.eta.ui.theme.MovoSpacing
import io.github.mangi.eta.ui.theme.MovoTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

/**
 * MCP 服务器列表（规范 8.7 二级页）：顶栏「+」添加；一张「服务器 · N」卡片列出已配置服务器，点击进入详情。
 * 添加对话框连接并发现工具，成功后结果写在列表卡片页脚（规范 8.11 不用 Toast）。
 */
@Composable
internal fun McpServersScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val servers by McpServerRepository.serversFlow().collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var addedMessage by remember { mutableStateOf<String?>(null) }

    MovoListPage(
        title = stringResource(R.string.route_mcp_servers),
        onBack = onBack,
        actions = {
            MovoIconButton(
                icon = MovoIcons.Plus,
                contentDescription = stringResource(R.string.mcp_add_server),
                onClick = { showAdd = true },
            )
        },
    ) {
        item(key = "servers") {
            MovoCard {
                CardTitle(stringResource(R.string.mcp_configured_servers, servers.size))
                if (servers.isEmpty()) {
                    McpEmptyBlock(
                        title = stringResource(R.string.mcp_empty_title),
                        summary = stringResource(R.string.mcp_empty_summary),
                    ) {
                        MovoPillButton(
                            label = stringResource(R.string.mcp_add_server),
                            onClick = { showAdd = true },
                            icon = MovoIcons.Plus,
                            primary = true,
                        )
                    }
                } else {
                    servers.forEachIndexed { index, server ->
                        SettingsRow(
                            title = server.name,
                            subtitle = stringResource(
                                R.string.mcp_server_row_summary,
                                server.activeTools.size,
                                server.tools.size,
                            ),
                            trailing = RowTrailing.Arrow(),
                            showDivider = index != servers.lastIndex,
                            onClick = { onNavigate(AppRoute.McpServerDetail(server.id)) },
                        )
                    }
                }
                addedMessage?.let { CardFooter(listOf(it)) }
            }
        }
    }

    MovoConfirmDialog(
        show = showAdd,
        title = stringResource(R.string.mcp_add_server),
        message = null,
        confirmText = if (working) {
            stringResource(R.string.mcp_connecting)
        } else {
            stringResource(R.string.mcp_add_server)
        },
        confirmEnabled = !working,
        // 连接中不能关闭（原先外部点击被屏蔽）；取消按钮同时置灰，避免后台连接完成后结果无处显示。
        cancelEnabled = !working,
        onDismissRequest = { if (!working) showAdd = false },
        onConfirm = {
            val normalizedName = name.trim()
            val normalizedUrl = url.trim()
            error = when {
                normalizedName.isBlank() -> resources.getString(R.string.mcp_name_required)
                else -> runCatching { validateMcpEndpoint(normalizedUrl) }
                    .exceptionOrNull()?.message
            }
            if (error == null) scope.launch {
                working = true
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val draft = McpServerSetting(
                            id = "",
                            name = normalizedName,
                            url = normalizedUrl,
                            protocolMode = McpProtocolMode.AUTO,
                            authorizationType = if (token.isBlank()) {
                                McpAuthorizationType.NONE
                            } else {
                                McpAuthorizationType.BEARER
                            },
                        )
                        val discovered = McpServerManager.discover(draft, token)
                        McpServerRepository.add(discovered, token)
                    }
                }
                working = false
                result.onSuccess {
                    showAdd = false
                    name = ""
                    url = ""
                    token = ""
                    error = null
                    addedMessage = resources.getString(R.string.mcp_server_added, it.tools.size)
                }.onFailure {
                    error = it.message ?: resources.getString(R.string.mcp_connection_failed)
                }
            }
        },
        extraContent = {
            Spacer(Modifier.size(MovoSpacing.lg))
            Column(
                verticalArrangement = Arrangement.spacedBy(MovoSpacing.md),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.mcp_server_name),
                    singleLine = true,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = url,
                    onValueChange = { url = it },
                    label = stringResource(R.string.mcp_server_url),
                    singleLine = true,
                    enabled = !working,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = token,
                    onValueChange = { token = it },
                    label = stringResource(R.string.mcp_bearer_optional),
                    singleLine = true,
                    enabled = !working,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(text = it, style = MovoTypography.labelRegular, color = MovoColors.roseFg)
                }
            }
        },
    )
}

/**
 * MCP 服务器详情（规范 8.7 二级页）：顶栏刷新工具（处理中换成加载圈）；「服务器」卡（启用开关、身份认证）；
 * 「工具 · N」卡（逐个开关，可能修改数据的工具开启前确认；刷新结果写在页脚）；删除服务器单独一张卡，删除前确认。
 */
@Composable
internal fun McpServerDetailScreen(
    serverId: String,
    onBack: () -> Unit,
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val servers by McpServerRepository.serversFlow().collectAsState(initial = emptyList())
    val server = servers.firstOrNull { it.id == serverId }
    var working by remember { mutableStateOf(false) }
    var pendingRiskyTool by remember { mutableStateOf<McpToolDefinition?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var refreshMessage by remember { mutableStateOf<String?>(null) }

    MovoListPage(
        title = server?.name ?: stringResource(R.string.route_mcp_server_detail),
        onBack = onBack,
        actions = {
            if (working) {
                Box(modifier = Modifier.size(MovoSize.touchTarget), contentAlignment = Alignment.Center) {
                    McpSpinner()
                }
            } else {
                MovoIconButton(
                    icon = MovoIcons.RotateCw,
                    contentDescription = stringResource(R.string.mcp_refresh_tools),
                    enabled = server != null,
                    onClick = {
                        scope.launch {
                            working = true
                            val result = withContext(Dispatchers.IO) {
                                runCatching { McpServerManager.refresh(serverId) }
                            }
                            working = false
                            refreshMessage = result.fold(
                                onSuccess = { resources.getString(R.string.mcp_refreshed, it.tools.size) },
                                onFailure = { it.message ?: resources.getString(R.string.mcp_refresh_failed) },
                            )
                        }
                    },
                )
            }
        },
    ) {
        if (server == null) {
            item(key = "missing") {
                MovoCard {
                    SettingsRow(
                        title = stringResource(R.string.mcp_server_missing),
                        trailing = RowTrailing.None,
                        showDivider = false,
                    )
                }
            }
            return@MovoListPage
        }
        item(key = "server") {
            MovoCard {
                CardTitle(stringResource(R.string.mcp_server_settings))
                McpRow(
                    title = stringResource(R.string.mcp_enable_server),
                    subtitle = server.url,
                    role = Role.Switch,
                    onClick = {
                        val enabled = !server.enabled
                        scope.launch(Dispatchers.IO) {
                            McpServerRepository.update(server.copy(enabled = enabled))
                        }
                    },
                ) {
                    MovoSwitch(checked = server.enabled, onCheckedChange = null)
                }
                SettingsRow(
                    title = stringResource(R.string.mcp_update_token),
                    subtitle = stringResource(
                        if (server.authorizationType == McpAuthorizationType.BEARER) {
                            R.string.mcp_token_configured
                        } else {
                            R.string.mcp_no_authentication
                        },
                    ),
                    trailing = RowTrailing.Arrow(),
                    showDivider = false,
                    onClick = { showToken = true },
                )
            }
        }
        item(key = "tools") {
            MovoCard {
                CardTitle(stringResource(R.string.mcp_tools_count, server.tools.size))
                if (server.tools.isEmpty()) {
                    McpEmptyBlock(
                        title = stringResource(R.string.mcp_no_tools),
                        summary = stringResource(R.string.mcp_refresh_tools_hint),
                    )
                } else {
                    server.tools.forEachIndexed { index, tool ->
                        val checked = tool.name in server.enabledToolNames
                        McpRow(
                            title = tool.title.ifBlank { tool.name },
                            subtitle = toolSummary(tool),
                            role = Role.Switch,
                            showDivider = index != server.tools.lastIndex,
                            onClick = {
                                val enabled = !checked
                                if (enabled && tool.readOnlyHint != true) {
                                    pendingRiskyTool = tool
                                } else {
                                    scope.launch(Dispatchers.IO) {
                                        McpServerRepository.setToolEnabled(serverId, tool.name, enabled)
                                    }
                                }
                            },
                        ) {
                            MovoSwitch(checked = checked, onCheckedChange = null)
                        }
                    }
                }
                refreshMessage?.let { CardFooter(listOf(it)) }
            }
        }
        item(key = "delete") {
            MovoCard {
                McpRow(
                    title = stringResource(R.string.mcp_delete_server),
                    subtitle = stringResource(R.string.mcp_delete_server_summary),
                    titleColor = MovoColors.roseFg,
                    showDivider = false,
                    onClick = { showDelete = true },
                )
            }
        }
    }

    val riskyTool = rememberLastNonNull(pendingRiskyTool)
    MovoConfirmDialog(
        show = pendingRiskyTool != null,
        title = stringResource(R.string.mcp_enable_risky_tool),
        message = riskyTool?.let {
            stringResource(R.string.mcp_enable_risky_tool_summary, it.name, server?.name.orEmpty())
        },
        confirmText = stringResource(R.string.mcp_enable_tool),
        onDismissRequest = { pendingRiskyTool = null },
        onConfirm = {
            val tool = pendingRiskyTool ?: return@MovoConfirmDialog
            scope.launch(Dispatchers.IO) {
                McpServerRepository.setToolEnabled(serverId, tool.name, true)
            }
            pendingRiskyTool = null
        },
    )

    MovoConfirmDialog(
        show = showToken,
        title = stringResource(R.string.mcp_update_token),
        message = stringResource(R.string.mcp_update_token_summary),
        confirmText = stringResource(R.string.action_save),
        onDismissRequest = { showToken = false },
        onConfirm = {
            server?.let { current ->
                scope.launch(Dispatchers.IO) {
                    McpServerRepository.update(
                        current.copy(
                            authorizationType = if (token.isBlank()) {
                                McpAuthorizationType.NONE
                            } else {
                                McpAuthorizationType.BEARER
                            },
                        ),
                        bearerToken = token,
                    )
                }
                token = ""
                showToken = false
            }
        },
        extraContent = {
            Spacer(Modifier.size(MovoSpacing.lg))
            TextField(
                value = token,
                onValueChange = { token = it },
                label = stringResource(R.string.mcp_bearer_token),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )

    MovoConfirmDialog(
        show = showDelete,
        title = stringResource(R.string.mcp_delete_server),
        message = stringResource(R.string.mcp_delete_confirm, server?.name.orEmpty()),
        confirmText = stringResource(R.string.ui_delete_3755f5),
        destructive = true,
        onDismissRequest = { showDelete = false },
        onConfirm = {
            scope.launch {
                withContext(Dispatchers.IO) { McpServerRepository.delete(serverId) }
                onBack()
            }
        },
    )
}

@Composable
private fun toolSummary(tool: McpToolDefinition): String = when {
    tool.readOnlyHint == true -> tool.description.ifBlank { stringResource(R.string.mcp_read_only_tool) }
    else -> tool.description.ifBlank { stringResource(R.string.mcp_may_modify_data) }
}

/**
 * 二级页行（icon=false，规范 8.7）：与公共 `SettingsRow` 同样式，另支持说明最多两行（工具描述可能很长）
 * 与标题颜色（删除行用 Rose）。开关行整行点击切换。
 */
@Composable
private fun McpRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    role: Role = Role.Button,
    showDivider: Boolean = true,
    titleColor: Color = MovoColors.textPrimary,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .movoClickable(kind = PressKind.Row, role = role, onClick = onClick),
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
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(MovoSpacing.md))
            trailing()
        }
        if (showDivider) MovoDivider(modifier = Modifier.align(Alignment.BottomStart), start = MovoSpacing.lg)
    }
}

/** 卡内空状态：标题 Body/Strong + 说明 Label/Regular 次要色，对齐内容线；可带一个操作。 */
@Composable
private fun McpEmptyBlock(
    title: String,
    summary: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = MovoSpacing.lg, vertical = MovoSpacing.md)) {
        Text(title, style = MovoTypography.bodyStrong, color = MovoColors.textPrimary)
        Text(summary, style = MovoTypography.labelRegular, color = MovoColors.textSecondary)
        if (action != null) {
            Spacer(Modifier.size(MovoSpacing.md))
            action()
        }
    }
}

/** 16 Indigo 加载圈：800ms 一圈 linear；减少动画时为静态完整圆环（规范 9.3、9.8）。 */
@Composable
private fun McpSpinner() {
    if (LocalReducedMotion.current) {
        Canvas(Modifier.size(MovoSize.iconSmall)) {
            drawCircle(color = MovoColors.indigoFg, radius = size.minDimension * 0.375f, style = Stroke(width = 1.5.dp.toPx()))
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "mcpSpinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(MovoMotion.SPINNER_PERIOD, easing = MovoMotion.EasingLinear)),
        label = "mcpSpinnerAngle",
    )
    MovoIcon(
        MovoIcons.LoaderCircle,
        contentDescription = null,
        size = MovoSize.iconSmall,
        tint = MovoColors.indigoFg,
        modifier = Modifier.graphicsLayer { rotationZ = angle },
    )
}

private class LastValue<T : Any> {
    var value: T? = null
}

/** 返回 [value]；为 null 时返回上一次的非空值（给对话框退场动画用）。 */
@Composable
private fun <T : Any> rememberLastNonNull(value: T?): T? {
    val holder = remember { LastValue<T>() }
    if (value != null) holder.value = value
    return value ?: holder.value
}
