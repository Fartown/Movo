package io.github.fartown.movo.ui.pages.providers

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import io.github.fartown.movo.R
import io.github.fartown.movo.ui.components.movo.CardFooter
import io.github.fartown.movo.ui.components.movo.MovoDivider
import io.github.fartown.movo.ui.components.movo.MovoIconButton
import io.github.fartown.movo.ui.components.movo.RowLeading
import io.github.fartown.movo.ui.components.movo.RowTrailing
import io.github.fartown.movo.ui.components.movo.SettingsRow
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

internal fun LazyListScope.providerHeadersEditor(
    headers: List<ProviderHeaderDraft>,
    onHeadersChange: (List<ProviderHeaderDraft>) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    // 请求头数量很少且必须收进同一张卡片，折叠/展开态整组重排，不拆成独立 Lazy 条目。
    item(key = "custom_headers") {
        ProviderSection(title = stringResource(R.string.movo_provider_headers_title)) {
            val chevronRotation by animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                animationSpec = MovoMotion.fast(),
                label = "headersChevron",
            )
            val expandLabel = stringResource(if (expanded) R.string.movo_provider_headers_collapse else R.string.movo_provider_headers_expand)
            SettingsRow(
                title = stringResource(R.string.movo_provider_headers_row),
                trailing = RowTrailing.Custom {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (headers.isEmpty()) {
                                stringResource(R.string.movo_provider_headers_none)
                            } else {
                                stringResource(R.string.movo_provider_headers_count, headers.size)
                            },
                            style = MovoTypography.labelRegular,
                            color = MovoColors.textSecondary,
                        )
                        MovoIcon(
                            MovoIcons.ChevronDown,
                            contentDescription = expandLabel,
                            size = MovoSize.iconSmall,
                            tint = MovoColors.textTertiary,
                            modifier = Modifier.padding(start = MovoSpacing.xs).rotate(chevronRotation),
                        )
                    }
                },
                onClick = { onExpandedChange(!expanded) },
                showDivider = expanded,
            )
            if (expanded) {
                headers.forEach { row ->
                    ProviderHeaderRow(
                        row = row,
                        onNameChange = { value ->
                            onHeadersChange(headers.map {
                                if (it.id == row.id) it.copy(header = it.header.copy(name = value)) else it
                            })
                        },
                        onValueChange = { value ->
                            onHeadersChange(headers.map {
                                if (it.id == row.id) it.copy(header = it.header.copy(value = value)) else it
                            })
                        },
                        onRemove = { onHeadersChange(headers.filterNot { it.id == row.id }) },
                    )
                    MovoDivider(start = MovoSpacing.lg)
                }
                SettingsRow(
                    title = stringResource(R.string.movo_provider_headers_add),
                    leading = RowLeading.Icon(MovoIcons.Plus),
                    trailing = RowTrailing.None,
                    showDivider = false,
                    onClick = { onHeadersChange(headers + ProviderHeaderDraft()) },
                )
            }
            CardFooter(
                listOf(
                    stringResource(R.string.movo_provider_headers_footer_1),
                    stringResource(R.string.movo_provider_headers_footer_2),
                ),
            )
        }
    }
}

@Composable
private fun ProviderHeaderRow(
    row: ProviderHeaderDraft,
    onNameChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    var visible by remember(row.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier.padding(start = MovoSpacing.lg, top = MovoSpacing.md, bottom = MovoSpacing.md, end = MovoSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MovoSpacing.sm),
        ) {
            TextField(
                value = row.header.name,
                onValueChange = onNameChange,
                label = stringResource(R.string.movo_provider_header_name),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = row.header.value,
                onValueChange = onValueChange,
                label = stringResource(R.string.movo_provider_header_value),
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    // Lucide 没有 eye-off 的生成数据，显隐切换暂用 Material 图标（见 restyle-C.md）。
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            imageVector = if (visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                            contentDescription = if (visible) {
                                context.getString(R.string.page_hide_bb0e7e)
                            } else {
                                context.getString(R.string.page_show_71b677)
                            },
                            tint = MovoColors.textSecondary,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        MovoIconButton(
            icon = MovoIcons.Trash2,
            contentDescription = context.getString(R.string.ui_delete_3755f5),
            onClick = onRemove,
            iconSize = MovoSize.iconMedium,
            tint = MovoColors.textSecondary,
        )
    }
}
