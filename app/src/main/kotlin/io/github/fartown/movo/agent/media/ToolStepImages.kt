package io.github.fartown.movo.agent.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import io.github.fartown.movo.agent.model.AgentModelClient
import java.io.ByteArrayOutputStream

/**
 * 执行卡里一步的图片（Movo 看到的屏幕截图、读到的图片）：只存进程内的缩略图，不进对话记录，
 * 重启后就没了（工具可视化方案 §6 决策 1）。键由 [put] 生成，写进 [io.github.fartown.movo.agent.tools.core.ToolUiBlock.Images]。
 */
internal object ToolStepImages {
    private const val MAX_LONG_EDGE = 720
    private const val JPEG_QUALITY = 80
    private const val MAX_TOTAL_BYTES = 12 * 1024 * 1024

    private val cache = object : LruCache<String, ByteArray>(MAX_TOTAL_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    /** 存一张图的缩略图；解不出来返回 null。 */
    fun put(toolCallId: String, index: Int, image: AgentModelClient.ModelImage): String? {
        val bytes = image.reference.decodeDataUrl() ?: return null
        val thumbnail = thumbnail(bytes) ?: return null
        val key = "$toolCallId#$index"
        cache.put(key, thumbnail)
        return key
    }

    /** JPEG 缩略图字节；已经不在缓存里（重启、被挤掉）时为 null。 */
    fun get(key: String): ByteArray? = cache.get(key)

    internal fun clearForTests() = cache.evictAll()

    private fun thumbnail(bytes: ByteArray): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_LONG_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = MAX_LONG_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            decoded
        }
        try {
            ByteArrayOutputStream().use { output ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                output.toByteArray()
            }
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }.getOrNull()

    private fun String.decodeDataUrl(): ByteArray? {
        if (!startsWith("data:image/", ignoreCase = true)) return null
        val marker = indexOf("base64,", ignoreCase = true).takeIf { it >= 0 } ?: return null
        return runCatching { Base64.decode(substring(marker + "base64,".length), Base64.DEFAULT) }.getOrNull()
    }
}
