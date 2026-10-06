package io.github.fartown.movo.agent.overlay

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.R
import io.github.fartown.movo.ui.components.movo.BlockTone
import io.github.fartown.movo.ui.components.movo.MovoBlockButton
import io.github.fartown.movo.ui.components.movo.MovoButtonRow
import io.github.fartown.movo.ui.components.movo.movoElevation
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoElevation
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoRadius
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Text

/**
 * 定稿「16-04 解锁提示」的悬浮版：锁屏时审批卡 / 提问卡不给出「允许」，改成这张卡。
 * 标题「需要先解锁手机」，说明受保护内容要解锁后才继续；「取消」= 这一步不做（按取消作答），「去解锁」拉起系统解锁界面。
 * 锁屏上窗口只有卡片大小、不压暗不拦截卡片外的触摸：用户仍可直接在锁屏上滑动解锁。
 */
@Composable
internal fun AgentOverlayUnlockPrompt(
    onCancel: () -> Unit,
    onUnlock: () -> Unit,
) {
    val shape = RoundedCornerShape(MovoRadius.xl)
    Column(
        modifier = Modifier
            .padding(horizontal = MovoSpacing.lg)
            .navigationBarsPadding()
            .padding(bottom = MovoSpacing.lg)
            .fillMaxWidth()
            .movoElevation(MovoElevation.Overlay, shape)
            .clip(shape)
            .background(MovoColors.bgSurface)
            .padding(MovoSpacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(MovoRadius.sm))
                    .background(MovoColors.bgSurfaceMuted),
                contentAlignment = Alignment.Center,
            ) {
                MovoIcon(MovoIcons.Lock, null, size = MovoSize.iconSmall, tint = MovoColors.textPrimary)
            }
            Spacer(Modifier.width(MovoSpacing.sm))
            Text(
                stringResource(R.string.overlay_unlock_header),
                style = MovoTypography.labelMedium,
                color = MovoColors.textTertiary,
            )
        }
        Spacer(Modifier.size(MovoSpacing.md))
        Text(
            stringResource(R.string.overlay_unlock_title),
            style = MovoTypography.titleSection,
            color = MovoColors.textPrimary,
        )
        Spacer(Modifier.size(MovoSpacing.sm))
        Text(
            stringResource(R.string.overlay_unlock_message),
            style = MovoTypography.bodyRegular,
            color = MovoColors.textSecondary,
        )
        Spacer(Modifier.size(MovoSpacing.lg))
        MovoButtonRow {
            MovoBlockButton(label = stringResource(R.string.action_cancel), onClick = onCancel, tone = BlockTone.Secondary)
            MovoBlockButton(label = stringResource(R.string.overlay_unlock_action), onClick = onUnlock, tone = BlockTone.Primary)
        }
    }
}

/**
 * 悬浮卡「去解锁」：透明、无界面，只调 [KeyguardManager.requestDismissKeyguard] 弹出系统解锁界面；
 * 成功、取消或出错都立即结束。解锁成功后 Runtime 收到 ACTION_USER_PRESENT 把解锁提示换回原卡；取消时重新显示解锁提示。
 *
 * 需要在 AndroidManifest 登记（透明主题、不进最近任务、独立任务栈），见 [intent]。
 */
internal class OverlayUnlockActivity : Activity() {
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            done()
            return
        }
        // 解锁请求要求本页面显示在锁屏上方；解锁后撤掉，之后再锁屏时不会继续盖在锁屏上。
        setShowWhenLocked(true)
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = done()
            override fun onDismissCancelled() = done()
            override fun onDismissError() = done()
        })
    }

    override fun onDestroy() {
        // 被系统直接销毁（例如息屏）也要放开「解锁进行中」，悬浮卡才会重新出现。
        if (!finished) InteractionCardCoordinator.setUnlockInProgress(false)
        super.onDestroy()
    }

    private fun done() {
        if (finished) return
        finished = true
        InteractionCardCoordinator.setUnlockInProgress(false)
        setShowWhenLocked(false)
        finish()
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, OverlayUnlockActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                )
    }
}
