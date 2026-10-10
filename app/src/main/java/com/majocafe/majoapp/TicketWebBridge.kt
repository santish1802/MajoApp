package com.majocafe.majoapp

import android.net.Uri
import android.util.Base64
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/** Puente para la web existente, sin sustituir su WebViewClient ni WebChromeClient. */
class TicketWebBridge(
    private val activity: AppCompatActivity,
    private val web: WebView,
    private val selectPrinter: (((PrintResult) -> Unit) -> Unit)
) {
    private var installed = false
    private var stopped = false
    fun start() {
        if (installed) return
        stopped = false
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "MajoPrinter", setOf(
                "https://majocafeteria.com", "https://www.majocafeteria.com"
            )) { _, message, origin, isMainFrame, reply ->
                if (stopped || !isMainFrame || !trusted(origin)) return@addWebMessageListener
                var requestId = ""
                fun answer(result: PrintResult) {
                    if (!stopped && !activity.isDestroyed && !activity.isFinishing &&
                        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                        reply.postMessage(JSONObject().apply {
                            put("requestId", requestId); put("ok", result.ok)
                            put("codigo", result.code); put("mensaje", result.message)
                            put("impresora", BluetoothTicketPrinter.selectedName(activity))
                            put("seleccionada", BluetoothTicketPrinter.address(activity) != null)
                            put("permiso", BluetoothTicketPrinter.hasPermission(activity))
                        }.toString())
                    }
                }
                try {
                    val raw = message.data ?: throw IllegalArgumentException("Mensaje vacío")
                    require(raw.length <= 131072) { "Pedido demasiado grande" }
                    val envelope = JSONObject(raw)
                    requestId = envelope.optString("requestId", "").take(100)
                    when (envelope.optString("accion", "imprimir")) {
                        "seleccionar" -> selectPrinter { answer(it) }
                        "estado" -> answer(PrintResult(true, "estado", "Configuración actual de la ticketera."))
                        "escpos" -> {
                            val encoded = envelope.getString("datos_base64")
                            require(encoded.length in 4..87384 && encoded.length % 4 == 0 &&
                                Regex("[A-Za-z0-9+/]+={0,2}").matches(encoded)) {
                                "ESC/POS requiere Base64 válido, de hasta 65536 bytes."
                            }
                            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                            require(bytes.isNotEmpty() && bytes.size <= 65536) { "ESC/POS supera el tamaño permitido." }
                            BluetoothTicketPrinter.printEscPos(activity, bytes) { answer(it) }
                        }
                        "imprimir" -> {
                            val pedido = envelope.optJSONObject("pedido") ?: envelope
                            BluetoothTicketPrinter.printReceipt(activity, PedidoParser.parse(pedido)) { answer(it) }
                        }
                        else -> answer(PrintResult(false, "accion", "Acción de impresora desconocida."))
                    }
                } catch (error: Exception) {
                    answer(PrintResult(false, "pedido_invalido", error.message ?: "Pedido inválido"))
                }
            }
            installed = true
        } else {
            Toast.makeText(
                activity,
                "Actualiza Android System WebView para imprimir desde la web.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    private fun trusted(uri: Uri) = uri.scheme == "https" &&
        uri.host in setOf("majocafeteria.com", "www.majocafeteria.com") &&
        (uri.port == -1 || uri.port == 443) && uri.userInfo == null
    fun stop() {
        stopped = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            if (installed) {
                WebViewCompat.removeWebMessageListener(web, "MajoPrinter")
                installed = false
            }
        }
    }
}
