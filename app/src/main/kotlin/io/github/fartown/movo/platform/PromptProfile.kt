package io.github.fartown.movo.platform

/** 系统提示里与设备有关的措辞；其余提示两边共用。 */
internal interface PromptProfile {
    /** 能操作的设备，例如“当前 Android 手机”。 */
    val device: String

    /** 设备的简称，用于“手机中的真实上下文”这类说法。 */
    val deviceNoun: String

    /** 追加在设备说明之后的操作方式与回答风格；没有时为空串。 */
    val guidance: String
}
