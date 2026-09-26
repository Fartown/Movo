package io.github.fartown.movo.agent.overlay

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 悬浮球的本地设置（与展开卡的键盘高度缓存同一个 `agent_overlay` 文件）。
 * 「常驻悬浮球」开（默认）：退出 App 后悬浮球保留，没有任务时是待命态，任务结束保持 ✓ / ! 到点开；
 * 关：只在有执行中（含暂停）的任务时显示，任务结束后 ✓ / ! 保留 3 秒再淡出。
 */
internal object OrbPrefs {
    private const val PREFS = "agent_overlay"
    const val KEY_KEEP_ORB = "keep_orb_after_exit"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun keepOrbAfterExit(context: Context): Boolean = prefs(context).getBoolean(KEY_KEEP_ORB, true)

    fun setKeepOrbAfterExit(context: Context, keep: Boolean) {
        prefs(context).edit { putBoolean(KEY_KEEP_ORB, keep) }
    }
}
