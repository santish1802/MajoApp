package com.majocafe.majoapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.math.ceil
import java.io.ByteArrayOutputStream

object ReceiptImage {
    private const val WIDTH = 384
    private const val LINE_HEIGHT = 24
    fun bytes(context: Context, order: TicketOrder): ByteArray {
        val bitmap = render(context, order)
        return try { raster(bitmap) } finally { bitmap.recycle() }
    }
    fun render(context: Context, order: TicketOrder): Bitmap {
        val rows = ReceiptLayout.rows(order)
        val logo = requireNotNull(
            ContextCompat.getDrawable(context, R.drawable.logo_ticket)
        ).mutate()

        val anchoLogo = 350 // Cambia este ancho, máximo WIDTH
        val altoLogo = (
                anchoLogo.toFloat() * logo.intrinsicHeight / logo.intrinsicWidth
                ).toInt().coerceAtLeast(1)
        val height = rows.sumOf { when (it.graphic) { "logo" -> altoLogo; "qr" -> 304; else -> LINE_HEIGHT } }
        require(height in 1..24000) { "El ticket es demasiado largo para imprimir como imagen." }
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
            var y = 0
            rows.forEach { row ->
                when (row.graphic) {
                    "logo" -> {
                        val izquierda = (WIDTH - anchoLogo) / 2

                        logo.setBounds(
                            izquierda,
                            y,
                            izquierda + anchoLogo,
                            y + altoLogo
                        )
                        logo.draw(canvas)
                        y += altoLogo
                    }
                    "qr" -> {
                        val matrix = QRCodeWriter().encode(order.receiptDetails.qr, BarcodeFormat.QR_CODE, 0, 0,
                            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 4))
                        val scale = 288 / matrix.width
                        require(scale >= 2) { "Contenido QR demasiado largo. Envía una URL corta de la boleta." }
                        val left = (WIDTH - matrix.width * scale) / 2
                        val top = y + (304 - matrix.height * scale) / 2
                        val qrPaint = Paint().apply { color = Color.BLACK; isAntiAlias = false }
                        for (cy in 0 until matrix.height) for (cx in 0 until matrix.width) {
                            if (matrix[cx, cy]) canvas.drawRect((left + cx * scale).toFloat(),
                                (top + cy * scale).toFloat(), (left + (cx + 1) * scale).toFloat(),
                                (top + (cy + 1) * scale).toFloat(), qrPaint)
                        }
                        y += 304
                    }
                    else -> {
                        paint.typeface = Typeface.create(Typeface.MONOSPACE, when {
                            row.bold -> Typeface.BOLD
                            row.italic -> Typeface.ITALIC
                            else -> Typeface.NORMAL
                        })
                        paint.textSize = 20f
                        val measured = paint.measureText(row.text)
                        if (measured > WIDTH - 2) paint.textSize *= (WIDTH - 2) / measured
                        paint.textAlign = if (row.center) Paint.Align.CENTER else Paint.Align.LEFT
                        val x = if (row.center) WIDTH / 2f else 0f
                        canvas.drawText(row.text, x, y + ceil(-paint.fontMetrics.ascent).toFloat(), paint)
                        y += LINE_HEIGHT
                    }
                }
            }
            return bitmap
        } catch (error: Exception) {
            bitmap.recycle()
            throw error
        }
    }
    // GS v 0, bloques de 48 filas para limitar el búfer de la ticketera.
    private fun raster(bitmap: Bitmap): ByteArray {
        val rowBytes = (bitmap.width + 7) / 8
        return ByteArrayOutputStream().apply {
            var top = 0
            while (top < bitmap.height) {
                val height = minOf(48, bitmap.height - top)
                write(byteArrayOf(0x1D, 0x76, 0x30, 0x00,
                    (rowBytes and 255).toByte(), (rowBytes shr 8).toByte(),
                    (height and 255).toByte(), (height shr 8).toByte()))
                for (y in top until top + height) {
                    for (column in 0 until rowBytes) {
                        var bits = 0
                        for (bit in 0..7) {
                            val x = column * 8 + bit
                            if (x < bitmap.width) {
                                val pixel = bitmap.getPixel(x, y)
                                val luminance = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                                if (luminance < 160) bits = bits or (0x80 shr bit)
                            }
                        }
                        write(bits)
                    }
                }
                top += height
            }
        }.toByteArray()
    }
}
