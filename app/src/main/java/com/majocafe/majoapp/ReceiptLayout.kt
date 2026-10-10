package com.majocafe.majoapp

/** Modelo independiente de Android: ancho fijo, saltos de línea y datos del servidor. */
data class ReceiptRow(val text: String = "", val center: Boolean = false,
    val bold: Boolean = false, val italic: Boolean = false, val graphic: String = "")

object ReceiptLayout {
    const val COLUMNS = 32
    fun rows(order: TicketOrder): List<ReceiptRow> = buildList {
        fun clean(s: String) = s.map { if (it.isISOControl()) ' ' else it }.joinToString("")
            .replace(Regex(" +"), " ").trim()
        fun line(value: String, center: Boolean = false, bold: Boolean = false, italic: Boolean = false) {
            var text = clean(value)
            if (text.isEmpty()) { add(ReceiptRow()); return }
            while (text.length > COLUMNS) {
                val end = text.lastIndexOf(' ', COLUMNS).takeIf { it > 0 } ?: COLUMNS
                add(ReceiptRow(text.substring(0, end), center, bold, italic))
                text = text.substring(end).trimStart()
            }
            if (text.isNotEmpty()) add(ReceiptRow(text, center, bold, italic))
        }
        fun columns(left: String, right: String, bold: Boolean = false) {
            val a = clean(left); val b = clean(right)
            if (a.length + b.length + 1 > COLUMNS) { line(a, bold = bold); line(b, bold = bold) }
            else add(ReceiptRow(a + " ".repeat(COLUMNS - a.length - b.length) + b, bold = bold))
        }
        fun separator(spaced: Boolean = true) = line(if (spaced) "- ".repeat(16).trimEnd() else "-".repeat(32))
        fun product(name: String, quantity: Int, price: java.math.BigDecimal) {
            line("$quantity $name")
            val unit = ReceiptFormatter.money(price).padStart(17)
            val subtotal = ReceiptFormatter.money(price.multiply(quantity.toBigDecimal())).padStart(15)
            if (unit.length + subtotal.length <= COLUMNS) add(ReceiptRow(unit + subtotal))
            else columns(ReceiptFormatter.money(price), ReceiptFormatter.money(price.multiply(quantity.toBigDecimal())))
        }
        val info = order.receiptDetails
        add(ReceiptRow(graphic = "logo"))
        line("CAFETERÍA", center = true, bold = true)
        line("")
        line("RUC: 10450499710", center = true)
        line("Av. Costa Negra 291, Chorrillos", center = true)
        line("Lima - Lima - Chorrillos", center = true)
        separator(false)
        val boleta = order.receipt.isNotBlank()
        line(if (boleta) "BOLETA DE VENTA ELECTRÓNICA" else "TICKET DE PEDIDO", center = true)
        line(if (boleta) order.receipt else "PEDIDO #${order.id}", center = true, bold = true)
        line("")
        val date = info.emissionDate.ifEmpty { order.date.substringBefore(' ') }
        val formattedDate = if (Regex("\\d{4}-\\d{2}-\\d{2}").matches(date))
            "${date.substring(8, 10)}/${date.substring(5, 7)}/${date.substring(0, 4)}" else date
        val time = info.emissionTime.ifEmpty { order.date.substringAfter(' ', "") }.take(5)
        line("Emisión: $formattedDate  $time")
        if (order.customer.isNotBlank()) { line("Cliente:"); line(order.customer) }
        if (order.document.isNotBlank()) line("Documento: ${order.document}")
        separator()
        add(ReceiptRow("          P.UNIT.        IMPORTE", bold = true))
        separator(false)
        order.items.forEach { item ->
            val regular = item.quantity - item.changedQuantity
            if (regular > 0) product(item.name, regular, item.price)
            if (item.changedQuantity > 0) product(item.name + " (mod.)", item.changedQuantity,
                requireNotNull(item.changedPrice) { "Falta precio_modificado" })
        }
        line("")
        if (info.includesTax) line("Precios incluyen impuestos.")
        separator()
        info.taxable?.let { columns("Op. gravadas:", "S/ ${ReceiptFormatter.money(it)}") }
        info.tax?.let { columns("IGV (18%):", "S/ ${ReceiptFormatter.money(it)}") }
        if (order.discount.signum() > 0) columns("Descuento:", "S/ ${ReceiptFormatter.money(order.discount)}")
        separator(false)
        columns("TOTAL:", "S/ ${ReceiptFormatter.money(order.total)}", bold = true)
        separator(false)
        line("SON: " + info.amountWords.ifEmpty { AmountWords.format(order.total) })
        separator()
        if (order.payment.isNotBlank()) columns("Medio de pago:", order.payment)
        if (order.notes.isNotBlank()) line("Notas: ${order.notes}", italic = true)
        separator()
        if (info.qr.isNotBlank()) {
            add(ReceiptRow(graphic = "qr"))
            line("Escanea para ver tu boleta", center = true)
            separator()
        }
        if (boleta) {
            line("Representación impresa de la", center = true)
            line("boleta de venta electrónica.", center = true)
            line("")
        }
        line("Un café, un buen día.", center = true, italic = true)
        line("¡Gracias por tu visita!", center = true, bold = true)
        line("")
    }
}
