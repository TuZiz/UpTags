package cn.aing.uptags.util

import java.text.DecimalFormat

object Formatters {
    private val decimalFormat = DecimalFormat("0.##")
    private val roman = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")

    fun formatDouble(value: Double): String = decimalFormat.format(value)

    fun roman(value: Int): String = when {
        value <= 0 -> "0"
        value <= roman.size -> roman[value - 1]
        else -> value.toString()
    }

    fun joinDisplay(values: Collection<String>, delimiter: String = ", "): String = if (values.isEmpty()) "无" else values.joinToString(delimiter)

    fun boolText(value: Boolean): String = if (value) "&a是" else "&c否"
}
