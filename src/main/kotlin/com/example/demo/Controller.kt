package com.example.demo

import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService
) {

    @PostMapping("/otp-login")
    fun otpLogin(@RequestBody otpLogin: OtpLogin)= authService.otpLogin(otpLogin)


    @PostMapping("/request-otp")
    fun requestOtp(@RequestBody request: OtpRequest)= authService.requestOtp(request.phoneNumber)


    @PostMapping("/login")
    fun login(@RequestBody request:LoginRequest) = authService.login(request)

}