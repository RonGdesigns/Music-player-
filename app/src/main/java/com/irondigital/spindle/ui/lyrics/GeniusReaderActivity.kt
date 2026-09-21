package com.irondigital.spindle.ui.lyrics

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * A deliberately small browser for selecting a Genius match and reading it.
 *
 * The page itself still comes from Genius. Once a song page has loaded, a
 * reader-mode script keeps its title, artist and lyric containers while hiding
 * navigation, recommendations, advertising and other page furniture. Nothing
 * is extracted in the background or stored automatically: text selection and
 * copying remain ordinary WebView actions controlled by the user.
 */
class GeniusReaderActivity : ComponentActivity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val startUrl = intent.getStringExtra(EXTRA_URL)
            ?.takeIf(::isGeniusUrl)
            ?: run {
                finish()
                return
            }

        webView = WebView(this)
        configure(webView)
        setContentView(webView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        webView.loadUrl(startUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView) {
        view.setBackgroundColor(Color.rgb(18, 19, 20))
        view.settings.apply {
            javaScriptEnabled = true // Genius renders both results and songs with JavaScript.
            domStorageEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url.toString()
                return if (isGeniusUrl(url)) {
                    false
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(READER_SCRIPT, null)
            }
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "genius_url"

        fun intent(context: Context, url: String): Intent =
            Intent(context, GeniusReaderActivity::class.java).putExtra(EXTRA_URL, url)

        private fun isGeniusUrl(url: String): Boolean = runCatching {
            val uri = android.net.Uri.parse(url)
            uri.scheme == "https" &&
                (uri.host == "genius.com" || uri.host?.endsWith(".genius.com") == true)
        }.getOrDefault(false)

        /**
         * Search pages retain their result cards. Song pages are rebuilt from
         * Genius' semantic lyric containers into a quiet, selectable document.
         */
        private val READER_SCRIPT = """
            (() => {
              if (document.documentElement.dataset.spindleReader === '1') return;

              const lyrics = [...document.querySelectorAll('[data-lyrics-container="true"]')];
              const style = document.createElement('style');
              style.textContent = `
                html, body { background:#121314 !important; color:#f3f1ea !important; }
                body { margin:0 !important; font-family:system-ui,sans-serif !important; }
                header, nav, footer, aside, [class*="Ad__"], [class*="Sidebar"],
                [class*="Sticky"], [class*="Header"], [class*="Footer"] {
                  display:none !important;
                }
                a { color:#57e3ee !important; }
                #spindle-reader { max-width:760px; margin:auto; padding:28px 22px 72px; }
                #spindle-reader h1 { font-size:1.8rem; line-height:1.15; margin:0 0 6px; }
                #spindle-reader .artist { color:#a9b0b4; margin-bottom:28px; }
                #spindle-reader [data-lyrics-container] {
                  font-size:1.15rem; line-height:1.8; margin-bottom:24px;
                  user-select:text !important; -webkit-user-select:text !important;
                }
                #spindle-reader .hint {
                  color:#a9b0b4; font-size:.85rem; border-top:1px solid #34383b;
                  margin-top:30px; padding-top:16px;
                }
              `;
              document.head.appendChild(style);

              if (lyrics.length) {
                const title = document.querySelector('h1')?.textContent?.trim() || 'Lyrics';
                const artist = document.querySelector('a[href*="/artists/"]')?.textContent?.trim() || '';
                const reader = document.createElement('main');
                reader.id = 'spindle-reader';
                const heading = document.createElement('h1');
                heading.textContent = title;
                reader.appendChild(heading);
                if (artist) {
                  const byline = document.createElement('div');
                  byline.className = 'artist';
                  byline.textContent = artist;
                  reader.appendChild(byline);
                }
                lyrics.forEach(block => reader.appendChild(block.cloneNode(true)));
                const hint = document.createElement('div');
                hint.className = 'hint';
                hint.textContent = 'Press and hold the lyrics to select and copy. Then return to Spindle.';
                reader.appendChild(hint);
                document.body.replaceChildren(reader);
              }

              document.documentElement.dataset.spindleReader = '1';
            })();
        """.trimIndent()
    }
}
