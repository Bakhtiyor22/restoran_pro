package com.example.demo

data class AuthDto(val phoneNumber: String)

data class UserDto(
    val id: Long?,
    val phoneNumber: String,
    val role: String,
    val addresses: List<AddressDto>
)

data class AddressDto(
    val id: Long?,
    val addressLine: String,
    val city: String,
    val state: String,
    val postalCode: String,
    val longitude: Float,
    val latitude: Float
)