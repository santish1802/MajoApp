package com.majocafe.ticketera

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class PrintResult(val ok: Boolean, val code: String, val message: String)

object BluetoothTicketPrinter {
    private val busy = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    fun hasPermission(context: Context) = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    fun address(context: Context) = context.getSharedPreferences("printer", Context.MODE_PRIVATE).getString("address", null)
    fun selectedName(context: Context) = context.getSharedPreferences("printer", Context.MODE_PRIVATE).getString("name", "Sin seleccionar")
    fun select(context: Context, address: String, name: String) {
        require(BluetoothAdapter.checkBluetoothAddress(address))
        context.getSharedPreferences("printer", Context.MODE_PRIVATE).edit()
            .putString("address", address).putString("name", name).apply()
    }

    @SuppressLint("MissingPermission")
    fun print(context: Context, text: String, callback: (PrintResult) -> Unit) {
        val app = context.applicationContext
        fun reply(result: PrintResult) { main.post { callback(result) } }
        if (!hasPermission(app)) { reply(PrintResult(false, "permiso", "Concede permiso Bluetooth desde la pantalla de impresoras.")); return }
        val mac = address(app)
        if (mac == null) { reply(PrintResult(false, "sin_impresora", "Selecciona primero una ticketera.")); return }
        if (text.length > 64000) { reply(PrintResult(false, "tamano", "El ticket es demasiado largo.")); return }
        if (!busy.compareAndSet(false, true)) { reply(PrintResult(false, "ocupada", "Hay otra impresión en curso.")); return }
        worker.execute {
            val result = try {
                val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
                    ?: throw IllegalStateException("Este dispositivo no tiene Bluetooth.")
                check(adapter.isEnabled) { "Activa Bluetooth y vuelve a intentarlo." }
                val device = adapter.bondedDevices.firstOrNull { it.address == mac }
                    ?: throw IllegalStateException("La ticketera ya no está emparejada. Selecciónala de nuevo.")
                device.createRfcommSocketToServiceRecord(spp).use { socket ->
                    val timedOut = AtomicBoolean(false)
                    val watchdog = timer.schedule({
                        timedOut.set(true)
                        try { socket.close() } catch (_: Exception) { }
                    }, 15, TimeUnit.SECONDS)
                    try {
                        socket.connect()
                        val bytes = ByteArrayOutputStream().apply {
                            write(byteArrayOf(0x1B, 0x40)) // ESC @: inicializar
                            write(byteArrayOf(0x1B, 0x4D, 0x00)) // Fuente A
                            write(byteArrayOf(0x1B, 0x61, 0x00)) // Alinear izquierda
                            write(text.toByteArray(Charsets.US_ASCII))
                            write(byteArrayOf(0x0A, 0x0A, 0x0A)) // Avance, sin corte automático
                        }.toByteArray()
                        socket.outputStream.write(bytes)
                        socket.outputStream.flush()
                        check(!timedOut.get()) { "La comunicación con la ticketera agotó el tiempo." }
                    } finally { watchdog.cancel(false) }
                }
                PrintResult(true, "enviado", "Ticket enviado a la impresora.")
            } catch (error: Exception) {
                PrintResult(false, "conexion", error.message ?: "No se pudo comunicar con la ticketera.")
            }
            busy.set(false)
            reply(result)
        }
    }
}
