package com.majocafeteria.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.majocafe.majoapp.R
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var errorPanel: View
    private lateinit var loadingPanel: View
    private lateinit var pageProgress: ProgressBar
    private lateinit var pdfPreview: BoletaPdfPreview
    private var javascriptAlert: AlertDialog? = null
    private var mainFrameFailed = false
    private var navigationGeneration = 0L
    private val loadingTimeout = Runnable {
        if (loadingPanel.visibility == View.VISIBLE) {
            showLoadError()
            webView.stopLoading()
        }
    }
    private var retryUrl = HOME_URL

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        // Sin ActionBar, Toolbar ni menú. Las barras del sistema se revelan por gesto.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        val root = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            // Mantiene los campos de la web visibles cuando aparece el teclado.
            view.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        webView = findViewById(R.id.webView)
        // Las cookies se conservan en el almacén propio de la app.
        CookieManager.getInstance().setAcceptCookie(true)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        errorPanel = findViewById(R.id.errorPanel)
        loadingPanel = findViewById(R.id.loadingPanel)
        pageProgress = findViewById(R.id.pageProgress)
        showInitialLoading()
        swipeRefresh.setColorSchemeResources(R.color.majo_coffee)
        val density = resources.displayMetrics.density
        // El círculo comienza fuera del área visible y entra tras un arrastre mayor.
        swipeRefresh.setProgressViewOffset(false, (-100 * density).toInt(), (64 * density).toInt())
        swipeRefresh.setDistanceToTriggerSync((96 * density).toInt())
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> webView.canScrollVertically(-1) }
        swipeRefresh.setOnRefreshListener { reloadPage(showRefreshIndicator = true) }
        findViewById<Button>(R.id.retryButton).setOnClickListener { reloadPage() }

        webView.settings.apply {
            setSupportMultipleWindows(false)
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        pdfPreview = BoletaPdfPreview(applicationContext, webView.settings.userAgentString)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                pageProgress.progress = newProgress
                pageProgress.visibility = if (newProgress < 100 && !mainFrameFailed &&
                    loadingPanel.visibility != View.VISIBLE && errorPanel.visibility != View.VISIBLE)
                    View.VISIBLE else View.GONE
            }

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                if (isFinishing || isDestroyed) {
                    result.cancel()
                    return true
                }
                javascriptAlert?.dismiss()
                var resolved = false
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("Aceptar") { _, _ ->
                        resolved = true
                        result.confirm()
                    }
                    .create()
                // También libera JavaScript si se cierra con Atrás o al destruir la actividad.
                dialog.setOnDismissListener {
                    if (!resolved) {
                        resolved = true
                        result.cancel()
                    }
                    if (javascriptAlert === dialog) javascriptAlert = null
                }
                javascriptAlert = dialog
                dialog.show()
                return true
            }
        }
        // No se añade ningún puente JavaScript ni se ignoran errores de certificado.
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // También recibe la petición del iframe de la boleta.
                return pdfPreview.intercept(request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                if (isInternalUrl(uri)) return false
                openExternal(uri)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                navigationGeneration++
                mainFrameFailed = false
                errorPanel.visibility = View.GONE
                pageProgress.progress = 0
                pageProgress.visibility = if (loadingPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                if (url != null && isInternalUrl(Uri.parse(url))) retryUrl = url
            }

            override fun onPageFinished(view: WebView, url: String?) {
                persistCookies()
                swipeRefresh.isRefreshing = false
                pageProgress.visibility = View.GONE
                if (mainFrameFailed || loadingPanel.visibility != View.VISIBLE || url != view.url) return
                val generation = navigationGeneration
                // La carga terminada no garantiza que el DOM ya esté listo para dibujar.
                // El WebView sigue visible debajo del panel para poder renderizar.
                view.postVisualStateCallback(generation, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (!isDestroyed && !mainFrameFailed && requestId == navigationGeneration) {
                            loadingPanel.removeCallbacks(loadingTimeout)
                            loadingPanel.visibility = View.GONE
                            swipeRefresh.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                        }
                    }
                })
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showLoadError()
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode >= 400) showLoadError()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    navigationGeneration++
                    mainFrameFailed = false
                    errorPanel.visibility = View.GONE
                    swipeRefresh.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        retryUrl = savedInstanceState?.getString("retryUrl")
            ?.takeIf { isInternalUrl(Uri.parse(it)) } ?: HOME_URL
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(retryUrl)
        } else if (savedInstanceState.getBoolean("mainFrameFailed")) {
            showLoadError()
        }
    }

    private fun reloadPage(showRefreshIndicator: Boolean = false) {
        navigationGeneration++
        val wasFailed = mainFrameFailed
        mainFrameFailed = false
        errorPanel.visibility = View.GONE
        if (wasFailed) showInitialLoading()
        swipeRefresh.isRefreshing = showRefreshIndicator && loadingPanel.visibility != View.VISIBLE
        if (wasFailed || webView.url.isNullOrBlank()) webView.loadUrl(retryUrl)
        else webView.reload()
    }

    private fun showLoadError() {
        mainFrameFailed = true
        loadingPanel.removeCallbacks(loadingTimeout)
        loadingPanel.visibility = View.GONE
        swipeRefresh.isRefreshing = false
        pageProgress.visibility = View.GONE
        errorPanel.visibility = View.VISIBLE
    }

    private fun showInitialLoading() {
        loadingPanel.visibility = View.VISIBLE
        swipeRefresh.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        loadingPanel.removeCallbacks(loadingTimeout)
        loadingPanel.postDelayed(loadingTimeout, 30_000L)
    }

    private fun isInternalUrl(uri: Uri): Boolean = uri.scheme == "https" &&
            (uri.host.equals("majocafeteria.com", ignoreCase = true) ||
                    uri.host.equals("www.majocafeteria.com", ignoreCase = true))

    private fun openExternal(uri: Uri) {
        if (uri.scheme !in setOf("https", "http", "tel", "mailto", "whatsapp", "geo")) {
            Toast.makeText(this, R.string.unsupported_link, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.unsupported_link, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.unsupported_link, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        outState.putString("retryUrl", retryUrl)
        outState.putBoolean("mainFrameFailed", mainFrameFailed)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() { super.onResume(); if (::webView.isInitialized) webView.onResume() }
    override fun onPause() {
        if (::webView.isInitialized) {
            // También cubre logins por AJAX que no generan onPageFinished.
            persistCookies()
            webView.onPause()
        }
        super.onPause()
    }

    private fun persistCookies() {
        val manager = CookieManager.getInstance()
        // flush hace I/O; se serializa fuera del hilo de la interfaz.
        cookieWriter.execute { manager.flush() }
    }
    override fun onDestroy() {
        javascriptAlert?.dismiss()
        if (::webView.isInitialized) {
            loadingPanel.removeCallbacks(loadingTimeout)
            swipeRefresh.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val HOME_URL = "https://majocafeteria.com/"
        // Compartido entre recreaciones de la actividad; dura lo que dura el proceso.
        private val cookieWriter = Executors.newSingleThreadExecutor()
    }
}
