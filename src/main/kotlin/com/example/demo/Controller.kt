package com.example.demo

import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService
) {

    @PostMapping("/otp-login")
    fun otpLogin(@RequestParam phoneNumber: String, @RequestParam otp: String): String {
        return authService.otpLogin(phoneNumber, otp)
    }

    @PostMapping("/request-otp")
    fun requestOtp(@RequestParam phoneNumber: String): String {
        return authService.requestOtp(phoneNumber)
    }

    @PostMapping("/login")
    fun login(@RequestParam phoneNumber: String, @RequestParam password: String): String {
        return authService.login(phoneNumber, password)
    }
}