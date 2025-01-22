package com.example.demo

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*


fun Double.formatWithSpace(): String {
    val symbols = DecimalFormatSymbols(Locale.getDefault()).apply {
        groupingSeparator = ' ' // Space for thousand separator
        decimalSeparator = ','  // Comma for decimal separator
    }
    val formatter = DecimalFormat("#,##0.00", symbols)
    return formatter.format(this)
}

