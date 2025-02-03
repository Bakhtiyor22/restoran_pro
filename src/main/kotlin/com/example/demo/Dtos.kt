package com.example.demo

data class BaseMessage(
    private val code: Int,
    private val message: String?,
)

data class OtpRequest(val phoneNumber: String = "")

data class OtpLogin(val phoneNumber: String = "", val otp : String)

data class  OtpIdResponse(val smsCodeId: Long, val message: String?)

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


data class LoginRequest(
    val phone:String,
    val password:String
)

data class TokenResponse(
    val accessToken:String,
    val refreshToken:String = "",
    val expired:Int // second
)