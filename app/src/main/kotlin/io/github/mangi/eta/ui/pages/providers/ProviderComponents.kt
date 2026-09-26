package io.github.mangi.eta.ui.pages.providers

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
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.ui.components.movo.CardTitle
import io.github.mangi.eta.ui.components.movo.MovoCard
import io.github.mangi.eta.ui.components.providerBrandLogoRes as sharedProviderBrandLogoRes
import io.github.mangi.eta.ui.theme.MovoColors
import io.github.mangi.eta.ui.theme.MovoIcon
import io.github.mangi.eta.ui.theme.MovoIcons
import io.github.mangi.eta.ui.theme.MovoRadius
import io.github.mangi.eta.ui.theme.MovoSize
import io.github.mangi.eta.ui.theme.MovoSpacing
import io.github.mangi.eta.ui.theme.MovoTypography
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
) {
    val logo = providerBrandLogoRes(sourceType) ?: return
    ProviderBrandImage(logo = logo, modifier = modifier)
}

@Composable
private fun ProviderBrandImage(
    @DrawableRes logo: Int,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(logo),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .size(MovoSize.iconMedium)
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

/** 已知厂商使用品牌图标，未知来源继续按协议类型使用通用线条图标。 */
@Composable
internal fun ProviderIcon(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
) {
    val logo = providerBrandLogoRes(provider)
    if (logo != null) {
        ProviderBrandImage(logo = logo, modifier = modifier)
        return
    }
    MovoIcon(
        icon = if (provider is CustomProviderSetting) MovoIcons.Database else MovoIcons.Globe,
        contentDescription = null,
        size = MovoSize.iconMedium,
        tint = MovoColors.textPrimary,
        modifier = modifier,
    )
}

internal enum class TagChipTone { Normal, Emphasized }

/** 小标签（圆角 8，Micro 12 Medium）：普通为浅底次要色，强调（「当前」）为 Indigo 浅底 + Indigo 文字。 */
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
        TagChipTone.Emphasized -> {
            background = MovoColors.indigoBg
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
