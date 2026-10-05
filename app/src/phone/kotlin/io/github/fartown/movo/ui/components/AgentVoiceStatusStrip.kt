package io.github.fartown.movo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.agent.voice.session.VoiceChannel
import io.github.fartown.movo.agent.voice.session.VoiceSessionUiState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 语音状态条：实时字幕、通道状态和两个必须可见的操作。
 *
 * 字幕与输入框草稿是两个独立的东西——用户手打到一半开语音，草稿不会被冲掉。
 * "停止播报"和"切回文字"必须是看得见的按钮，语音命令只是兜底。
 */
@Composable
internal fun AgentVoiceStatusStrip(
    voice: VoiceSessionUiState,
    onStopSpeaking: () -> Unit,
    onEndVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (voice.channel == VoiceChannel.Connecting) colors.outline else colors.primary),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = voice.statusText.ifBlank { "语音对话中" },
                style = MiuixTheme.textStyles.body2,
                color = colors.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (voice.speaking) {
                VoiceStripAction(
                    icon = Icons.Rounded.VolumeOff,
                    label = "停止播报",
                    onClick = onStopSpeaking,
                )
                Spacer(Modifier.width(4.dp))
            }
            VoiceStripAction(
                icon = Icons.Rounded.Keyboard,
                label = "切回文字",
                onClick = onEndVoice,
            )
        }
        if (voice.transcript.isNotBlank()) {
            Text(
                text = voice.transcript,
                style = MiuixTheme.textStyles.body1,
                color = colors.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text(
            text = "也可以直接说：别念了 / 取消任务 / 结束对话",
            style = MiuixTheme.textStyles.footnote2,
            color = colors.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 语音结束或没能开始时的原因，停留几秒后自动消失；读屏会主动播报这一行。 */
@Composable
internal fun AgentVoiceNotice(text: String, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        color = colors.onSurfaceVariantSummary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun VoiceStripAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.secondaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(icon, label, Modifier.size(14.dp), tint = colors.onSecondaryContainer)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MiuixTheme.textStyles.footnote1, color = colors.onSecondaryContainer)
    }
}
