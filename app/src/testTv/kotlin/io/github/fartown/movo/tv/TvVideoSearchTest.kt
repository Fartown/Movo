package io.github.fartown.movo.tv

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 直达搜索的意图必须与 TCL 真机验证过的写法一致（.docs/tv-video-deeplink/report.md）。 */
@RunWith(RobolectricTestRunner::class)
class TvVideoSearchTest {
    @Test
    fun tencentUsesTheOpenActionAndResultPage() {
        val intent = TvVideoApp.TENCENT.searchIntent("狂飙")
        assertEquals("com.tencent.qqlivetv.open", intent.action)
        assertEquals("com.ktcp.csvideo", intent.`package`)
        assertEquals("tenvideo2://?action=59&search_keyword=%E7%8B%82%E9%A3%99", intent.dataString)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun iqiyiSendsSearchResultToTheLoadingActivity() {
        val intent = TvVideoApp.IQIYI.searchIntent("西游记")
        assertEquals("com.gitvdemo.video.action.ACTION_SEARCHRESULT", intent.action)
        assertEquals("com.gala.video.app.epg.LoadingActivity", intent.component?.className)
        assertEquals("西游记", intent.getStringExtra("keyword"))
    }

    @Test
    fun youkuOnlyPrefillsTheSearchBox() {
        val intent = TvVideoApp.YOUKU.searchIntent("西游记")
        assertEquals("cibntv_yingshi://search?keyword=%E8%A5%BF%E6%B8%B8%E8%AE%B0", intent.dataString)
        assertTrue(TvVideoApp.YOUKU.prefillOnly)
    }

    @Test
    fun titlesLoseBookTitleMarks() {
        assertEquals("狂飙", TvVideoSearchTool.cleanTitle(" 《狂飙》 "))
        assertEquals("西游记", TvVideoSearchTool.cleanTitle("「西游记」"))
    }
}
