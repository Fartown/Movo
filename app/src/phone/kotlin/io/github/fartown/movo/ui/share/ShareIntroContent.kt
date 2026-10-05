package io.github.fartown.movo.ui.share

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.R
import io.github.fartown.movo.ui.components.SuggestionChip
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Text

/**
 * 分享进来的新会话在内容区底部显示（规范 8.9.1）：来源提示 → 跳过说明（Rose，下 4）→ 快捷建议（上 12，横向滚动）。
 * 用户在输入框里改写了预填文字（自己写了指令）时快捷建议隐藏。
 */
@Composable
internal fun ShareIntroContent(
    intro: ShareIntro,
    composerText: String,
    onChip: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.BottomStart) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(
                text = intro.sourceLabel?.let { stringResource(R.string.share_intro_from_source, it) }
                    ?: stringResource(R.string.share_intro_hint),
                style = MovoTypography.labelRegular,
                color = MovoColors.textSecondary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (intro.skippedCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = pluralStringResource(
                        R.plurals.share_intro_skipped,
                        intro.skippedCount,
                        intro.skippedCount,
                        stringResource(
                            when (intro.skipReason) {
                                SkipReason.TooLarge -> R.string.share_skip_too_large
                                SkipReason.TooManyImages -> R.string.share_skip_too_many_images
                                else -> R.string.share_skip_unreadable
                            },
                        ),
                    ),
                    style = MovoTypography.labelRegular,
                    color = MovoColors.roseFg,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            val userWroteInstruction = composerText.trim() != intro.prefilledText.trim()
            if (!userWroteInstruction) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(PaddingValues(horizontal = 20.dp)),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (label in shareChips(intro.kind)) {
                        SuggestionChip(label) { onChip(shareChipPrompt(label, intro.prefilledText)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun shareChips(kind: ShareIntro.Kind): List<String> = when (kind) {
    ShareIntro.Kind.Text -> listOf(
        stringResource(R.string.share_chip_summarize),
        stringResource(R.string.share_chip_translate),
        stringResource(R.string.share_chip_explain),
    )
    ShareIntro.Kind.Images -> listOf(
        stringResource(R.string.share_chip_describe_image),
        stringResource(R.string.share_chip_extract_text),
    )
    ShareIntro.Kind.Files -> listOf(stringResource(R.string.share_chip_key_points))
}
