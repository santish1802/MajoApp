package com.majocafeteria.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min
import kotlin.math.roundToInt

/** Convierte únicamente el endpoint de las boletas a una vista HTML local. */
class BoletaPdfPreview(private val context: Context, private val userAgent: String) {
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (request.method != "GET" || !isTrusted(request.url) ||
            !Regex("^/pedidos/[0-9]+/boleta-pdf/?$").matches(request.url.path.orEmpty())) return null

        // shouldInterceptRequest se ejecuta fuera del hilo de la interfaz.
        val pdfFile = File.createTempFile("boleta-", ".pdf", context.cacheDir)
        var htmlFile: File? = null
        return try {
            download(request.url, pdfFile, request.requestHeaders)
            val resultFile = File.createTempFile("boleta-preview-", ".html", context.cacheDir)
            htmlFile = resultFile
            renderHtml(pdfFile, resultFile)
            val stream = object : FilterInputStream(FileInputStream(resultFile)) {
                override fun close() {
                    try { super.close() } finally { resultFile.delete() }
                }
            }
            response(stream)
        } catch (error: PreviewException) {
            htmlFile?.delete()
            errorResponse(error.message ?: "No se pudo mostrar el PDF.")
        } catch (_: Exception) {
            htmlFile?.delete()
            errorResponse("No se pudo cargar el PDF. Comprueba la conexión y vuelve a abrir la boleta.")
        } finally {
            pdfFile.delete()
        }
    }

    private fun isTrusted(uri: Uri): Boolean = uri.scheme == "https" &&
        (uri.host.equals("majocafeteria.com", true) || uri.host.equals("www.majocafeteria.com", true)) &&
        (uri.port == -1 || uri.port == 443) && uri.userInfo == null

    private fun download(initialUri: Uri, target: File, headers: Map<String, String>) {
        var current = initialUri
        repeat(6) {
            if (!isTrusted(current)) throw PreviewException("El PDF redirige a una dirección no admitida.")
            val connection = URL(current.toString()).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Accept", "application/pdf")
                connection.setRequestProperty("Accept-Encoding", "identity")
                CookieManager.getInstance().getCookie(current.toString())?.let {
                    connection.setRequestProperty("Cookie", it)
                }
                headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value?.let {
                    if (isTrusted(Uri.parse(it))) connection.setRequestProperty("Referer", it)
                }
                val status = connection.responseCode
                connection.headerFields.forEach { (name, values) ->
                    if (name.equals("Set-Cookie", true)) values?.forEach {
                        CookieManager.getInstance().setCookie(current.toString(), it, null)
                    }
                }
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Missing redirect location")
                    current = Uri.parse(URL(URL(current.toString()), location).toString())
                    return@repeat
                }
                if (status == 401 || status == 403) throw PreviewException("Tu sesión no permite abrir esta boleta. Vuelve a iniciar sesión.")
                if (status !in 200..299) throw PreviewException("El servidor no pudo entregar el PDF. Vuelve a abrir la boleta.")
                if (connection.contentType?.contains("text/html", true) == true) {
                    throw PreviewException("El servidor devolvió una página en lugar del PDF. Comprueba que tu sesión sigue activa.")
                }
                if (connection.contentLengthLong > MAX_PDF_BYTES) throw PreviewException("El PDF supera el tamaño admitido de 20 MB.")
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            total += count
                            if (total > MAX_PDF_BYTES) throw PreviewException("El PDF supera el tamaño admitido de 20 MB.")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                return
            } finally { connection.disconnect() }
        }
        throw PreviewException("No se pudo seguir la dirección del PDF.")
    }

    private fun renderHtml(pdfFile: File, htmlFile: File) {
        // PdfRenderer necesita un archivo con acceso aleatorio, no un stream HTTP.
        val descriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try { PdfRenderer(descriptor) } catch (_: Exception) {
            descriptor.close()
            throw PreviewException("El documento no es un PDF válido o requiere contraseña.")
        }
        renderer.use { pdf ->
            if (pdf.pageCount !in 1..30) throw PreviewException("Este visor de boletas admite documentos de hasta 30 páginas.")
            htmlFile.bufferedWriter().use { writer ->
                writer.write("""<!doctype html><html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Boleta PDF</title><style>html,body{margin:0;background:#eeeeee}main{max-width:1100px;margin:auto}img{display:block;width:100%;height:auto;background:white;margin-bottom:12px}p{font:14px sans-serif;text-align:center;color:#555}</style></head><body><main>""")
                for (index in 0 until pdf.pageCount) {
                    pdf.openPage(index).use { page ->
                        val scale = min(1000f / page.width, 4000f / page.height)
                        val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                        val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val imageBytes = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, imageBytes)
                            writer.write("<img alt=\"Página ${index + 1} de ${pdf.pageCount}\" src=\"data:image/png;base64,")
                            writer.write(Base64.encodeToString(imageBytes.toByteArray(), Base64.NO_WRAP))
                            writer.write("\">")
                            writer.flush()
                            if (htmlFile.length() > 60L * 1024 * 1024) throw PreviewException("El PDF es demasiado complejo para mostrarlo en este visor.")
                        } finally { bitmap.recycle() }
                    }
                }
                writer.write("<p>${pdf.pageCount} página(s)</p></main></body></html>")
            }
        }
    }

    private fun errorResponse(message: String): WebResourceResponse {
        // Mensajes fijos; no se interpolan errores, direcciones ni contenido del servidor.
        val html = """<!doctype html><html lang="es"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><body style="font:16px sans-serif;padding:20px;background:#fff8f0"><p>$message</p></body></html>"""
        return response(ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
    }

    private fun response(stream: java.io.InputStream): WebResourceResponse =
        WebResourceResponse("text/html", "UTF-8", 200, "OK", mapOf(
            "Cache-Control" to "no-store",
            "Content-Security-Policy" to "default-src 'none'; img-src data:; style-src 'unsafe-inline'"
        ), stream)

    private class PreviewException(message: String) : Exception(message)
    companion object { private const val MAX_PDF_BYTES = 20L * 1024 * 1024 }
}
