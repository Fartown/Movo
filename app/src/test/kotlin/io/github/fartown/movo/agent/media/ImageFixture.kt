package io.github.fartown.movo.agent.media

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/** Valid, incompressible pixels exercise IPC payload budgets rather than invalid base64. */
internal object ImageFixture {
    fun png(): ByteArray {
        val random = Random(710)
        val pixels = IntArray(512 * 512) { random.nextInt() or (0xff shl 24) }
        val bitmap = Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray()
        } } finally { bitmap.recycle() }
    }
}
