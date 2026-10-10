package com.majocafe.majoapp

import java.math.BigDecimal
import java.math.RoundingMode

data class TicketItem(val name: String, val quantity: Int, val price: BigDecimal,
    val changedQuantity: Int = 0, val changedPrice: BigDecimal? = null, val notes: String = "")
data class TicketOrder(val id: Long, val date: String, val total: BigDecimal, val discount: BigDecimal,
    val location: String, val payment: String, val receipt: String, val customer: String,
    val document: String, val notes: String, val items: List<TicketItem>,
    val receiptDetails: ReceiptDetails = ReceiptDetails())

data class ReceiptDetails(
    val emissionDate: String = "", val emissionTime: String = "", val address: String = "",
    val taxable: BigDecimal? = null, val tax: BigDecimal? = null,
    val amountWords: String = "", val qr: String = "",
    val includesTax: Boolean = false)

object ReceiptFormatter {
    fun money(amount: BigDecimal): String = amount.setScale(2, RoundingMode.HALF_UP).toPlainString()
}
