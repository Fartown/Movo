package io.github.fartown.movo.agent.tools.file

import io.github.fartown.movo.agent.tools.core.Sensitivity
import org.json.JSONObject
import java.util.Base64

/**
 * 文件领域共享的小工具：路径分区、敏感度判定、Root shell 引用、file_search 句柄编解码。
 *
 * 分区只按路径字符串判断（resolve 阶段拿不到 Context，无法做 canonical 规范化），
 * 因此 App 私有目录只有用相对路径或 `~` 时才算工作区；用绝对的 /data/user/... 路径会被当作工作区外。
 * 这是刻意的保守取舍：宁可多确认，不可漏确认（见定义清单 §29 安全）。
 */
internal object FileSupport {

    /** 路径分区：决定 file_write 的风险等级（见定义清单 §29）。 */
    internal enum class PathZone {
        /** Movo 工作区：相对路径、`~`、Root 工作区 /data/local/tmp/movo、App 私有目录。可逆写。 */
        WORKSPACE,
        /** 共享存储（/storage/emulated/0、/sdcard）：覆盖写算本机可逆；追加无法核实，按工作区外处理。 */
        SHARED,
        /** 工作区与共享存储以外：Root 才能写的系统位置，一律外发确认。 */
        EXTERNAL,
    }

    /** 仅按路径字符串分区。 */
    fun zoneOf(rawPath: String): PathZone {
        val path = rawPath.trim()
        if (path.isEmpty() || path == "~" || path.startsWith("~/") || !path.startsWith("/")) {
            return PathZone.WORKSPACE
        }
        return when {
            path == "/data/local/tmp/movo" || path.startsWith("/data/local/tmp/movo/") -> PathZone.WORKSPACE
            path.startsWith("/storage/emulated/0") || path.startsWith("/sdcard") -> PathZone.SHARED
            else -> PathZone.EXTERNAL
        }
    }

    /** 按路径判定敏感度：相册、私有数据目录、聊天目录等算个人数据。 */
    fun sensitivityOf(rawPath: String): Sensitivity {
        val path = rawPath.lowercase()
        val private = PRIVATE_MARKERS.any { path.contains(it) }
        return if (private) Sensitivity.PRIVATE else Sensitivity.NORMAL
    }

    /** Root shell 单引号转义，复用 AgentImageTools / AgentPersonalDataTools 的既有写法。 */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    // ---- file_search 句柄：不透明 token，file_read 可解回路径 ----
    // TODO(core)：当前只做 base64(JSON{p,s,m})，未做 HMAC 绑定会话 + mtime/size 校验（定义清单 §0.5）。
    private const val HANDLE_PREFIX = "fh1:"

    fun encodeHandle(path: String, sizeBytes: Long, mtimeMillis: Long): String {
        val payload = JSONObject().put("p", path).put("s", sizeBytes).put("m", mtimeMillis).toString()
        return HANDLE_PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(Charsets.UTF_8))
    }

    /** 解析句柄为路径；不是句柄返回 null。 */
    fun decodeHandlePath(handle: String): String? {
        if (!handle.startsWith(HANDLE_PREFIX)) return null
        return runCatching {
            val json = JSONObject(
                String(Base64.getUrlDecoder().decode(handle.removePrefix(HANDLE_PREFIX)), Charsets.UTF_8),
            )
            json.getString("p")
        }.getOrNull()
    }

    private val PRIVATE_MARKERS = listOf(
        "/dcim/", "/pictures/", "/movies/", "/music/", "/recordings/",
        "/data/data/", "/data/user/", "/tencent/", "/wechat", "/mmchat", "/android/media/com.tencent",
    )
}
