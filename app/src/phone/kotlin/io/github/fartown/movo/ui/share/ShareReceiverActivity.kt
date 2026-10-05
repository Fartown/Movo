package io.github.fartown.movo.ui.share

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.fartown.movo.ui.AgentConversationSheetActivity
import kotlin.concurrent.thread

/**
 * 系统分享面板里的「Movo」（规范 8.9.1）。不显示界面：趁来源 App 给的读取授权还有效，把图片和文件复制进
 * App 私有目录，然后打开对话浮层并把内容交给它。对外只接收 SEND / SEND_MULTIPLE。
 */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = intent
        if (incoming?.action != Intent.ACTION_SEND && incoming?.action != Intent.ACTION_SEND_MULTIPLE) {
            finish()
            return
        }
        val source = shareSourceLabel(this, referrer)
        thread(name = "movo-share-import") {
            val content = ShareImporter(applicationContext).import(incoming, source)
            runOnUiThread {
                if (!isFinishing && !isDestroyed && !content.isEmpty) {
                    startActivity(
                        content.toExtras(
                            Intent(this, AgentConversationSheetActivity::class.java)
                                .setAction(AgentConversationSheetActivity.ACTION_SHARE)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                        ),
                    )
                }
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }
    }
}
