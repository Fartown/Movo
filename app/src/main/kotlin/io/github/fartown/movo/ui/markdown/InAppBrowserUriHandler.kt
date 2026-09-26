package io.github.fartown.movo.ui.markdown

import android.content.Context
import android.content.Intent
import androidx.compose.ui.platform.UriHandler
import io.github.fartown.movo.ui.MainActivity
import java.net.URI

/** Markdown links share the same browser as the Agent's browser tools. */
internal class InAppBrowserUriHandler(
    private val context: Context,
    private val onOpened: () -> Unit = {},
) : UriHandler {
    override fun openUri(uri: String) {
        val url = normalizeBrowserLink(uri) ?: return
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_BROWSER)
                .putExtra(EXTRA_BROWSER_URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        onOpened()
    }

    companion object {
        const val ACTION_OPEN_BROWSER = "io.github.fartown.movo.OPEN_BROWSER"
        const val EXTRA_BROWSER_URL = "browser_url"
    }
}

/** Older saved citations contain CommonMark angle delimiters in their destination. */
internal fun normalizeBrowserLink(raw: String): String? {
    val value = raw.trim().removeSurrounding("<", ">")
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    return value.takeIf {
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.rawAuthority.isNullOrBlank()
    }
}
