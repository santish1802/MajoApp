package com.majocafe.majoapp

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.max

/** Oculta la barra de estado con el IME abierto y restaura el modo inmersivo al cerrarlo.
 * En API 24–29, FLAG_FULLSCREEN exige ajustar manualmente el área tapada por el IME.
 */
class ImmersiveKeyboardController(private val activity: Activity, private val content: View) {
    private val decor = activity.window.decorView
    private val visibleFrame = Rect()
    private val location = IntArray(2)
    private var keyboardVisible = false
    private var fullWindowHeight = 0
    private var stopped = false
    private var ownsFullscreenFlag = false
    private val restore = Runnable {
        if (!stopped && !activity.isFinishing && !activity.isDestroyed &&
            activity.hasWindowFocus()) {
            if (keyboardVisible) hideStatusBar() else hideSystemBars()
        }
    }
    private val legacyLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        updateLegacyKeyboard()
    }

    fun start() {
        stopped = false
        if (Build.VERSION.SDK_INT < 30) {
            ownsFullscreenFlag = activity.window.attributes.flags and
                    WindowManager.LayoutParams.FLAG_FULLSCREEN == 0
            // adjustResize se ignora con FULLSCREEN; el listener inferior adapta el WebView.
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        hideSystemBars()
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
                updateKeyboard(visible, if (visible) bars.top else 0, if (visible) ime.bottom else 0)
                if (visible && insets.isVisible(WindowInsetsCompat.Type.statusBars())) {
                    hideStatusBar()
                }
            }
            // En Android 10 e inferiores, la geometría visible sirve de respaldo
            // porque los insets del IME se calculan de forma aproximada.
            insets
        }
        if (Build.VERSION.SDK_INT < 30) {
            decor.viewTreeObserver.addOnGlobalLayoutListener(legacyLayoutListener)
        }
        ViewCompat.requestApplyInsets(content)
    }

    private fun updateLegacyKeyboard() {
        if (stopped || decor.height == 0) return
        decor.getWindowVisibleDisplayFrame(visibleFrame)
        decor.getLocationOnScreen(location)
        val visibleBottom = (visibleFrame.bottom - location[1]).coerceAtLeast(0)
        // Guarda la altura sin teclado; soporta tanto ventanas redimensionadas
        // por adjustResize como ventanas fullscreen que no se redimensionan.
        fullWindowHeight = max(fullWindowHeight, decor.height)
        val threshold = (100 * content.resources.displayMetrics.density).toInt()
        val visible = fullWindowHeight - visibleBottom > threshold
        // Añade solo la parte que realmente tapa esta ventana. Si adjustResize
        // ya la encogió, el padding inferior es 0, evitando descontar el IME dos veces.
        val bottom = if (visible) (decor.height - visibleBottom).coerceAtLeast(0) else 0
        val top = if (visible) (visibleFrame.top - location[1]).coerceAtLeast(0) else 0
        updateKeyboard(visible, top, bottom)
    }

    private fun updateKeyboard(visible: Boolean, top: Int, bottom: Int) {
        val wasVisible = keyboardVisible
        keyboardVisible = visible
        if (content.paddingTop != top || content.paddingBottom != bottom) {
            content.setPadding(0, top, 0, bottom)
        }
        if (visible) {
            decor.removeCallbacks(restore)
            if (!wasVisible) hideStatusBar()
        } else if (wasVisible) restoreWhenReady()
    }

    fun restoreWhenReady() {
        if (stopped) return
        decor.removeCallbacks(restore)
        // Se aplica después de que termine la restauración de barras del IME.
        decor.postDelayed(restore, 200L)
    }

    @Suppress("DEPRECATION")
    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT < 30) {
            // Refuerzo de la ventana fullscreen en Android 7–10; no oculta el teclado.
            if (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN == 0) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
            val flags = decor.systemUiVisibility
            if (flags and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                decor.systemUiVisibility = flags or View.SYSTEM_UI_FLAG_FULLSCREEN
            }
        } else {
            WindowCompat.getInsetsController(activity.window, decor).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.statusBars())
            }
        }
    }

    private fun hideSystemBars() {
        hideStatusBar()
        WindowCompat.getInsetsController(activity.window, decor).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun stop() {
        stopped = true
        decor.removeCallbacks(restore)
        ViewCompat.setOnApplyWindowInsetsListener(content, null)
        if (Build.VERSION.SDK_INT < 30 && decor.viewTreeObserver.isAlive) {
            decor.viewTreeObserver.removeOnGlobalLayoutListener(legacyLayoutListener)
        }
        if (ownsFullscreenFlag) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            ownsFullscreenFlag = false
        }
    }
}
