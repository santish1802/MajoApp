package com.majocafe.majoapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.ValueCallback
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var swipeRefresh: MajoSwipeRefreshLayout
    private lateinit var errorPanel: View
    private lateinit var loadingPanel: View
    private lateinit var pageProgress: ProgressBar
    private lateinit var immersiveKeyboard: ImmersiveKeyboardController
    private lateinit var pdfPreview: BoletaPdfPreview
    private lateinit var ticketBridge: TicketWebBridge
    private var printerSelectionReply: ((PrintResult) -> Unit)? = null
    private var printerSettingsOpen = false
    private val printerSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        printerSettingsOpen = false
        val callback = printerSelectionReply
        printerSelectionReply = null
        callback?.invoke(if (result.resultCode == RESULT_OK)
            PrintResult(true, "seleccionada", "Ticketera seleccionada.")
        else PrintResult(false, "cancelada", "Selección cerrada sin cambiar la ticketera."))
    }
    private lateinit var downloads: WebDownloadController
    private val downloadPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (::downloads.isInitialized) downloads.onStoragePermission(granted)
    }
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var filePickerOpen = false
    private var allowMultipleUploads = false
    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        filePickerOpen = false
        val callback = fileUploadCallback
        fileUploadCallback = null
        if (callback != null) {
            val parsed = if (result.resultCode == RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            } else null
            val allowed = parsed?.filter { isAllowedUploadUri(it) }?.distinct()
                ?.let { if (allowMultipleUploads) it else it.take(1) }
            if (parsed != null && allowed?.size != parsed.distinct().size && allowMultipleUploads) {
                Toast.makeText(this, "Algunos archivos no se pudieron seleccionar.", Toast.LENGTH_LONG).show()
            } else if (result.resultCode == RESULT_OK && allowed.isNullOrEmpty()) {
                Toast.makeText(this, "No se pudo acceder al archivo seleccionado.", Toast.LENGTH_LONG).show()
            }
            callback.onReceiveValue(if (!isDestroyed && !isFinishing && !allowed.isNullOrEmpty()) allowed.toTypedArray() else null)
        }
    }
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

        val root = findViewById<View>(android.R.id.content)
        immersiveKeyboard = ImmersiveKeyboardController(this, root)
        immersiveKeyboard.start()

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
        swipeRefresh.setProgressViewOffset(false, (-80 * density).toInt(), (64 * density).toInt())
        swipeRefresh.setDistanceToTriggerSync((96 * density).toInt())
        swipeRefresh.attachWebView(webView) {
            !mainFrameFailed && errorPanel.visibility != View.VISIBLE &&
                    loadingPanel.visibility != View.VISIBLE &&
                    pageProgress.visibility != View.VISIBLE &&
                    !swipeRefresh.isRefreshing &&
                    webView.url?.let {
                        val uri = Uri.parse(it)
                        isInternalUrl(uri) && (uri.port == -1 || uri.port == 443)
                    } == true
        }
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
        downloads = WebDownloadController(this, webView) {
            downloadPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        downloads.start()
        pdfPreview = BoletaPdfPreview(applicationContext, webView.settings.userAgentString)
        ticketBridge = TicketWebBridge(this, webView) { callback -> openPrinterSettings(callback) }
        ticketBridge.start()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams
            ): Boolean {
                if (isDestroyed || isFinishing || filePickerOpen) {
                    filePathCallback.onReceiveValue(null)
                    return true
                }
                if (fileChooserParams.mode != WebChromeClient.FileChooserParams.MODE_OPEN &&
                    fileChooserParams.mode != WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                    filePathCallback.onReceiveValue(null)
                    Toast.makeText(this@MainActivity, "Este tipo de selección no está disponible.", Toast.LENGTH_SHORT).show()
                    return true
                }
                cancelFileUpload()
                fileUploadCallback = filePathCallback
                allowMultipleUploads = fileChooserParams.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
                try {
                    val intent = fileChooserParams.createIntent().apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultipleUploads)
                    }
                    filePickerOpen = true
                    filePicker.launch(Intent.createChooser(intent, "Seleccionar archivo"))
                } catch (_: ActivityNotFoundException) {
                    filePickerOpen = false
                    cancelFileUpload()
                    Toast.makeText(this@MainActivity, "No hay un selector de archivos disponible.", Toast.LENGTH_LONG).show()
                } catch (_: SecurityException) {
                    filePickerOpen = false
                    cancelFileUpload()
                    Toast.makeText(this@MainActivity, "No se pudo abrir el selector de archivos.", Toast.LENGTH_LONG).show()
                }
                return true
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                pageProgress.progress = newProgress
                pageProgress.visibility = if (newProgress < 100 && !mainFrameFailed &&
                    loadingPanel.visibility != View.VISIBLE && errorPanel.visibility != View.VISIBLE)
                    View.VISIBLE else View.GONE
            }

            override fun onJsBeforeUnload(
                view: WebView,
                url: String,
                message: String,
                result: JsResult
            ): Boolean {
                // Cancelar la salida no garantiza onPageFinished. Detener solo
                // el indicador; WebView conserva su diálogo y decide si recarga.
                swipeRefresh.isRefreshing = false
                swipeRefresh.invalidateRefreshGesture()
                return super.onJsBeforeUnload(view, url, message, result)
            }

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                // Un alert puede interrumpir el flujo de recarga de la web.
                swipeRefresh.isRefreshing = false
                swipeRefresh.invalidateRefreshGesture()
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
        // El puente de tickets está limitado al origen de la web; no se ignoran errores TLS.
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // También recibe la petición del iframe de la boleta.
                return pdfPreview.intercept(request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                if (uri.scheme == "blob" || uri.scheme == "data") {
                    downloads.download(uri.toString())
                    return true
                }
                if (isInternalUrl(uri)) return false
                openExternal(uri)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                // Un permiso de gesto no debe sobrevivir a una navegación.
                swipeRefresh.invalidateRefreshGesture()
                // No entregar una selección pendiente a una página distinta.
                cancelFileUpload()
                navigationGeneration++
                mainFrameFailed = false
                errorPanel.visibility = View.GONE
                pageProgress.progress = 0
                pageProgress.visibility = if (loadingPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                if (url != null && isInternalUrl(Uri.parse(url))) retryUrl = url
            }

            override fun onPageFinished(view: WebView, url: String?) {
                downloads.installPageHandler()
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
        // Solo se presenta la configuración al iniciar si aún no hay ticketera elegida.
        if (savedInstanceState == null && BluetoothTicketPrinter.address(this) == null) {
            webView.post { if (!isDestroyed && !isFinishing) openPrinterSettings(null) }
        }
    }

    private fun openPrinterSettings(callback: ((PrintResult) -> Unit)?) {
        if (printerSettingsOpen) {
            callback?.invoke(PrintResult(false, "ocupada", "Ya está abierta la selección de ticketera."))
            return
        }
        printerSettingsOpen = true
        printerSelectionReply = callback
        printerSettings.launch(Intent(this, PrinterSettingsActivity::class.java))
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

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
        if (::immersiveKeyboard.isInitialized) immersiveKeyboard.restoreWhenReady()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::immersiveKeyboard.isInitialized) immersiveKeyboard.restoreWhenReady()
    }
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
    private fun cancelFileUpload() {
        val callback = fileUploadCallback
        fileUploadCallback = null
        callback?.onReceiveValue(null)
    }

    private fun isAllowedUploadUri(uri: Uri): Boolean {
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) return false
        val provider = packageManager.resolveContentProvider(uri.authority!!, 0) ?: return false
        // Nunca entregar a la web archivos privados de esta app desde un resultado externo.
        return provider.applicationInfo.uid != applicationInfo.uid
    }

    override fun onDestroy() {
        if (::downloads.isInitialized) downloads.stop()
        cancelFileUpload()
        printerSelectionReply = null
        if (::ticketBridge.isInitialized) ticketBridge.stop()
        if (::immersiveKeyboard.isInitialized) immersiveKeyboard.stop()
        javascriptAlert?.dismiss()
        if (::webView.isInitialized) {
            loadingPanel.removeCallbacks(loadingTimeout)
            swipeRefresh.detachWebView()
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
