package com.majocafe.majoapp

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.util.Locale
import kotlin.math.abs

/**
 * SwipeRefreshLayout protegido contra recargas accidentales dentro de WebView.
 *
 * En ACTION_DOWN deja que SwipeRefreshLayout memorice el puntero, pero mantiene
 * el refresco bloqueado hasta comprobar, UNA sola vez, el elemento HTML tocado.
 * Si el gesto comienza sobre un iframe o un contenedor con scroll, no se habilita.
 * La aprobación se mantiene únicamente durante ese gesto; nunca se activa por
 * haber llegado al inicio de una tabla/página durante el arrastre.
 *
 * Si JavaScript falla, tarda demasiado o hay una navegación, no se recarga.
 */
class MajoSwipeRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwipeRefreshLayout(context, attrs) {

    private var webView: WebView? = null
    private var canAttemptRefresh: (() -> Boolean)? = null
    private var queryTemplate: String? = null

    private var gestureVersion = 0L
    private var gestureActive = false
    private var permissionGranted = false
    private var permissionRevoked = false
    private var initializingDown = false
    private var nativeDragCaptured = false
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var startX = 0f
    private var startY = 0f
    private var gestureStartUptime = 0L
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    fun attachWebView(web: WebView, canRefresh: () -> Boolean) {
        webView = web
        canAttemptRefresh = canRefresh
        // Se carga una sola vez, sin ejecutar JS en cada movimiento.
        queryTemplate = try {
            context.assets.open("majo-refresh-hit-test.js")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null // Sin verificación del DOM es preferible NO recargar.
        }
        setOnChildScrollUpCallback { _, _ ->
            // SwipeRefreshLayout debe procesar ACTION_DOWN para memorizar el
            // puntero. No puede iniciar un arrastre durante ese evento.
            if (initializingDown || nativeDragCaptured) false
            else !permissionGranted || permissionRevoked || web.canScrollVertically(-1)
        }
    }

    /** Invalida respuestas JS pendientes al cambiar de documento. */
    fun invalidateRefreshGesture() {
        gestureVersion++
        gestureActive = false
        nativeDragCaptured = false
        permissionGranted = false
        permissionRevoked = true
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginGesture(event)
            MotionEvent.ACTION_MOVE -> if (!nativeDragCaptured) evaluateMotion(event)
            MotionEvent.ACTION_POINTER_DOWN -> if (!nativeDragCaptured) revokePermission()
        }

        initializingDown = event.actionMasked == MotionEvent.ACTION_DOWN
        val intercept = try {
            super.onInterceptTouchEvent(event)
        } finally {
            initializingDown = false
        }
        if (intercept) nativeDragCaptured = true
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL) {
            invalidateRefreshGesture()
        }
        return intercept
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Una vez que el padre capturó el arrastre, deja que su animación
        // nativa termine/cancele el gesto; no bloquees ACTION_UP a mitad.
        if (!nativeDragCaptured && event.actionMasked == MotionEvent.ACTION_MOVE) evaluateMotion(event)
        if (!nativeDragCaptured && event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) revokePermission()
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL) {
            invalidateRefreshGesture()
        }
        return handled
    }

    private fun beginGesture(event: MotionEvent) {
        invalidateRefreshGesture()
        gestureActive = true
        permissionRevoked = false
        pointerId = event.getPointerId(0)
        startX = event.x
        startY = event.y
        gestureStartUptime = SystemClock.uptimeMillis()

        val web = webView ?: return
        val allowedByApp = canAttemptRefresh?.invoke() == true
        if (!allowedByApp || event.pointerCount != 1 ||
            web.canScrollVertically(-1) || web.width <= 0 || web.height <= 0) {
            revokePermission()
            return
        }

        val script = queryTemplate ?: run {
            revokePermission()
            return
        }
        val id = gestureVersion
        val normalizedX = (startX / web.width).coerceIn(0f, 1f)
        val normalizedY = (startY / web.height).coerceIn(0f, 1f)
        val source = script.replace("__TOUCH_X__", String.format(Locale.US, "%.6f", normalizedX))
            .replace("__TOUCH_Y__", String.format(Locale.US, "%.6f", normalizedY))

        try {
            web.evaluateJavascript(source) { result ->
                // Las respuestas viejas (otra pantalla, otro gesto) se ignoran.
                if (id != gestureVersion || !gestureActive || permissionRevoked) return@evaluateJavascript
                // Evita habilitar a mitad de un gesto si el renderizador va lento.
                if (SystemClock.uptimeMillis() - gestureStartUptime > 300L) {
                    revokePermission()
                    return@evaluateJavascript
                }
                if (result != "true" || web.canScrollVertically(-1) ||
                    canAttemptRefresh?.invoke() != true) {
                    revokePermission()
                    return@evaluateJavascript
                }
                permissionGranted = true
            }
        } catch (_: RuntimeException) {
            revokePermission()
        }
    }

    private fun evaluateMotion(event: MotionEvent) {
        if (!gestureActive || permissionRevoked) return
        if (event.pointerCount != 1) {
            revokePermission()
            return
        }
        val index = event.findPointerIndex(pointerId)
        if (index < 0) {
            revokePermission()
            return
        }
        val dy = event.getY(index) - startY
        val dx = event.getX(index) - startX
        // Un desplazamiento inicialmente ascendente u horizontal no puede
        // convertirse en un gesto de recarga tras volver arriba.
        if (dy < -touchSlop || (abs(dx) > touchSlop && abs(dx) > abs(dy)) ||
            webView?.canScrollVertically(-1) != false) {
            revokePermission()
        }
    }

    private fun revokePermission() {
        permissionRevoked = true
        permissionGranted = false
    }

    fun detachWebView() {
        invalidateRefreshGesture()
        setOnChildScrollUpCallback(null)
        webView = null
        canAttemptRefresh = null
        queryTemplate = null
    }
}
