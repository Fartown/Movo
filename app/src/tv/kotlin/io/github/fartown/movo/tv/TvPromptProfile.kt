package io.github.fartown.movo.tv

import io.github.fartown.movo.platform.PromptProfile

internal object TvPromptProfile : PromptProfile {
    override val device: String = "当前 Android 电视"
    override val deviceNoun: String = "电视"
    override val guidance: String =
        "这台电视用遥控器操作，没有触屏：界面靠方向键移动焦点、确认键选择。" +
            "操作其他应用时先观察界面，按节点点击，不按坐标点按或滑动。" +
            "用户多半在用语音交流、离屏幕较远：回答简短、口语化，适合直接朗读。"
}
