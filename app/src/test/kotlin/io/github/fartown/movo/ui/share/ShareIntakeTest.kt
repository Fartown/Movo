package io.github.fartown.movo.ui.share

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareIntakeTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun linkShareCombinesTitleAndUrl() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "把手机交给 AI Agent")
            .putExtra(Intent.EXTRA_TEXT, "https://sspai.com/post/98231")
        assertEquals("把手机交给 AI Agent https://sspai.com/post/98231", ShareIntentParser.text(intent))
        val alreadyIncluded = Intent(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_SUBJECT, "标题")
            .putExtra(Intent.EXTRA_TEXT, "标题 https://a.b/c")
        assertEquals("标题 https://a.b/c", ShareIntentParser.text(alreadyIncluded))
    }

    @Test
    fun streamsAreCollectedDedupedAndOnlyContentScheme() {
        val a = Uri.parse("content://media/external/images/1")
        val b = Uri.parse("content://media/external/images/2")
        val own = Uri.parse("file:///data/data/io.github.fartown.movo/files/secret")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b, own))
        intent.clipData = ClipData.newRawUri("x", a)
        assertEquals(listOf(a, b), ShareIntentParser.streams(intent))
    }

    @Test
    fun importerCopiesAndReportsSkips() {
        val resolver = shadowOf(context.contentResolver)
        val images = (1..9).map { Uri.parse("content://test/images/$it") }
        images.forEach { resolver.registerInputStream(it, ByteArrayInputStream(byteArrayOf(1, 2, 3))) }
        val unreadable = Uri.parse("content://test/images/missing")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(images + unreadable))
        val result = ShareImporter(context).import(intent, "相册")
        assertEquals(8, result.imagePaths.size)
        assertTrue(result.imagePaths.all { File(it).length() == 3L })
        assertEquals(2, result.skippedCount)
        assertEquals("相册", result.sourceLabel)
    }

    @Test
    fun oversizedImageIsSkippedAsTooLarge() {
        val uri = Uri.parse("content://test/images/big")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(12 * 1024 * 1024 + 1)))
        val intent = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
        val result = ShareImporter(context).import(intent, null)
        assertTrue(result.imagePaths.isEmpty())
        assertEquals(SkipReason.TooLarge, result.skipReason)
    }

    @Test
    fun documentIsImportedIntoWorkspace() {
        val uri = Uri.parse("content://test/docs/report")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream("hello".toByteArray()))
        val intent = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
        val result = ShareImporter(context).import(intent, null)
        assertEquals(1, result.filePaths.size)
        assertTrue(result.filePaths.single().contains("/workspace/imports/"))
        assertEquals("hello", File(result.filePaths.single()).readText())
        assertNull(result.skipReason)
    }

    @Test
    fun contentSurvivesTheHandoffIntent() {
        val content = SharedContent("hi", listOf("/a.png"), listOf("/b.pdf"), "浏览器", 1, SkipReason.TooLarge)
        assertEquals(content, SharedContent.fromExtras(content.toExtras(Intent())))
    }

    @Test
    fun introKindAndChipPrompt() {
        assertEquals(ShareIntro.Kind.Images, ShareIntro.of(SharedContent("t", listOf("/a"), listOf("/b"), null, 0, null)).kind)
        assertEquals(ShareIntro.Kind.Files, ShareIntro.of(SharedContent("", emptyList(), listOf("/b"), null, 0, null)).kind)
        assertEquals(ShareIntro.Kind.Text, ShareIntro.of(SharedContent("t", emptyList(), emptyList(), null, 0, null)).kind)
        assertEquals("总结\n\n标题 https://a.b", shareChipPrompt("总结", " 标题 https://a.b "))
        assertEquals("描述图片", shareChipPrompt("描述图片", ""))
    }
}
