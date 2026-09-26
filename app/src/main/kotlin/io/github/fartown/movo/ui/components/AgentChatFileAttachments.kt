package io.github.fartown.movo.ui.components

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.model.AgentFileReference
import io.github.fartown.movo.agent.model.AgentFileReferenceKind
import io.github.fartown.movo.ui.components.movo.MovoConfirmDialog
import io.github.fartown.movo.ui.components.movo.MovoPopover
import io.github.fartown.movo.ui.components.movo.MovoPopoverItem
import io.github.fartown.movo.ui.components.movo.TextField
import io.github.fartown.movo.ui.model.PendingFileReferenceUi
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoRadius
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val ChatInputPopupMargin = 8.dp
internal val ChatInputActionSize = 40.dp
internal val ChatInputActionIconSize = 24.dp

@Composable
internal fun AgentAttachmentPickerButton(
    popupAnchorTopPx: Int,
    popupMaxHeight: Dp,
    onAttachImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showPopup by remember { mutableStateOf(false) }
    var showPathDialog by remember { mutableStateOf(false) }
    var pathInput by remember { mutableStateOf("") }
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onAttachImage(uri.toString())
        }
    }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) onAttachFiles(uris.map { it.toString() })
    }
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) onAttachFolder(uri.toString())
    }

    Box(modifier = modifier) {
        // 附件：40 浅底圆 + 20「+」（规范 8「圆形按钮」）。
        io.github.fartown.movo.ui.components.movo.MovoCircleButton(
            icon = MovoIcons.Plus,
            contentDescription = stringResource(R.string.ui_add_attachment_dba9e8),
            onClick = { showPopup = true },
        )
        // 附件菜单（`Popover/Menu`，出现在输入框上方）：点一项即关闭菜单并打开对应的选择器（规范 9.3.1「松手即执行」）。
        MovoPopover(
            show = showPopup && popupAnchorTopPx > 0,
            onDismiss = { showPopup = false },
            aboveYPx = popupAnchorTopPx,
            maxHeight = popupMaxHeight,
        ) {
            MovoPopoverItem(
                label = stringResource(R.string.attachment_image),
                icon = MovoIcons.Image,
                onClick = {
                    showPopup = false
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
            MovoPopoverItem(
                label = stringResource(R.string.attachment_file),
                icon = MovoIcons.File,
                onClick = {
                    showPopup = false
                    filePicker.launch(arrayOf("*/*"))
                },
            )
            MovoPopoverItem(
                label = stringResource(R.string.attachment_folder),
                icon = MovoIcons.Folder,
                onClick = {
                    showPopup = false
                    folderPicker.launch(null)
                },
            )
            MovoPopoverItem(
                label = stringResource(R.string.attachment_enter_path),
                icon = MovoIcons.Terminal,
                onClick = {
                    showPopup = false
                    pathInput = ""
                    showPathDialog = true
                },
            )
        }
    }

    // 输入文件路径（`Dialog/Confirm`，规范 8.11）：说明写在正文，下面是路径输入框；「添加」为普通确认（主操作色）。
    MovoConfirmDialog(
        show = showPathDialog,
        title = stringResource(R.string.ui_input_file_path_36d474),
        message = stringResource(R.string.ui_supports_files_and_folders_under_internal_storage_or_520786),
        confirmText = stringResource(R.string.attachment_add),
        confirmEnabled = pathInput.trim().startsWith('/'),
        onConfirm = {
            val path = pathInput.trim()
            showPathDialog = false
            onAttachFilePath(path)
        },
        onDismissRequest = { showPathDialog = false },
        extraContent = {
            Spacer(Modifier.height(MovoSpacing.lg))
            TextField(
                value = pathInput,
                onValueChange = { pathInput = it },
                label = stringResource(R.string.ui_absolute_path_9ac6fc),
                useLabelAsPlaceholder = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

/**
 * 待发送的文件胶囊条（规范 8.9.1：分享进来的文件与「+」添加的相同）：胶囊高 40、圆角 20，`bg/surface` + 0.5 发丝描边；
 * 文件 / 文件夹图标 16 用 Graphite（开发者与底层：文件）；名字 `Label/Medium`；删除按钮视觉 20、热区 44；无水波纹。
 */
@Composable
internal fun PendingFileReferenceStrip(
    references: List<PendingFileReferenceUi>,
    onRemoveReference: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chipShape = RoundedCornerShape(MovoRadius.lg)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(MovoSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        references.forEach { pending ->
            val reference = pending.reference
            Row(
                modifier = Modifier
                    .height(MovoSize.controlMedium)
                    .widthIn(max = 250.dp)
                    // 不裁切：删除按钮的 44 热区要能溢出胶囊。
                    .background(MovoColors.bgSurface, chipShape)
                    .border(MovoSize.hairline, MovoColors.borderHairline, chipShape)
                    // 右侧 10 让 20 删除圆与 40 高胶囊的端头同心（20 = 10 + 10）。
                    .padding(start = MovoSpacing.md, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MovoIcon(
                    if (reference.kind == AgentFileReferenceKind.Directory) MovoIcons.Folder else MovoIcons.File,
                    null,
                    size = MovoSize.iconSmall,
                    tint = MovoColors.graphiteFg,
                )
                Spacer(Modifier.width(MovoSpacing.sm))
                Text(
                    text = reference.displayName +
                        if (reference.kind == AgentFileReferenceKind.Directory) "/" else "",
                    style = MovoTypography.labelMedium,
                    color = MovoColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(MovoSpacing.sm))
                PendingRemoveButton(
                    contentDescription = stringResource(R.string.ui_remove_file_reference_04bbfc),
                    onClick = { onRemoveReference(pending.id) },
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SentFileReferenceFlow(
    references: List<AgentFileReference>,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        references.forEach { reference ->
            Row(
                modifier = Modifier
                    .height(38.dp)
                    .widthIn(max = 280.dp)
                    .squircleSurface(
                        color = MiuixTheme.colorScheme.surface,
                        cornerRadius = 12.dp,
                    )
                    .squircleBorder(
                        width = 0.5.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.45f),
                        cornerRadius = 12.dp,
                    )
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (reference.kind == AgentFileReferenceKind.Directory) {
                        Icons.Rounded.FolderOpen
                    } else {
                        Icons.Rounded.Description
                    },
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = reference.displayName +
                        if (reference.kind == AgentFileReferenceKind.Directory) "/" else "",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
