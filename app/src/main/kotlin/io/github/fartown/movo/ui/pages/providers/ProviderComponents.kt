package io.github.fartown.movo.ui.pages.providers

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.data.model.CustomProviderSetting
import io.github.fartown.movo.data.model.ProviderSetting
import io.github.fartown.movo.ui.components.movo.CardTitle
import io.github.fartown.movo.ui.components.movo.MovoCard
import io.github.fartown.movo.ui.components.providerBrandLogoRes as sharedProviderBrandLogoRes
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIconData
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoRadius
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Text

/**
 * Provider 相关页面的分组卡片（规范 8.7）：一组 = 一张 [MovoCard]，分组标题在卡内（`Card/Title`），卡片外不放文字。
 */
@Composable
internal fun ProviderSection(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    MovoCard(modifier = modifier) {
        if (title != null) {
            CardTitle(title)
        }
        content()
    }
}

/** 厂商品牌原色图标：20、圆形裁切 + 0.5 描边（规范 6「品牌 Logo」、8.7「模型行」）。 */
@Composable
internal fun ProviderBrandIcon(
    sourceType: String,
    modifier: Modifier = Modifier,
    size: Dp = MovoSize.iconMedium,
) {
    val logo = providerBrandLogoRes(sourceType) ?: return
    ProviderBrandImage(logo = logo, modifier = modifier, size = size)
}

@Composable
private fun ProviderBrandImage(
    @DrawableRes logo: Int,
    modifier: Modifier = Modifier,
    size: Dp = MovoSize.iconMedium,
) {
    Image(
        painter = painterResource(logo),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(MovoSize.hairline, MovoColors.borderHairline, CircleShape),
    )
}

@DrawableRes
internal fun providerBrandLogoRes(provider: ProviderSetting): Int? =
    sharedProviderBrandLogoRes(provider)

@DrawableRes
internal fun providerBrandLogoRes(sourceType: String): Int? =
    sharedProviderBrandLogoRes(sourceType)

/**
 * 已知厂商使用品牌图标，未知来源继续按协议类型使用通用线条图标。
 * [size] 大于 20（服务商页 28 / 40）时，兜底线条图标放进同尺寸的 `bg/surface-muted` 圆底里，与 Logo 对齐。
 */
@Composable
internal fun ProviderIcon(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
    size: Dp = MovoSize.iconMedium,
) {
    val logo = providerBrandLogoRes(provider)
    if (logo != null) {
        ProviderBrandImage(logo = logo, modifier = modifier, size = size)
        return
    }
    val icon = if (provider is CustomProviderSetting) MovoIcons.Database else MovoIcons.Globe
    if (size <= MovoSize.iconMedium) {
        MovoIcon(
            icon = icon,
            contentDescription = null,
            size = size,
            tint = MovoColors.textPrimary,
            modifier = modifier,
        )
        return
    }
    ProviderIconTile(icon = icon, modifier = modifier, size = size)
}

/** 圆形浅灰底 + 16 线条图标（服务商页没有 Logo 的服务商、「更多服务商与自定义接口」等入口行）。 */
@Composable
internal fun ProviderIconTile(
    icon: MovoIconData,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MovoColors.bgSurfaceMuted),
        contentAlignment = Alignment.Center,
    ) {
        MovoIcon(icon = icon, contentDescription = null, size = MovoSize.iconSmall, tint = MovoColors.textPrimary)
    }
}

internal enum class TagChipTone { Normal, Emphasized }

/** 小标签（圆角 8，Micro 12 Medium）：普通为浅底次要色，强调（「当前」，在整行浅紫的选中行里）为白底 + Indigo 文字。 */
@Composable
internal fun TagChip(
    text: String,
    tone: TagChipTone = TagChipTone.Normal,
) {
    val background: Color
    val foreground: Color
    when (tone) {
        TagChipTone.Normal -> {
            background = MovoColors.bgSurfaceMuted
            foreground = MovoColors.textSecondary
        }
        // 「当前」只出现在当前模型那一行，而那一行整行是浅紫（单选选中态）：用白底才看得出来。
        TagChipTone.Emphasized -> {
            background = MovoColors.bgSurface
            foreground = MovoColors.indigoFg
        }
    }
    Text(
        text = text,
        style = MovoTypography.microMedium,
        color = foreground,
        maxLines = 1,
        modifier = Modifier
            .background(background, RoundedCornerShape(MovoRadius.xs))
            .padding(horizontal = MovoSpacing.sm, vertical = MovoSpacing.xxs),
    )
}

/**
 * 就地结果（规范 8.11「轻提示」「失败」）：失败 = Rose 警示图标 + 主色文字；成功 = Green ✓ + 次要色文字。
 * 颜色不是唯一信号，图标同时区分。
 */
@Composable
internal fun ProviderStatusLine(
    message: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.height(18.dp), contentAlignment = Alignment.Center) {
            MovoIcon(
                icon = if (isError) MovoIcons.CircleAlert else MovoIcons.CircleCheck,
                contentDescription = null,
                size = MovoSize.iconLabel,
                tint = if (isError) MovoColors.roseFg else MovoColors.greenFg,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = message,
            style = MovoTypography.labelRegular,
            color = if (isError) MovoColors.textPrimary else MovoColors.textSecondary,
        )
    }
}
