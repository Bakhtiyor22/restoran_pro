package com.example.demo

fun validatePhoneNumber(phoneNumber: String): Boolean = phoneNumber.matches(Regex("^\\+998[0-9]{9}$"))

