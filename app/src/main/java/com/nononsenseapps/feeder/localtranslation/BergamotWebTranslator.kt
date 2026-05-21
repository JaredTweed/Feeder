package com.nononsenseapps.feeder.localtranslation

import android.annotation.SuppressLint
import android.app.Application
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import com.nononsenseapps.feeder.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class BergamotWebTranslator(
    private val application: Application,
) {
    private val json =
        Json {
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    private val bridge = Bridge()
    private val requestId = AtomicLong(0)
    private val initMutex = Mutex()
    private var webView: WebView? = null
    private var ready = CompletableDeferred<Unit>()
    private var initializedRegistryJson: String = ""

    suspend fun translate(
        content: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
        preserveHtml: Boolean,
        modelRegistry: List<BergamotModelRegistryEntry>,
    ): BergamotWebTranslationResult =
        withContext(Dispatchers.Main.immediate) {
            initialize(modelRegistry)

            val results = mutableListOf<String>()
            for (text in content) {
                results +=
                    translateOne(
                        text = text,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        preserveHtml = preserveHtml,
                    )
            }

            BergamotWebTranslationResult.Success(results)
        }

    private suspend fun initialize(modelRegistry: List<BergamotModelRegistryEntry>) {
        val registryJson = json.encodeToString(modelRegistry)
        initMutex.withLock {
            ensureWebView()
            ready.await()
            if (initializedRegistryJson != registryJson) {
                evaluate("window.FeederBergamot.initialize($registryJson);")
                initializedRegistryJson = registryJson
            }
        }
    }

    private suspend fun translateOne(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        preserveHtml: Boolean,
    ): String {
        val id = requestId.incrementAndGet()
        val response = CompletableDeferred<BergamotWebTranslationResult>()
        bridge.pending[id] = response

        evaluate(
            "window.FeederBergamot.translate(" +
                "$id," +
                "${json.encodeToString(sourceLanguage)}," +
                "${json.encodeToString(targetLanguage)}," +
                "${json.encodeToString(text)}," +
                "$preserveHtml" +
                ");",
        )

        return when (val result = response.await()) {
            is BergamotWebTranslationResult.Success -> result.values.firstOrNull().orEmpty()
            is BergamotWebTranslationResult.Error -> throw IllegalStateException(result.message)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView() {
        if (webView != null) {
            return
        }

        ready = CompletableDeferred()
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        webView =
            WebView(application).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.allowFileAccess = true
                settings.allowFileAccessFromFileURLs = true
                settings.allowUniversalAccessFromFileURLs = true
                webChromeClient = WebChromeClient()
                addJavascriptInterface(bridge, "AndroidBergamot")
                loadUrl(BERGAMOT_PAGE_URL)
            }
    }

    private fun evaluate(script: String) {
        webView?.evaluateJavascript(script, null)
            ?: throw IllegalStateException("Bergamot WebView is not initialized")
    }

    private inner class Bridge {
        val pending = ConcurrentHashMap<Long, CompletableDeferred<BergamotWebTranslationResult>>()

        @JavascriptInterface
        fun onReady() {
            ready.complete(Unit)
        }

        @JavascriptInterface
        fun onTranslationSuccess(
            id: Long,
            translatedText: String,
        ) {
            pending.remove(id)?.complete(BergamotWebTranslationResult.Success(listOf(translatedText)))
        }

        @JavascriptInterface
        fun onTranslationError(
            id: Long,
            message: String,
        ) {
            pending.remove(id)?.complete(BergamotWebTranslationResult.Error(message))
        }

        @JavascriptInterface
        fun onLog(message: String) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("FeederBergamot", message)
            }
        }
    }

    companion object {
        private const val BERGAMOT_PAGE_URL = "file:///android_asset/bergamot/index.html"
    }
}

sealed interface BergamotWebTranslationResult {
    data class Success(
        val values: List<String>,
    ) : BergamotWebTranslationResult

    data class Error(
        val message: String,
    ) : BergamotWebTranslationResult
}
