package com.majocafe.majoapp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class PrinterSettingsActivity : AppCompatActivity() {
    private lateinit var screenRoot: ScrollView
    private val restoreBars = Runnable {
        if (!isDestroyed && !isFinishing && hasWindowFocus()) showCoffeeBars()
    }
    private lateinit var selected: TextView
    private lateinit var selectedHint: TextView
    private lateinit var badge: TextView
    private lateinit var status: TextView
    private lateinit var selectButton: Button
    private var picker: AlertDialog? = null
    private val coffee by lazy { ContextCompat.getColor(this, R.color.majo_coffee) }
    private val cream by lazy { ContextCompat.getColor(this, R.color.majo_background) }
    // Un tinte suave mantiene las tarjetas visibles aunque majo_background sea blanco.
    private val pageBackground by lazy { ColorUtils.blendARGB(cream, coffee, 0.045f) }
    private val softAccent by lazy { ColorUtils.blendARGB(cream, coffee, 0.08f) }
    private val muted by lazy { Color.argb(180, Color.red(coffee), Color.green(coffee), Color.blue(coffee)) }
    private val border by lazy { Color.argb(26, Color.red(coffee), Color.green(coffee), Color.blue(coffee)) }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) choosePrinter() else showStatus("Permiso pendiente. Permite el acceso a Bluetooth para seleccionar tu impresora.")
    }
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        choosePrinter(askToEnable = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBackground)
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
        setContentView(scroll)
        screenRoot = scroll
        scroll.setBackgroundColor(coffee)
        showCoffeeBars()
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(scroll)

        val icon = ImageView(this).apply {
            background = rounded(Color.WHITE, 24, border)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setImageDrawable(PrinterIcon(coffee))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        content.addView(icon, LinearLayout.LayoutParams(dp(76), dp(76)))
        content.addView(label("Configurar\nimpresora", 30f, bold = true).apply {
            setLineSpacing(0f, 1.05f)
        }, spaced(24))
        content.addView(label("Elige tu ticketera para imprimir\ndirectamente desde Majo.", 16f, muted).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }, spaced(12))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 24, border)
            setPadding(dp(22), dp(22), dp(22), dp(22))
        }
        content.addView(card, spaced(28))
        val cardHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        cardHeader.addView(label("TU IMPRESORA", 11f, muted, true).apply { letterSpacing = 0.12f },
            LinearLayout.LayoutParams(0, -2, 1f))
        badge = label("Sin seleccionar", 11f, coffee, true).apply {
            background = rounded(softAccent, 10)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        cardHeader.addView(badge)
        card.addView(cardHeader)
        selected = label("Selecciona una ticketera", 21f, coffee, true)
        card.addView(selected, spaced(20))
        selectedHint = label("La selección se guardará en este teléfono.", 14f, muted)
        card.addView(selectedHint, spaced(8))

        selectButton = actionButton("Seleccionar impresora", true) { choosePrinter() }
        content.addView(selectButton, spaced(20, 56))
        content.addView(actionButton("Abrir ajustes Bluetooth", false) {
            try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            catch (_: Exception) { showStatus("No se pudieron abrir los ajustes Bluetooth.") }
        }, spaced(12, 56))

        val notice = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(softAccent, 18)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        notice.addView(label("¿Es la primera vez?", 14f, coffee, true))
        notice.addView(label("Enciende la ticketera y empareja el dispositivo en los ajustes Bluetooth. Luego selecciónalo aquí.", 14f, muted).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
        }, spaced(6))
        content.addView(notice, spaced(24))
        status = label("", 14f, muted).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status, spaced(16))
        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        content.addView(actionButton("Volver a la web", false) { finish() }, spaced(28, 56))
        refreshSelection()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun spaced(top: Int, height: Int? = null) = LinearLayout.LayoutParams(-1, height?.let { dp(it) } ?: -2).apply {
        topMargin = dp(top)
    }
    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun label(value: String, size: Float, color: Int = coffee, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding = false
    }
    private fun actionButton(title: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = title
        textSize = 15f
        isAllCaps = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) cream else coffee)
        gravity = Gravity.CENTER
        minHeight = dp(56)
        minimumHeight = dp(56)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        ViewCompat.setBackgroundTintList(this, null)
        background = RippleDrawable(
            ColorStateList.valueOf(if (primary) Color.argb(40, 255, 255, 255) else border),
            rounded(if (primary) coffee else Color.TRANSPARENT, 18, if (primary) null else border),
            rounded(Color.WHITE, 18)
        )
        stateListAnimator = null
        setOnClickListener { action() }
    }
    private fun showStatus(message: String) { if (::status.isInitialized) status.text = message }

    @SuppressLint("MissingPermission")
    private fun refreshSelection() {
        if (!::selected.isInitialized) return
        val saved = BluetoothTicketPrinter.address(this) != null
        selected.text = if (saved) BluetoothTicketPrinter.selectedName(this) else "Selecciona una ticketera"
        selectedHint.text = if (saved) "Bluetooth · Papel de 58 mm" else "La selección se guardará en este teléfono."
        badge.text = if (saved) "Guardada" else "Sin seleccionar"
        selectButton.text = if (saved) "Cambiar impresora" else "Seleccionar impresora"
        if (!BluetoothTicketPrinter.hasPermission(this)) {
            showStatus("Permite el acceso a Bluetooth al seleccionar tu impresora.")
            return
        }
        try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            when {
                adapter == null -> showStatus("Este teléfono no tiene Bluetooth disponible.")
                !adapter.isEnabled -> showStatus("Bluetooth está apagado. Actívalo para imprimir.")
                !saved -> showStatus("Selecciona un dispositivo emparejado para continuar.")
                else -> showStatus("Selección guardada. Enciende la ticketera antes de imprimir desde la web.")
            }
        } catch (_: SecurityException) {
            showStatus("Se necesita permiso Bluetooth para acceder a la impresora.")
        }
    }

    /** Esta Activity siempre muestra sus barras, aunque la web use modo inmersivo. */
    @Suppress("DEPRECATION")
    private fun showCoffeeBars() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = coffee
        window.navigationBarColor = coffee
        if (Build.VERSION.SDK_INT >= 28) window.navigationBarDividerColor = coffee
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        // Fondo de respaldo para barras transparentes en Android recientes.
        window.decorView.setBackgroundColor(coffee)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onResume() {
        super.onResume()
        showCoffeeBars()
        refreshSelection()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::screenRoot.isInitialized) {
            screenRoot.removeCallbacks(restoreBars)
            screenRoot.post(restoreBars)
        }
    }
    override fun onDestroy() {
        picker?.dismiss()
        if (::screenRoot.isInitialized) {
            screenRoot.removeCallbacks(restoreBars)
            ViewCompat.setOnApplyWindowInsetsListener(screenRoot, null)
        }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun choosePrinter(askToEnable: Boolean = true) {
        if (picker?.isShowing == true) return
        if (!BluetoothTicketPrinter.hasPermission(this)) {
            if (Build.VERSION.SDK_INT >= 31) permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null) { showStatus("Este teléfono no tiene Bluetooth disponible."); return }
            if (!adapter.isEnabled) {
                if (askToEnable) enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                else showStatus("Activa Bluetooth para seleccionar una impresora.")
                return
            }
            val devices = adapter.bondedDevices.filter { it.type != BluetoothDevice.DEVICE_TYPE_LE }
                .sortedBy { it.name ?: it.address }
            if (devices.isEmpty()) {
                showStatus("No hay impresoras emparejadas. Abre los ajustes Bluetooth y empareja tu ticketera.")
                return
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle("Elige tu impresora")
                .setItems(devices.map { "${it.name ?: "Dispositivo Bluetooth"}\n${it.address}" }.toTypedArray()) { _, which ->
                    val device = devices[which]
                    BluetoothTicketPrinter.select(this, device.address, device.name ?: device.address)
                    setResult(RESULT_OK)
                    refreshSelection()
                    showStatus("Impresora guardada. Ya puedes volver a la web.")
                }
                .setNegativeButton("Cancelar", null)
                .create()
            picker = dialog
            dialog.setOnDismissListener { if (picker === dialog) picker = null }
            dialog.show()
            dialog.window?.setBackgroundDrawable(rounded(cream, 24))
            dialog.window?.decorView?.clipToOutline = true
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(coffee)
        } catch (_: SecurityException) {
            showStatus("Falta permiso Bluetooth. Vuelve a seleccionar la impresora.")
        }
    }

    /** Icono vectorial dibujado localmente: no requiere imágenes ni recursos adicionales. */
    private class PrinterIcon(private val ink: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            style = Paint.Style.STROKE
            strokeWidth = 1.8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        override fun draw(canvas: Canvas) {
            val saved = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
            canvas.drawRoundRect(3f, 8f, 21f, 18f, 2f, 2f, paint)
            canvas.drawRect(7f, 3f, 17f, 8f, paint)
            paint.style = Paint.Style.FILL
            canvas.drawRect(7f, 14f, 17f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            paint.style = Paint.Style.STROKE
            canvas.drawRect(7f, 14f, 17f, 22f, paint)
            canvas.drawLine(10f, 17f, 14f, 17f, paint)
            canvas.drawLine(10f, 19f, 14f, 19f, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(17.5f, 11.5f, 0.8f, paint)
            paint.style = Paint.Style.STROKE
            canvas.restoreToCount(saved)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Suppress("DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
