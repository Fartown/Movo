package io.github.fartown.movo.ui.app

import androidx.compose.runtime.mutableIntStateOf
import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.fartown.movo.agent.roleplay.CharacterCard
import io.github.fartown.movo.agent.roleplay.CharacterCardCodec
import io.github.fartown.movo.agent.roleplay.CharacterCardFormat
import io.github.fartown.movo.agent.roleplay.CharacterCardException
import io.github.fartown.movo.agent.roleplay.CharacterCardCompatibility
import io.github.fartown.movo.agent.roleplay.CharacterBookDraft
import io.github.fartown.movo.agent.roleplay.CharacterProfile
import io.github.fartown.movo.agent.roleplay.RoleplayBinding
import io.github.fartown.movo.agent.roleplay.UserPersona
import io.github.fartown.movo.core.AndroidAgentLogger
import io.github.fartown.movo.data.repository.AgentMemorySnapshot
import io.github.fartown.movo.data.repository.AgentMemoryWriteResult
import io.github.fartown.movo.data.repository.CharacterMemoryRepository
import io.github.fartown.movo.data.repository.CharacterRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 需要用户知情的失败（规范 8.11，界面用 `Dialog/Info`）：[title] 写哪件事没成，[message] 写原因与下一步。
 * 成功不再弹窗，由页面就地反馈（保存按钮 ✓、设置行右侧值、列表行增删）。
 */
internal data class CharacterNotice(val title: String, val message: String)

/** One retained editing session per character; null is the new-character session. */
internal data class CharacterEditorSession(
    val original: CharacterProfile?,
    val card: CharacterCard,
    val name: String,
)

internal class CharacterEditorDrafts {
    private val sessions = mutableMapOf<String?, CharacterEditorSession>()

    fun remember(id: String?, session: CharacterEditorSession) {
        sessions[id] = session
    }

    fun find(id: String?): CharacterEditorSession? = sessions[id]

    fun discard(id: String?) {
        sessions.remove(id)
    }
}

internal class CharacterLibraryViewModel(application: Application) : AndroidViewModel(application) {
    val store = CharacterLibraryStore(application, viewModelScope)
}

/** 角色页面才加载资料；配置变更保留编辑草稿，文件操作均在后台完成。 */
internal class CharacterLibraryStore(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var characters by mutableStateOf<List<CharacterProfile>>(emptyList())
        private set
    var query by mutableStateOf("")
    var selected by mutableStateOf<CharacterProfile?>(null)
        private set
    var compatibilityWarnings by mutableStateOf<List<String>>(emptyList())
        private set
    var draft by mutableStateOf<CharacterCard?>(null)
        private set
    var draftName by mutableStateOf("")
        private set
    var persona by mutableStateOf(UserPersona())
        private set
    var personaDraft by mutableStateOf(UserPersona())
        private set
    var usePersona by mutableStateOf(true)
    var greetingIndex by mutableStateOf(0)
    var memoryDraft by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<CharacterNotice?>(null)
        private set
    /** 编辑页名称为空时的校验错误，显示在名称输入框下方。 */
    var nameError by mutableStateOf<String?>(null)
        private set
    /** 最近一次导出成功的格式与序号（页面据此在对应行就地显示「已导出」）。 */
    var exportedFormat by mutableStateOf<CharacterCardFormat?>(null)
        private set
    var exportedToken by mutableIntStateOf(0)
        private set
    /** 剧情记忆保存成功的序号（保存按钮据此原地换成 ✓）。 */
    var memorySavedToken by mutableIntStateOf(0)
        private set
    private var operation: Job? = null
    private var pendingLoad: (() -> Unit)? = null
    private var editorKey: String? = null
    private var editorLoaded = false
    private var editorOriginal: CharacterProfile? = null
    private val editorDrafts = CharacterEditorDrafts()
    private var personaLoaded = false
    private var memoryCharacterId: String? = null
    private var memorySnapshot: AgentMemorySnapshot? = null

    val filteredCharacters: List<CharacterProfile>
        get() {
            val term = query.trim()
            return characters.filter {
                term.isEmpty() ||
                    it.card.name.contains(term, ignoreCase = true) ||
                    it.card.tags.any { tag -> tag.contains(term, ignoreCase = true) }
            }
        }

    fun loadLibrary() = runOperation("角色库读取失败", "请稍后重试。", queueIfBusy = true) {
        io { CharacterRepository.ensureDefaultCharacter() }
        characters = io { CharacterRepository.list() }
    }

    fun dismissNotice() { notice = null }

    fun loadDetail(id: String) = runOperation("角色读取失败", "请返回角色库重试。", queueIfBusy = true) {
        val profile = io { CharacterRepository.get(id) } ?: error("CHARACTER_NOT_FOUND")
        val warnings = io { CharacterCardCompatibility.warnings(profile.card) }
        if (selected?.id != id) greetingIndex = 0
        selected = profile
        compatibilityWarnings = warnings
        persona = io { CharacterRepository.persona() }
    }

    fun loadEditor(id: String?) {
        if (editorLoaded && editorKey == id) return
        // Navigation may pop this screen without calling its toolbar callback (system or swipe back).
        // Keep the current form before opening another character's editor.
        if (editorLoaded) {
            val currentCard = draft
            if (currentCard != null) {
                editorDrafts.remember(editorKey, CharacterEditorSession(editorOriginal, currentCard, draftName))
            }
        }
        editorLoaded = false
        draft = null
        runOperation("角色读取失败", "请稍后重试。", queueIfBusy = true) {
            val retained = editorDrafts.find(id)
            val profile = retained?.original ?: id?.let { io { CharacterRepository.get(it) } ?: error("CHARACTER_NOT_FOUND") }
            val session = retained ?: CharacterEditorSession(
                original = profile,
                card = profile?.card ?: CharacterCardCodec.create("新角色"),
                name = profile?.card?.name.orEmpty(),
            )
            editorOriginal = session.original
            draft = session.card
            draftName = session.name
            nameError = null
            editorKey = id
            editorLoaded = true
        }
    }

    fun discardEditor() {
        editorDrafts.discard(editorKey)
        editorLoaded = false
        editorKey = null
        editorOriginal = null
        draft = null
        draftName = ""
    }

    fun updateDraft(update: (CharacterCard) -> CharacterCard) {
        if (!busy) draft = draft?.let(update)
    }

    fun updateName(name: String) {
        if (busy) return
        draftName = name
        if (name.isNotBlank()) nameError = null
    }

    fun updateWorldbook(update: (CharacterBookDraft) -> CharacterBookDraft) {
        if (busy) return
        try {
            draft = draft?.let { it.withWorldbook(update(it.worldbookDraft())) }
        } catch (_: IllegalArgumentException) {
            notice = CharacterNotice("世界书设置无效", "请检查扫描深度、预算与条目位置。")
        }
    }

    fun importCard(uri: Uri, onImported: (String) -> Unit) = runOperation(
        "角色卡导入失败",
        "请确认文件是完整的 PNG 或 JSON 角色卡，且未超过大小限制。",
        diagnoseCardImport = true,
    ) {
        val profile = io {
            context.contentResolver.openInputStream(uri)?.use { CharacterRepository.import(it) }
                ?: error("CHARACTER_INPUT_UNAVAILABLE")
        }
        selected = profile
        compatibilityWarnings = emptyList()
        characters = io { CharacterRepository.list() }
        onImported(profile.id)
    }

    fun saveEditor(onSaved: (String) -> Unit) {
        val originalDraft = draft ?: return
        if (draftName.isBlank()) {
            // 校验错误放在名称输入框下方（规范 8.11），不弹窗。
            nameError = "请填写角色名称"
            return
        }
        val card = originalDraft.withEdits(name = draftName)
        val original = editorOriginal
        runOperation("角色保存失败", "请稍后重试。") {
            val profile = io {
                if (original == null) CharacterRepository.create(card)
                else CharacterRepository.save(original.copy(card = card))
            }
            selected = profile
            compatibilityWarnings = emptyList()
            discardEditor()
            characters = io { CharacterRepository.list() }
            onSaved(profile.id)
        }
    }

    fun duplicate(id: String, onDuplicated: (String) -> Unit) = runOperation("角色复制失败", "请稍后重试。") {
        val profile = io { CharacterRepository.duplicate(id) }
        characters = io { CharacterRepository.list() }
        onDuplicated(profile.id)
    }

    fun restoreDefaultCharacter() = runOperation("默认角色恢复失败", "请稍后重试。") {
        io { CharacterRepository.createDefaultCharacter() }
        characters = io { CharacterRepository.list() }
    }

    fun delete(id: String, onDeleted: () -> Unit) = runOperation("角色删除失败", "请稍后重试。") {
        io { CharacterRepository.delete(id) }
        if (selected?.id == id) selected = null
        characters = io { CharacterRepository.list() }
        onDeleted()
    }

    fun export(id: String, format: CharacterCardFormat, uri: Uri) = runOperation("角色卡导出失败", "无法写入所选文件，请换个位置再试。") {
        io {
            context.contentResolver.openOutputStream(uri, "wt")?.use {
                CharacterRepository.export(id, format, it)
            } ?: error("CHARACTER_OUTPUT_UNAVAILABLE")
        }
        exportedFormat = format
        exportedToken++
    }

    fun startConversation(id: String, onReady: (RoleplayBinding, String) -> Unit) = runOperation(
        "新对话创建失败",
        "请稍后重试。",
    ) {
        val profile = io { CharacterRepository.get(id) } ?: error("CHARACTER_NOT_FOUND")
        val storedBinding = io { CharacterRepository.binding(id) }
        val binding = if (usePersona) storedBinding else storedBinding.copy(userName = "用户", userDescription = "")
        val greetings = listOf(profile.card.firstMessage) + profile.card.alternateGreetings
        onReady(binding, greetings.getOrElse(greetingIndex) { profile.card.firstMessage })
    }

    fun loadPersona() {
        if (personaLoaded && personaDraft != persona) return
        runOperation("用户人设读取失败", "请稍后重试。", queueIfBusy = true) {
            persona = io { CharacterRepository.persona() }
            personaDraft = persona
            personaLoaded = true
        }
    }

    fun updatePersona(name: String = personaDraft.name, description: String = personaDraft.description) {
        if (!busy) personaDraft = UserPersona(name, description)
    }

    fun savePersona(onSaved: () -> Unit) = runOperation("用户人设保存失败", "请稍后重试。") {
        val normalized = personaDraft.copy(name = personaDraft.name.trim().ifBlank { "用户" })
        io { CharacterRepository.savePersona(normalized) }
        persona = normalized
        personaDraft = normalized
        onSaved()
    }

    fun loadMemory(id: String, force: Boolean = false) {
        if (!force && memoryCharacterId == id && memorySnapshot != null && memoryDraft != memorySnapshot?.content) return
        runOperation("剧情记忆读取失败", "请稍后重试。", queueIfBusy = true) {
            val snapshot = io { CharacterMemoryRepository.snapshot(context, id) }
            memoryCharacterId = id
            memorySnapshot = snapshot
            memoryDraft = snapshot.content
        }
    }

    fun updateMemory(content: String) { if (!busy) memoryDraft = content }

    fun saveMemory(id: String) {
        val original = memorySnapshot ?: return
        val content = memoryDraft
        runOperation("剧情记忆保存失败", "请检查内容长度后重试。") {
            when (val result = io {
                CharacterMemoryRepository.replaceAllIfRevision(context, id, original.revision, content)
            }) {
                is AgentMemoryWriteResult.Success -> {
                    memorySnapshot = result.snapshot
                    memorySavedToken++
                }
                is AgentMemoryWriteResult.Conflict -> {
                    notice = CharacterNotice(
                        "剧情记忆已被对话更新",
                        "当前草稿仍保留，请复制需要的内容后重新载入，再合并保存。",
                    )
                }
            }
        }
    }

    private fun runOperation(
        failureTitle: String,
        failureMessage: String,
        queueIfBusy: Boolean = false,
        diagnoseCardImport: Boolean = false,
        block: suspend () -> Unit,
    ) {
        if (operation?.isActive == true) {
            if (queueIfBusy) pendingLoad = { runOperation(failureTitle, failureMessage, queueIfBusy = true, block = block) }
            return
        }
        operation = scope.launch {
            busy = true
            try {
                io { CharacterRepository.initialize(context) }
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AndroidAgentLogger.warn("CharacterLibrary operation_failed type=${error.javaClass.simpleName}")
                val message = if (diagnoseCardImport) {
                    characterCardImportMessage((error as? CharacterCardException)?.code) ?: failureMessage
                } else failureMessage
                notice = CharacterNotice(failureTitle, message)
            } finally {
                busy = false
                operation = null
                val next = pendingLoad
                pendingLoad = null
                next?.invoke()
            }
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
