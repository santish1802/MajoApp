package com.majocafe.ticketera

import org.json.JSONObject
import java.math.BigDecimal

object PedidoParser {
    fun parse(json: String): TicketOrder {
        require(json.length <= 131072) { "El pedido es demasiado grande" }
        return parse(JSONObject(json))
    }
    fun parse(p: JSONObject): TicketOrder {
        val items = p.getJSONArray("items")
        require(items.length() in 1..100) { "Se requieren entre 1 y 100 productos" }
        val id = p.getLong("id")
        require(id > 0) { "ID de pedido inválido" }
        return TicketOrder(id, text(p, "fecha_pedido"), amount(p, "total"),
            if (p.isNull("descuento")) BigDecimal.ZERO else amount(p, "descuento"),
            text(p, "ubicacion"), text(p, "metodo_pago"), text(p, "boleta_numero"),
            text(p, "boleta_cliente_nombre").ifEmpty { text(p, "cliente_nombre") },
            text(p, "boleta_cliente_documento").ifEmpty { text(p, "cliente_dni") },
            text(p, "notas"), (0 until items.length()).map {
                val item = items.getJSONObject(it)
                val quantity = item.getInt("cantidad")
                val changed = item.optInt("cantidad_modificada", 0)
                require(quantity in 1..10000 && changed in 0..quantity) { "Cantidad inválida" }
                TicketItem(text(item, "nombre").also { require(it.isNotBlank()) { "Falta nombre" } },
                    quantity, amount(item, "precio_unitario"), changed,
                    if (changed > 0) amount(item, "precio_modificado") else null, text(item, "notas_item"))
            })
    }
    private fun text(p: JSONObject, key: String): String {
        if (p.isNull(key)) return ""
        return p.optString(key, "").also { require(it.length <= 500) { "Texto demasiado largo: $key" } }
    }
    private fun amount(p: JSONObject, key: String): BigDecimal {
        require(!p.isNull(key)) { "Falta $key" }
        val raw = p.get(key).toString()
        require(Regex("[0-9]{1,9}(\\.[0-9]{1,2})?").matches(raw)) { "Importe inválido: $key" }
        return raw.toBigDecimal()
    }
}
