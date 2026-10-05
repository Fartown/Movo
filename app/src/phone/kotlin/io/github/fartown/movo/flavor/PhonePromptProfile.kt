package io.github.fartown.movo.flavor

import io.github.fartown.movo.platform.PromptProfile

internal object PhonePromptProfile : PromptProfile {
    override val device: String = "当前 Android 手机"
    override val deviceNoun: String = "手机"
    override val guidance: String = ""
}
