package com.example.demo

object Utils {
    fun validatePhoneNumber(phoneNumber: String): Boolean =
        phoneNumber.matches(Regex("^\\+998[0-9]{9}$"))
}