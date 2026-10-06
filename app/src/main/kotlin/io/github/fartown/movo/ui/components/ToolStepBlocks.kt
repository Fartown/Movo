package io.github.fartown.movo.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.media.ToolStepImages
import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.ui.components.movo.PressKind
import io.github.fartown.movo.ui.components.movo.movoClickable
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text

/**
 * `Run/StepDetail` 里的展开块（定稿 20，规范 8.4）：截图、列表、输出、键值、前后对比、内容预览，块间距 10。
 * 只负责画块，外面的灰底块与展开动画由执行卡的步骤行负责。
 */
@Composable
internal fun ToolStepBlocks(view: ToolUiView, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        view.blocks.forEach { block ->
            when (block) {
                is ToolUiBlock.Images -> block.keys.forEach { ToolStepImage(it) }
                is ToolUiBlock.Items -> ItemsBlock(block)
                is ToolUiBlock.Output -> OutputBlock(block)
                is ToolUiBlock.Fields -> FieldsBlock(block)
                is ToolUiBlock.Change -> ChangeBlock(block)
                is ToolUiBlock.Preview -> PreviewBlock(block)
            }
        }
    }
}

/** 截图只在本次运行中显示：缓存里没有了（重启、被挤掉）就显示占位。 */
@Composable
internal fun ToolStepImageGone(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MovoColors.bgSurface)
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Text(stringResource(R.string.tool_step_image_gone), style = MovoTypography.numericMicro, color = MovoColors.textTertiary)
    }
}

@Composable
private fun ToolStepImage(key: String) {
    val bitmap by produceState<ImageBitmap?>(null, key) {
        value = withContext(Dispatchers.IO) {
            ToolStepImages.get(key)?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        }
    }
    var gone by remember(key) { mutableStateOf(ToolStepImages.get(key) == null) }
    if (gone) {
        ToolStepImageGone()
        return
    }
    val image = bitmap ?: return
    var viewing by rememberSaveable(key) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val shape = RoundedCornerShape(6.dp)
        Image(
            bitmap = image,
            contentDescription = stringResource(R.string.tool_step_open_image),
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .width(120.dp)
                .clip(shape)
                .border(MovoSize.hairline, MovoColors.borderHairline, shape)
                .movoClickable(PressKind.Solid, shape = shape) { viewing = true },
        )
        Text(stringResource(R.string.tool_step_open_image), style = MovoTypography.numericMicro, color = MovoColors.textTertiary)
    }
    if (viewing) {
        Dialog(
            onDismissRequest = { viewing = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = stringResource(R.string.tool_step_close_image),
                    ) { viewing = false },
                contentAlignment = Alignment.Center,
            ) {
                Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun BlockLabel(text: String) {
    Text(text, style = MovoTypography.microMedium.copy(letterSpacing = 0.sp), color = MovoColors.textSecondary)
}

@Composable
private fun MoreLine(text: String) {
    Text(text, style = MovoTypography.numericMicro, color = MovoColors.textTertiary)
}

@Composable
private fun ItemsBlock(block: ToolUiBlock.Items) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        block.items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.title, style = MovoTypography.labelRegular, color = MovoColors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    item.subtitle?.let {
                        Text(it, style = MovoTypography.numericMicro, color = MovoColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                item.trailing?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MovoTypography.numericMicro, color = MovoColors.textTertiary, maxLines = 1)
                }
            }
        }
        if (block.more > 0) MoreLine(stringResource(R.string.tool_step_more_items, block.more))
    }
}

/** 输出：等宽 12/17 白底块，长行折行（定稿 20 · 10），默认 8 行，更多点「展开全部」。 */
@Composable
private fun OutputBlock(block: ToolUiBlock.Output) {
    val lines = remember(block.text) { block.text.lines() }
    var all by rememberSaveable(block.text) { mutableStateOf(false) }
    val shown = if (all || lines.size <= OUTPUT_FOLDED_LINES) block.text else lines.take(OUTPUT_FOLDED_LINES).joinToString("\n")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.label?.let { BlockLabel(it) }
        SelectionContainer {
            Text(
                text = shown,
                style = MovoTypography.numericMicro.copy(fontFamily = FontFamily.Monospace, lineHeight = 17.sp),
                color = MovoColors.textPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MovoColors.bgSurface)
                    .padding(10.dp),
            )
        }
        when {
            !all && lines.size > OUTPUT_FOLDED_LINES -> Text(
                stringResource(R.string.tool_step_show_all, lines.size + block.more),
                style = MovoTypography.microMedium.copy(letterSpacing = 0.sp),
                color = MovoColors.indigoFg,
                modifier = Modifier.movoClickable(PressKind.Solid) { all = true },
            )
            block.more > 0 -> MoreLine(stringResource(R.string.tool_step_more_lines, block.more))
        }
    }
}

@Composable
private fun FieldsBlock(block: ToolUiBlock.Fields) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.rows.forEach { row ->
            Row {
                Text(row.label, style = MovoTypography.labelRegular, color = MovoColors.textSecondary, modifier = Modifier.width(64.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(12.dp))
                Text(row.value, style = MovoTypography.labelRegular, color = MovoColors.textPrimary, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ChangeBlock(block: ToolUiBlock.Change) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.label?.let { BlockLabel(it) }
        ChangeRow(stringResource(R.string.tool_step_before), block.before, before = true)
        ChangeRow(stringResource(R.string.tool_step_after), block.after, before = false)
    }
}

@Composable
private fun ChangeRow(label: String, value: String?, before: Boolean) {
    Row {
        Text(label, style = MovoTypography.labelRegular, color = MovoColors.textSecondary, modifier = Modifier.width(40.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text = value ?: "—",
            style = MovoTypography.labelRegular.copy(textDecoration = if (before && value != null) TextDecoration.LineThrough else null),
            color = if (before) MovoColors.textTertiary else MovoColors.textPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PreviewBlock(block: ToolUiBlock.Preview) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.label?.let { BlockLabel(it) }
        SelectionContainer {
            Text(block.text, style = MovoTypography.labelRegular, color = MovoColors.textPrimary, maxLines = PREVIEW_MAX_LINES, overflow = TextOverflow.Ellipsis)
        }
        if (block.more) MoreLine(stringResource(R.string.tool_step_more_content))
    }
}

private const val OUTPUT_FOLDED_LINES = 8
private const val PREVIEW_MAX_LINES = 12
