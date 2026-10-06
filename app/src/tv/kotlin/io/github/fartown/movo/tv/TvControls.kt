package io.github.fartown.movo.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object TvTokens {
    val canvas = Color(0xFFF4F3EF)
    val surface = Color.White
    val muted = Color(0xFFF0EFEA)
    val text = Color(0xFF151515)
    val secondary = Color(0xFF6B6964)
    val primary = Color(0xFFDCDFFF)
    val ink = Color(0xFF454CD2)
    val selected = Color(0xFFEEF0FF)
}

@Composable internal fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = TvTokens.ink, onPrimary = Color.White, background = TvTokens.canvas,
        surface = TvTokens.surface, onSurface = TvTokens.text, onBackground = TvTokens.text,
    ), content = content)
}

/** Clickable provides DPAD center / enter and accessibility semantics without TV Material animation. */
@Composable internal fun TvButton(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.widthIn(min = 200.dp).heightIn(min = 64.dp)
        .onFocusChanged { focused = it.isFocused }
        .background(if (focused) TvTokens.selected else if (primary) TvTokens.primary else TvTokens.muted, shape)
        .border(3.dp, if (focused) TvTokens.ink else Color.Transparent, shape)
        .clip(shape)
        .clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 24.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 24.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium,
            color = (if (primary) TvTokens.ink else TvTokens.text).copy(alpha = if (enabled) 1f else .45f))
    }
}
