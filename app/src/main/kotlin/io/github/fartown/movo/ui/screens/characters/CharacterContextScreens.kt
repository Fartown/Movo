package io.github.fartown.movo.ui.screens.characters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.fartown.movo.ui.app.CharacterLibraryStore
import io.github.fartown.movo.ui.components.movo.BlockTone
import io.github.fartown.movo.ui.components.movo.MovoBlockButton
import io.github.fartown.movo.ui.components.movo.MovoListPage
import io.github.fartown.movo.ui.theme.MovoSpacing

/** 我的人设（规范 8.7 二级页）：输入卡（称呼、身份与关系；页脚说明使用方式）→ 整行「保存人设」。 */
@Composable
internal fun CharacterPersonaScreen(store: CharacterLibraryStore, onBack: () -> Unit) {
    MovoListPage(title = "我的人设", onBack = onBack, modifier = Modifier.imePadding()) {
        item(key = "persona") {
            CharacterFieldCard(
                footer = listOf(
                    "开始对话时可以选择是否使用。",
                    "已开始的故事保留当时的人设。",
                ),
            ) {
                CharacterTextField("称呼", store.personaDraft.name, { store.updatePersona(name = it) }, !store.busy, singleLine = true)
                CharacterTextField("身份与关系", store.personaDraft.description, { store.updatePersona(description = it) }, !store.busy, minLines = 6)
            }
        }
        item(key = "save") {
            MovoBlockButton(
                label = "保存人设",
                onClick = { store.savePersona(onBack) },
                enabled = !store.busy,
                tone = BlockTone.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 剧情记忆（规范 8.7 二级页）：输入卡（剧情与关系；页脚说明共享范围）→ 同一行「重新载入」「保存记忆」。 */
@Composable
internal fun CharacterMemoryScreen(id: String, store: CharacterLibraryStore, onBack: () -> Unit) {
    MovoListPage(title = "剧情记忆", onBack = onBack, modifier = Modifier.imePadding()) {
        item(key = "memory") {
            CharacterFieldCard(
                footer = listOf(
                    "记录这个角色的重要经历、关系与约定。",
                    "由此角色的各次对话共享，受记忆总开关控制。",
                ),
            ) {
                CharacterTextField("剧情与关系", store.memoryDraft, store::updateMemory, !store.busy, minLines = 10)
            }
        }
        item(key = "actions") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MovoSpacing.sm)) {
                MovoBlockButton(
                    label = "重新载入",
                    onClick = { store.loadMemory(id, force = true) },
                    enabled = !store.busy,
                    tone = BlockTone.Secondary,
                    modifier = Modifier.weight(1f),
                )
                MovoBlockButton(
                    label = "保存记忆",
                    onClick = { store.saveMemory(id) },
                    enabled = !store.busy,
                    tone = BlockTone.Primary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
