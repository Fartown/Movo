package io.github.fartown.movo.ui.theme

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon

/** 24 网格下的 Lucide 线条图标几何；线宽不随图标保存，由显示尺寸决定。 */
internal class MovoIconData(val name: String, val paths: List<String>) {
    private val cache = HashMap<Float, ImageVector>()

    /** 规范第 6 章：尺寸与线宽固定配对，换算到 24 视口里的线宽。 */
    fun vector(size: Dp): ImageVector = synchronized(cache) {
        cache.getOrPut(size.value) {
            val stroke = strokeFor(size) * 24f / size.value
            val builder = ImageVector.Builder(
                name = "movo.$name.${size.value}",
                defaultWidth = size,
                defaultHeight = size,
                viewportWidth = 24f,
                viewportHeight = 24f,
            )
            paths.forEach { d ->
                builder.addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = stroke,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            builder.build()
        }
    }

    companion object {
        fun strokeFor(size: Dp): Float = when {
            size >= 24.dp -> 1.75f
            size >= 22.dp -> 1.7f
            size >= 20.dp -> 1.6f
            size >= 16.dp -> 1.5f
            else -> 1.4f
        }
    }
}

/** 线条图标：颜色由 [tint] 给出，图标本身不带颜色（规范 6：除品牌 Logo 外不用多色图标）。 */
@Composable
internal fun MovoIcon(
    icon: MovoIconData,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = MovoSize.iconMedium,
    tint: Color = MovoColors.textPrimary,
) {
    val vector = remember(icon, size) { icon.vector(size) }
    Icon(
        imageVector = vector,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint,
    )
}
