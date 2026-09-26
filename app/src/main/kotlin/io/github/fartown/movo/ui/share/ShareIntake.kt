package io.github.fartown.movo.ui.share

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.fartown.movo.agent.device.AgentFileReferenceGateway
import io.github.fartown.movo.agent.device.BoundedFileCopy
import io.github.fartown.movo.agent.media.MAX_AGENT_IMAGE_BYTES
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * 分享到 Movo（规范 8.9.1 `Overlay/ShareIn`）：从系统分享面板收到的内容。
 * 图片和文件在接收时就复制进 App 私有目录（来源 App 给的读取授权只在接收那一刻有效），这里只保存本地路径。
 */
internal data class SharedContent(
    val text: String,
    val imagePaths: List<String>,
    val filePaths: List<String>,
    val sourceLabel: String?,
    val skippedCount: Int,
    val skipReason: SkipReason?,
) {
    val isEmpty: Boolean get() = text.isBlank() && imagePaths.isEmpty() && filePaths.isEmpty() && skippedCount == 0

    fun toExtras(intent: Intent): Intent = intent
        .putExtra(EXTRA_TEXT, text)
        .putStringArrayListExtra(EXTRA_IMAGES, ArrayList(imagePaths))
        .putStringArrayListExtra(EXTRA_FILES, ArrayList(filePaths))
        .putExtra(EXTRA_SOURCE, sourceLabel)
        .putExtra(EXTRA_SKIPPED, skippedCount)
        .putExtra(EXTRA_SKIP_REASON, skipReason?.name)

    companion object {
        private const val EXTRA_TEXT = "io.github.fartown.movo.share.TEXT"
        private const val EXTRA_IMAGES = "io.github.fartown.movo.share.IMAGES"
        private const val EXTRA_FILES = "io.github.fartown.movo.share.FILES"
        private const val EXTRA_SOURCE = "io.github.fartown.movo.share.SOURCE"
        private const val EXTRA_SKIPPED = "io.github.fartown.movo.share.SKIPPED"
        private const val EXTRA_SKIP_REASON = "io.github.fartown.movo.share.SKIP_REASON"

        fun fromExtras(intent: Intent): SharedContent = SharedContent(
            text = intent.getStringExtra(EXTRA_TEXT).orEmpty(),
            imagePaths = intent.getStringArrayListExtra(EXTRA_IMAGES).orEmpty(),
            filePaths = intent.getStringArrayListExtra(EXTRA_FILES).orEmpty(),
            sourceLabel = intent.getStringExtra(EXTRA_SOURCE),
            skippedCount = intent.getIntExtra(EXTRA_SKIPPED, 0),
            skipReason = intent.getStringExtra(EXTRA_SKIP_REASON)?.let { name -> SkipReason.entries.firstOrNull { it.name == name } },
        )
    }
}

/** 没能加入的原因；多项被跳过时只说最主要的一条。 */
internal enum class SkipReason { TooLarge, TooManyImages, Unreadable }

/** 浮层内容区的来源提示与快捷建议（规范 8.9.1），只对应分享新开的那条会话。 */
internal data class ShareIntro(
    val sourceLabel: String?,
    val prefilledText: String,
    val kind: Kind,
    val skippedCount: Int,
    val skipReason: SkipReason?,
) {
    enum class Kind { Text, Images, Files }

    companion object {
        fun of(content: SharedContent): ShareIntro = ShareIntro(
            sourceLabel = content.sourceLabel,
            prefilledText = content.text,
            kind = when {
                content.imagePaths.isNotEmpty() -> Kind.Images
                content.filePaths.isNotEmpty() -> Kind.Files
                else -> Kind.Text
            },
            skippedCount = content.skippedCount,
            skipReason = content.skipReason,
        )
    }
}

/** 快捷建议发送的内容：芯片文字是指令，已预填的文字跟在后面（附件本来就在输入框里）。 */
internal fun shareChipPrompt(chip: String, prefilledText: String): String =
    if (prefilledText.isBlank()) chip else "$chip\n\n${prefilledText.trim()}"

/** 当前待展示的分享提示；新建或切换会话、分享的会话发出第一条消息后清掉。 */
internal object ShareIntake {
    var intro by mutableStateOf<ShareIntro?>(null)

    fun clear() {
        intro = null
    }
}

/** 解析 ACTION_SEND / ACTION_SEND_MULTIPLE。 */
internal object ShareIntentParser {
    fun text(intent: Intent): String {
        val body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        // 浏览器分享链接时标题在 SUBJECT、链接在 TEXT：合成「标题 链接」；标题已包含在正文里就不重复。
        return when {
            subject.isEmpty() -> body
            body.isEmpty() -> subject
            body.contains(subject) -> body
            else -> "$subject $body"
        }
    }

    fun streams(intent: Intent): List<Uri> {
        val uris = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> intent.parcelable<Uri>(Intent.EXTRA_STREAM)?.let(uris::add)
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableList<Uri>(Intent.EXTRA_STREAM)?.let(uris::addAll)
        }
        intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
        // 只收 content://：file:// 可能指向 Movo 自己的私有文件，不接受。
        return uris.filter { it.scheme == ContentResolver.SCHEME_CONTENT }
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> Intent.parcelable(name: String): T? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(name, T::class.java) else getParcelableExtra(name)

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> Intent.parcelableList(name: String): List<T>? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(name, T::class.java) else getParcelableArrayListExtra(name)
}

/** 把分享来的 Uri 复制进 App 私有目录：图片进缓存（随后转成对话图片），其他文件进工作区 imports。 */
internal class ShareImporter(private val context: Context) {
    private val resolver = context.contentResolver

    fun import(intent: Intent, sourceLabel: String?): SharedContent {
        val images = ArrayList<String>()
        val files = ArrayList<String>()
        val skipped = HashMap<SkipReason, Int>()
        val fallbackType = intent.type
        for (uri in ShareIntentParser.streams(intent)) {
            val type = runCatching { resolver.getType(uri) }.getOrNull() ?: fallbackType
            if (type?.startsWith("image/") == true) {
                if (images.size >= MAX_SHARED_IMAGES) {
                    skipped.merge(SkipReason.TooManyImages, 1, Int::plus)
                    continue
                }
                when (val result = copyImage(uri, type)) {
                    is Copy.Done -> images += result.path
                    is Copy.Skipped -> skipped.merge(result.reason, 1, Int::plus)
                }
            } else {
                when (val result = AgentFileReferenceGateway.importDocumentUri(context, uri)) {
                    is AgentFileReferenceGateway.Resolution.Success -> files += result.reference.absolutePath
                    is AgentFileReferenceGateway.Resolution.Failure -> skipped.merge(
                        if (result.error == AgentFileReferenceGateway.Error.ImportTooLarge) SkipReason.TooLarge else SkipReason.Unreadable,
                        1,
                        Int::plus,
                    )
                }
            }
        }
        return SharedContent(
            text = ShareIntentParser.text(intent),
            imagePaths = images,
            filePaths = files,
            sourceLabel = sourceLabel,
            skippedCount = skipped.values.sum(),
            skipReason = skipped.maxByOrNull { it.value }?.key,
        )
    }

    private sealed interface Copy {
        data class Done(val path: String) : Copy
        data class Skipped(val reason: SkipReason) : Copy
    }

    private fun copyImage(uri: Uri, type: String): Copy {
        val directory = File(context.cacheDir, "shared-images").apply { mkdirs() }
        val extension = type.substringAfter('/', "").takeIf { it.matches(Regex("[a-z0-9.+-]{1,10}")) } ?: "img"
        val target = File(directory, "${UUID.randomUUID()}.$extension")
        return try {
            val input = resolver.openInputStream(uri) ?: return Copy.Skipped(SkipReason.Unreadable)
            input.use { source -> target.outputStream().use { BoundedFileCopy.copy(source, it, MAX_AGENT_IMAGE_BYTES.toLong()) } }
            Copy.Done(target.absolutePath)
        } catch (_: BoundedFileCopy.TooLargeException) {
            target.delete()
            Copy.Skipped(SkipReason.TooLarge)
        } catch (_: IOException) {
            target.delete()
            Copy.Skipped(SkipReason.Unreadable)
        } catch (_: SecurityException) {
            target.delete()
            Copy.Skipped(SkipReason.Unreadable)
        } catch (_: RuntimeException) {
            target.delete()
            Copy.Skipped(SkipReason.Unreadable)
        }
    }

    companion object {
        /** 与一次发送的图片上限一致（`AgentRuntimeImageTransfer`）。 */
        const val MAX_SHARED_IMAGES = 8
    }
}

/** 分享来源 App 的名称；取不到（或是 Movo 自己）时为 null。 */
internal fun shareSourceLabel(context: Context, referrer: Uri?): String? {
    val packageName = referrer?.takeIf { it.scheme == "android-app" }?.host ?: return null
    if (packageName == context.packageName) return null
    return runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
