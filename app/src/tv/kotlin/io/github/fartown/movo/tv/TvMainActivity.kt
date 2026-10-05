package io.github.fartown.movo.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fartown.movo.R

/**
 * 电视首页。P1 是工程骨架的占位页，用来验证电视包能安装、启动；首页、语音面板、对话与设置
 * 按 Figma 定稿后在 P2 / P4 实现（实施方案 §5.9）。
 */
class TvMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(stringResource(R.string.app_name), color = Color.White, fontSize = 48.sp)
                        Text(stringResource(R.string.tv_home_placeholder), color = Color(0xFFBDC1C6), fontSize = 20.sp)
                    }
                }
            }
        }
    }

    companion object {
        /** 语音助手入口打开会话界面时的 action（P2 起由语音面板处理）。 */
        const val ACTION_ASSISTANT = "io.github.fartown.movo.tv.ASSISTANT"
    }
}
