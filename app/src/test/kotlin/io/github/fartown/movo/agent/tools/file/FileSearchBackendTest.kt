package io.github.fartown.movo.agent.tools.file

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.core.AndroidAgentLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * file_search 在 MediaStore 上的查询（审计 C11、C12）：document 按 MIME / 扩展名筛、关键词也匹配目录（音频再加标题和歌手）、
 * 下载查 MediaStore.Downloads。用一个假的 media 提供者把查询条件真的交给 SQLite 执行，按集合模拟 MediaStore 的范围。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FileSearchBackendTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var media: FakeMediaProvider

    /** MediaStore 的 files 表的一小部分列；按集合（图片 / 视频 / 音频 / 下载）用 media_type、is_download 限定范围。 */
    class FakeMediaProvider : ContentProvider() {
        lateinit var db: SQLiteDatabase

        override fun onCreate(): Boolean {
            db = SQLiteDatabase.create(null)
            db.execSQL(
                "CREATE TABLE files (_id INTEGER PRIMARY KEY, _display_name TEXT, mime_type TEXT, relative_path TEXT, " +
                    "title TEXT, artist TEXT, date_modified INTEGER, _size INTEGER, _data TEXT, media_type INTEGER, is_download INTEGER)",
            )
            return true
        }

        val queriedUris = mutableListOf<Uri>()

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            queriedUris += uri
            val segments = uri.pathSegments
            val scope = when {
                "images" in segments -> "media_type=1"
                "video" in segments -> "media_type=3"
                "audio" in segments -> "media_type=2"
                "downloads" in segments -> "is_download=1"
                else -> null
            }
            val where = listOfNotNull(scope, selection?.let { "($it)" }).joinToString(" AND ").ifEmpty { null }
            return db.query("files", projection?.map { it }?.toTypedArray(), where, args?.map { it }?.toTypedArray(), null, null, sort)
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    @Before
    fun setUp() {
        media = Robolectric.setupContentProvider(FakeMediaProvider::class.java, "media")
        var time = 1_790_000_000L
        fun row(name: String, mime: String, dir: String, mediaType: Int, download: Boolean = false, title: String? = null, artist: String? = null) {
            media.db.insert(
                "files", null,
                ContentValues().apply {
                    put("_display_name", name)
                    put("mime_type", mime)
                    put("relative_path", dir)
                    put("title", title ?: name.substringBeforeLast('.'))
                    put("artist", artist)
                    put("date_modified", time--)
                    put("_size", 1024)
                    put("_data", "/storage/emulated/0/$dir$name")
                    put("media_type", mediaType)
                    put("is_download", if (download) 1 else 0)
                },
            )
        }
        row("IMG_001.jpg", "image/jpeg", "DCIM/Camera/", 1)
        row("报告.pdf", "application/pdf", "Documents/", 0)
        row("会议纪要.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Download/", 0, download = true)
        row("notes.md", "application/octet-stream", "Documents/", 0)
        row("app-release.apk", "application/vnd.android.package-archive", "Download/", 0, download = true)
        row("发票截图.png", "image/png", "Download/", 1, download = true)
        row("song.mp3", "audio/mpeg", "Music/", 2, title = "晴天", artist = "周杰伦")
        row("录音 001.m4a", "audio/mp4", "Recordings/", 2)
        // 路径里碰巧带 Download 的相册目录：以前按 LIKE '%Download%' 会被当成下载。
        row("cat.jpg", "image/jpeg", "Pictures/Downloaded/", 1)
    }

    private fun search(type: FileType, location: FileLocation = FileLocation.ANY, query: String? = null): List<String> =
        RealFileSearchBackend(
            context,
            BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = { false }),
            rootAvailable = { false },
            sharedStorageHidden = { false },
        ).search(type, location, query, sinceMillis = null, untilMillis = null, limit = 30, cursor = null).items.map { it.name }

    @Test
    fun document_isFilteredByMimeAndExtension_notEverythingInFiles() {
        assertEquals(setOf("报告.pdf", "会议纪要.docx", "notes.md"), search(FileType.DOCUMENT).toSet())
        // any 仍是全部文件。
        assertEquals(9, search(FileType.ANY).size)
    }

    @Test
    fun downloads_useTheDownloadsCollection_andStillFilterByType() {
        assertEquals(setOf("会议纪要.docx", "app-release.apk", "发票截图.png"), search(FileType.ANY, FileLocation.DOWNLOADS).toSet())
        assertEquals(listOf("发票截图.png"), search(FileType.IMAGE, FileLocation.DOWNLOADS))
        assertEquals(listOf("会议纪要.docx"), search(FileType.DOCUMENT, FileLocation.DOWNLOADS))
        assertTrue(media.queriedUris.all { it.toString() == "content://media/external/downloads" })
    }

    @Test
    fun query_matchesTheDirectoryToo_andAudioTitleAndArtist() {
        // 只按文件名时，「相机里的照片」「Documents 里的文件」都搜不到。
        assertEquals(listOf("IMG_001.jpg"), search(FileType.IMAGE, query = "Camera"))
        assertEquals(setOf("报告.pdf", "notes.md"), search(FileType.DOCUMENT, query = "Documents").toSet())
        assertEquals(listOf("song.mp3"), search(FileType.AUDIO, query = "周杰伦"))
        assertEquals(listOf("song.mp3"), search(FileType.AUDIO, query = "晴天"))
        assertEquals(listOf("录音 001.m4a"), search(FileType.AUDIO, FileLocation.RECORDINGS))
    }

    @Test
    fun query_wildcardsAreLiteral() {
        // `_`、`%` 是 LIKE 通配符：不转义时「_」会匹配所有文件。
        assertEquals(listOf("IMG_001.jpg"), search(FileType.ANY, query = "_"))
        assertEquals(emptyList<String>(), search(FileType.ANY, query = "%"))
    }

    @Test
    fun withoutDownloadsCollection_fallsBackToTheDownloadDirectoryPath() {
        val plan = MediaStoreFileQuery.build(
            FileType.IMAGE, FileLocation.DOWNLOADS, query = null, sinceMillis = null, untilMillis = null,
            downloadsCollection = false,
        )
        assertEquals(MediaStoreFileQuery.Collection.IMAGES, plan.collection)
        assertEquals("_data LIKE ?", plan.selection)
        assertEquals(listOf("%/Download/%"), plan.args)
        val any = MediaStoreFileQuery.build(FileType.ANY, FileLocation.ANY, null, null, null, downloadsCollection = true)
        assertEquals(MediaStoreFileQuery.Collection.FILES, any.collection)
        assertNull(any.selection)
    }
}
