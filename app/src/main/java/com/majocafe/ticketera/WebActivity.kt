package com.majocafe.ticketera

import android.annotation.SuppressLint
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.concurrent.Executors

class WebActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private var bridgeAdded = false
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        web = WebView(this)
        val root = FrameLayout(this).apply { addView(web, FrameLayout.LayoutParams(-1, -1)) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame || trusted(request.url)) return false
                if (request.url.scheme in setOf("https", "http", "tel", "mailto")) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    catch (_: ActivityNotFoundException) { Toast.makeText(this@WebActivity, "No se puede abrir este enlace.", Toast.LENGTH_SHORT).show() }
                }
                return true
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "MajoPrinter", setOf(
                "https://majocafeteria.com", "https://www.majocafeteria.com"
            )) { _, message, sourceOrigin, isMainFrame, reply ->
                if (!isMainFrame || !trusted(sourceOrigin)) return@addWebMessageListener
                var requestId = ""
                fun answer(result: PrintResult) {
                    if (!isDestroyed && !isFinishing) reply.postMessage(JSONObject().apply {
                        put("requestId", requestId); put("ok", result.ok)
                        put("codigo", result.code); put("mensaje", result.message)
                    }.toString())
                }
                try {
                    val raw = message.data ?: throw IllegalArgumentException("Mensaje vacío")
                    require(raw.length <= 131072) { "Pedido demasiado grande" }
                    val envelope = JSONObject(raw)
                    requestId = envelope.optString("requestId", "").take(100)
                    val pedido = envelope.optJSONObject("pedido") ?: envelope
                    val text = ReceiptFormatter.format(PedidoParser.parse(pedido))
                    BluetoothTicketPrinter.print(applicationContext, text) { answer(it) }
                } catch (error: Exception) {
                    answer(PrintResult(false, "pedido_invalido", error.message ?: "Pedido inválido"))
                }
            }
            bridgeAdded = true
        } else {
            Toast.makeText(this, "Actualiza Android System WebView para imprimir desde la web.", Toast.LENGTH_LONG).show()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) web.loadUrl("https://majocafeteria.com/")
    }
    private fun trusted(uri: Uri) = uri.scheme == "https" &&
        uri.host in setOf("majocafeteria.com", "www.majocafeteria.com") &&
        (uri.port == -1 || uri.port == 443) && uri.userInfo == null
    override fun onSaveInstanceState(outState: Bundle) { web.saveState(outState); super.onSaveInstanceState(outState) }
    override fun onPause() {
        if (::web.isInitialized) { web.onPause(); cookieWriter.execute { CookieManager.getInstance().flush() } }
        super.onPause()
    }
    override fun onResume() { super.onResume(); if (::web.isInitialized) web.onResume() }
    override fun onDestroy() {
        if (::web.isInitialized) {
            if (bridgeAdded) WebViewCompat.removeWebMessageListener(web, "MajoPrinter")
            web.stopLoading(); web.destroy()
        }
        super.onDestroy()
    }
    companion object { private val cookieWriter = Executors.newSingleThreadExecutor() }
}
