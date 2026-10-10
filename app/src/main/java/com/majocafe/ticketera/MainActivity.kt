package com.majocafe.ticketera

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.graphics.Typeface
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var selected: TextView
    private lateinit var preview: TextView
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) choosePrinter() else status.text = "Se necesita permiso para conectarse a tu ticketera."
    }
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        choosePrinter(askToEnable = false)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(scroll)
        fun label(text: String, size: Float = 16f) = TextView(this).apply { this.text = text; textSize = size; content.addView(this) }
        fun button(text: String, action: () -> Unit) {
            content.addView(Button(this).apply { this.text = text; setOnClickListener { action() } })
        }
        label("MajoTicket · 58 mm", 25f)
        label("Empareja tu ticketera en los ajustes de Bluetooth, selecciónala e imprime una prueba.")
        selected = label("")
        status = label("Listo.")
        button("Ajustes Bluetooth") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        button("Seleccionar ticketera") { choosePrinter() }
        button("Imprimir prueba") {
            send("         MAJO CAFETERIA\n       PRUEBA DE IMPRESION\n--------------------------------\nTicketera Bluetooth 58 mm\nConexion correcta\n--------------------------------\n")
        }
        button("Imprimir pedido de ejemplo") {
            try {
                val json = assets.open("pedido-ejemplo.json").bufferedReader().use { it.readText() }
                send(ReceiptFormatter.format(PedidoParser.parse(json)))
            } catch (error: Exception) { status.text = error.message ?: "Pedido inválido." }
        }
        button("Abrir majocafeteria.com") { startActivity(Intent(this, WebActivity::class.java)) }
        label("Vista previa del último ticket", 18f)
        preview = label("").apply { typeface = Typeface.MONOSPACE; textSize = 13f }
    }
    override fun onResume() {
        super.onResume()
        if (::selected.isInitialized) selected.text = "Ticketera: ${BluetoothTicketPrinter.selectedName(this)}"
    }

    @SuppressLint("MissingPermission")
    private fun choosePrinter(askToEnable: Boolean = true) {
        if (!BluetoothTicketPrinter.hasPermission(this)) {
            if (Build.VERSION.SDK_INT >= 31) permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null) { status.text = "Este teléfono no tiene Bluetooth."; return }
            if (!adapter.isEnabled) {
                if (askToEnable) enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                else status.text = "Activa Bluetooth para seleccionar una ticketera."
                return
            }
            val devices = adapter.bondedDevices.filter { it.type != BluetoothDevice.DEVICE_TYPE_LE }
                .sortedBy { it.name ?: it.address }
            if (devices.isEmpty()) { status.text = "No hay dispositivos clásicos emparejados. Empareja la ticketera en Ajustes Bluetooth."; return }
            AlertDialog.Builder(this).setTitle("Selecciona tu ticketera")
                .setItems(devices.map { "${it.name ?: "Dispositivo"}\n${it.address}" }.toTypedArray()) { _, which ->
                    val device = devices[which]
                    BluetoothTicketPrinter.select(this, device.address, device.name ?: device.address)
                    selected.text = "Ticketera: ${BluetoothTicketPrinter.selectedName(this)}"
                    status.text = "Guardada. Ahora puedes imprimir una prueba."
                }.setNegativeButton("Cancelar", null).show()
        } catch (_: SecurityException) { status.text = "Falta permiso Bluetooth. Vuelve a seleccionar la ticketera." }
    }
    private fun send(text: String) {
        preview.text = text
        status.text = "Enviando..."
        BluetoothTicketPrinter.print(this, text) {
            if (!isDestroyed && !isFinishing) status.text = it.message
        }
    }
}
