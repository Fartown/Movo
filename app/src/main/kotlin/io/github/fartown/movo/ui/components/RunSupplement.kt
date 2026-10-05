package io.github.fartown.movo.ui.components

import io.github.fartown.movo.ui.model.AgentChatMessageUi
import io.github.fartown.movo.ui.model.UserMessageUi

/** 运行时补充以 `user-<runId>-supplement-<index>` 的用户消息投影进来（见 AgentRunMessageProjector）。 */
internal fun AgentChatMessageUi.isRunSupplement(): Boolean =
    this is UserMessageUi && id.startsWith("user-") && id.contains("-supplement-")
