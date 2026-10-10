package com.majocafe.majoapp

import android.Manifest
import android.app.DownloadManager
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Descargas HTTP(S) y archivos generados por la web (blob/data), en Descargas. */
class WebDownloadController(
    private val activity: AppCompatActivity,
    private val web: WebView,
    private val requestStoragePermission: () -> Unit
) {
    private val app = activity.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val pending = mutableListOf<() -> Unit>()
    private var permissionRequested = false
    private var installed = false
    @Volatile private var stopped = false
    private val maxBytes = 8 * 1024 * 1024

    fun start() {
        web.setDownloadListener { url, agent, disposition, mime, _ ->
            download(url, agent, disposition, mime)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "MajoDownloads", setOf(
                "https://majocafeteria.com", "https://www.majocafeteria.com"
            )) { _, message, origin, mainFrame, _ ->
                if (stopped || !mainFrame || !trusted(origin)) return@addWebMessageListener
                val raw = message.data ?: return@addWebMessageListener
                if (raw.length > maxBytes * 4 / 3 + 4096) {
                    notice("El archivo generado supera el límite de 8 MB.")
                    return@addWebMessageListener
                }
                try {
                    val json = JSONObject(raw)
                    if (json.has("error")) {
                        notice("No se pudo preparar el archivo para descargar.")
                    } else {
                        val encoded = json.getString("base64")
                        val name = safeName(json.optString("nombre", "archivo"))
                        val mime = cleanMime(json.optString("mime", "application/octet-stream"))
                        afterPermission { saveBase64(encoded, name, mime) }
                    }
                } catch (_: Exception) { notice("No se pudo descargar el archivo generado.") }
            }
            installed = true
        }
    }

    fun download(url: String, userAgent: String? = null, disposition: String? = null, mime: String? = null) {
        if (stopped) return
        val uri = Uri.parse(url)
        when (uri.scheme) {
            "https", "http" -> {
                val name = safeName(URLUtil.guessFileName(url, disposition, mime))
                afterPermission {
                    try {
                        val request = DownloadManager.Request(uri)
                            .setTitle(name)
                            .setDescription("Descarga de Majo")
                            .setMimeType(cleanMime(mime))
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                        val agent = userAgent ?: web.settings.userAgentString
                        if (!agent.isNullOrBlank()) request.addRequestHeader("User-Agent", agent)
                        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                            request.addRequestHeader("Cookie", it)
                        }
                        app.getSystemService(DownloadManager::class.java).enqueue(request)
                        notice("Descarga iniciada. Se guardará en Descargas.")
                    } catch (_: Exception) { notice("No se pudo iniciar la descarga.") }
                }
            }
            "blob", "data" -> {
                if (!installed) {
                    notice("Actualiza Android System WebView para descargar archivos generados por la web.")
                    return
                }
                installPageHandler()
                val name = safeName(URLUtil.guessFileName(url, disposition, mime))
                web.evaluateJavascript("window.__majoDownloadLocal(${JSONObject.quote(url)}, ${JSONObject.quote(name)});", null)
            }
            else -> notice("Este enlace no es un archivo descargable compatible.")
        }
    }

    fun installPageHandler() {
        if (stopped || !installed) return
        web.evaluateJavascript("""
            (() => {
              if (window.__majoDownloadLocal || !window.MajoDownloads) return;
              let busy = false;
              window.__majoDownloadLocal = async (url, name) => {
                if (busy) return;
                busy = true;
                try {
                  const response = await fetch(url);
                  if (!response.ok) throw new Error('Descarga fallida');
                  const blob = await response.blob();
                  if (blob.size > 8388608) throw new Error('Archivo demasiado grande');
                  const dataUrl = await new Promise((resolve, reject) => {
                    const reader = new FileReader();
                    reader.onload = () => resolve(reader.result);
                    reader.onerror = () => reject(new Error('No se pudo leer el archivo'));
                    reader.readAsDataURL(blob);
                  });
                  window.MajoDownloads.postMessage(JSON.stringify({
                    base64: dataUrl.slice(dataUrl.indexOf(',') + 1),
                    nombre: name || 'archivo', mime: blob.type || 'application/octet-stream'
                  }));
                } catch (_) {
                  window.MajoDownloads.postMessage(JSON.stringify({ error: true }));
                } finally { busy = false; }
              };
              document.addEventListener('click', event => {
                const link = event.target.closest ? event.target.closest('a[href]') : null;
                if (!link || !/^(blob:|data:)/.test(link.href)) return;
                event.preventDefault();
                window.__majoDownloadLocal(link.href, link.download || 'archivo');
              }, true);
            })();
        """.trimIndent(), null)
    }

    private fun afterPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 29 || ContextCompat.checkSelfPermission(app,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            if (pending.size >= 3) { notice("Espera a terminar las descargas pendientes."); return }
            pending.add(action)
            if (!permissionRequested) {
                permissionRequested = true
                requestStoragePermission()
            }
        }
    }
    fun onStoragePermission(granted: Boolean) {
        permissionRequested = false
        val jobs = pending.toList()
        pending.clear()
        if (granted && !stopped) jobs.forEach { it() }
        else if (!stopped) notice("Se necesita permiso para guardar en Descargas en este Android.")
    }
    private fun saveBase64(encoded: String, name: String, mime: String) {
        worker.execute {
            var target: Uri? = null
            var legacyFile: File? = null
            try {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                require(bytes.size <= maxBytes)
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = requireNotNull(app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
                    target = uri
                    requireNotNull(app.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
                    app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                } else {
                    @Suppress("DEPRECATION")
                    val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    check(directory.exists() || directory.mkdirs())
                    val file = File(directory, name)
                    legacyFile = file
                    file.outputStream().use { it.write(bytes) }
                    android.media.MediaScannerConnection.scanFile(app, arrayOf(file.absolutePath), arrayOf(mime), null)
                }
                notice("Archivo guardado en Descargas: $name")
            } catch (_: Exception) {
                target?.let { try { app.contentResolver.delete(it, null, null) } catch (_: Exception) { } }
                legacyFile?.delete()
                notice("No se pudo guardar el archivo en Descargas.")
            }
        }
    }
    private fun cleanMime(value: String?): String {
        val mime = value?.substringBefore(';')?.trim().orEmpty()
        return if (Regex("[A-Za-z0-9!#$&^_.+\\-]+/[A-Za-z0-9!#$&^_.+\\-]+").matches(mime)) mime else "application/octet-stream"
    }
    private fun safeName(value: String): String {
        val plain = value.substringAfterLast('/').substringAfterLast('\\')
            .map { if (it.isISOControl() || it in ":*?\"<>|") '_' else it }.joinToString("")
            .trim().trim('.').take(140).ifEmpty { "archivo" }
        val dot = plain.lastIndexOf('.').takeIf { it > 0 } ?: plain.length
        return plain.substring(0, dot) + "-" + UUID.randomUUID().toString().take(8) + plain.substring(dot)
    }
    private fun trusted(uri: Uri) = uri.scheme == "https" && uri.host in
        setOf("majocafeteria.com", "www.majocafeteria.com") &&
        (uri.port == -1 || uri.port == 443) && uri.userInfo == null
    private fun notice(text: String) { main.post { if (!stopped) Toast.makeText(app, text, Toast.LENGTH_LONG).show() } }
    fun stop() {
        stopped = true
        pending.clear()
        web.setDownloadListener(null)
        if (installed && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.removeWebMessageListener(web, "MajoDownloads")
        }
        worker.shutdown()
    }
}
