package com.example.demo

import org.springframework.web.bind.annotation.*
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService
) {

    @PostMapping("/otp-login")
    fun otpLogin(
        @RequestParam username: String,
        @RequestParam phoneNumber: String,
        @RequestParam otp: String
    ): String {
        return authService.otpLogin(username, phoneNumber, otp)
    }

    @PostMapping("/login")
    fun loginWithPassword(
        @RequestParam phoneNumber: String,
        @RequestParam password: String
    ): String {
        return authService.loginWithPassword(phoneNumber, password)
    }
}
