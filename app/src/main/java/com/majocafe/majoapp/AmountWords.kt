package com.majocafe.majoapp

import java.math.BigDecimal
import java.math.RoundingMode

object AmountWords {
    fun format(amount: BigDecimal): String {
        val rounded = amount.setScale(2, RoundingMode.HALF_UP)
        val whole = rounded.toBigInteger().toLong()
        require(whole in 0..999999999)
        val cents = rounded.remainder(BigDecimal.ONE).movePointRight(2).toInt()
        return "${number(whole.toInt())} CON ${cents.toString().padStart(2, '0')}/100 SOLES"
    }
    private fun number(n: Int): String {
        val small = listOf("CERO", "UNO", "DOS", "TRES", "CUATRO", "CINCO", "SEIS", "SIETE",
            "OCHO", "NUEVE", "DIEZ", "ONCE", "DOCE", "TRECE", "CATORCE", "QUINCE",
            "DIECISÉIS", "DIECISIETE", "DIECIOCHO", "DIECINUEVE", "VEINTE", "VEINTIUNO",
            "VEINTIDÓS", "VEINTITRÉS", "VEINTICUATRO", "VEINTICINCO", "VEINTISÉIS",
            "VEINTISIETE", "VEINTIOCHO", "VEINTINUEVE")
        if (n < 30) return small[n]
        if (n < 100) {
            val tens = listOf("", "", "", "TREINTA", "CUARENTA", "CINCUENTA", "SESENTA", "SETENTA", "OCHENTA", "NOVENTA")
            return tens[n / 10] + if (n % 10 == 0) "" else " Y ${number(n % 10)}"
        }
        if (n == 100) return "CIEN"
        if (n < 1000) {
            val hundreds = listOf("", "CIENTO", "DOSCIENTOS", "TRESCIENTOS", "CUATROCIENTOS",
                "QUINIENTOS", "SEISCIENTOS", "SETECIENTOS", "OCHOCIENTOS", "NOVECIENTOS")
            return hundreds[n / 100] + if (n % 100 == 0) "" else " ${number(n % 100)}"
        }
        if (n < 1000000) return (if (n / 1000 == 1) "MIL" else "${apocopate(number(n / 1000))} MIL") +
            if (n % 1000 == 0) "" else " ${number(n % 1000)}"
        return (if (n / 1000000 == 1) "UN MILLÓN" else "${apocopate(number(n / 1000000))} MILLONES") +
            if (n % 1000000 == 0) "" else " ${number(n % 1000000)}"
    }
    private fun apocopate(s: String) = if (s.endsWith("VEINTIUNO")) s.dropLast(9) + "VEINTIÚN"
        else if (s.endsWith("UNO")) s.dropLast(3) + "UN" else s
}
