package com.majocafe.ticketera

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer

data class TicketItem(val name: String, val quantity: Int, val price: BigDecimal,
    val changedQuantity: Int = 0, val changedPrice: BigDecimal? = null, val notes: String = "")
data class TicketOrder(val id: Long, val date: String, val total: BigDecimal, val discount: BigDecimal,
    val location: String, val payment: String, val receipt: String, val customer: String,
    val document: String, val notes: String, val items: List<TicketItem>)

object ReceiptFormatter {
    private const val WIDTH = 32
    fun money(amount: BigDecimal): String = amount.setScale(2, RoundingMode.HALF_UP).toPlainString()
    fun format(order: TicketOrder): String = buildString {
        appendLine("MAJO CAFETERIA".padStart(23))
        appendLine("PEDIDO #${order.id}")
        field("Fecha", order.date)
        field("Lugar", order.location)
        field("Pago", order.payment)
        field("Boleta", order.receipt)
        field("Cliente", order.customer)
        field("Documento", order.document)
        appendLine("-".repeat(WIDTH))
        order.items.forEach { item ->
            require(item.quantity in 1..10000 && item.changedQuantity in 0..item.quantity)
            require(item.price.signum() >= 0)
            val regular = item.quantity - item.changedQuantity
            if (regular > 0) product(item.name, regular, item.price)
            if (item.changedQuantity > 0) {
                val price = requireNotNull(item.changedPrice) { "Falta precio_modificado" }
                require(price.signum() >= 0)
                product(item.name + " (mod.)", item.changedQuantity, price)
            }
            field("Nota", item.notes)
        }
        appendLine("-".repeat(WIDTH))
        columns("Descuento", "S/ ${money(order.discount)}")
        columns("TOTAL", "S/ ${money(order.total)}")
        field("Notas", order.notes)
        appendLine("-".repeat(WIDTH))
        appendLine("Gracias por su visita".padStart(26))
    }

    // Solo texto imprimible: nunca deja pasar comandos ESC/POS del JSON.
    private fun clean(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").map { if (it.code in 32..126) it else ' ' }.joinToString("")
        .replace(Regex(" +"), " ").trim()
    private fun StringBuilder.wrap(value: String) {
        var text = clean(value)
        while (text.length > WIDTH) {
            val space = text.lastIndexOf(' ', WIDTH).takeIf { it > 0 } ?: WIDTH
            appendLine(text.substring(0, space))
            text = text.substring(space).trimStart()
        }
        if (text.isNotEmpty()) appendLine(text)
    }
    private fun StringBuilder.field(label: String, value: String) {
        if (value.isNotBlank()) wrap("$label: $value")
    }
    private fun StringBuilder.columns(left: String, right: String) {
        val a = clean(left); val b = clean(right)
        if (a.length + b.length + 1 > WIDTH) { wrap(a); wrap(b) }
        else appendLine(a + " ".repeat(WIDTH - a.length - b.length) + b)
    }
    private fun StringBuilder.product(name: String, quantity: Int, price: BigDecimal) {
        wrap("$quantity $name")
        columns("  $quantity x ${money(price)}", money(price.multiply(quantity.toBigDecimal())))
    }
}
