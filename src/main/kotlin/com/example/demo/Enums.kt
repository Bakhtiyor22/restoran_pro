package com.example.demo

enum class Roles {
    CUSTOMER,
    MANAGER,
    DEV
}

enum class ErrorCode(val code: Int) {
    GENERAL_ERROR(-1),
    USER_NOT_FOUND(-2),
}