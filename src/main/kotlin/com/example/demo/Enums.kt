package com.example.demo

enum class Roles {
    CUSTOMER,
    MANAGER,
    DEV
}

enum class ErrorCode(val code: Int) {
    GENERAL_ERROR(-1),
    USER_NOT_FOUND(-2),
    DUPLICATE_RESOURCE(-3),
    FORBIDDEN(-4),
    INVALID_INPUT(-5),
    RESOURCE_NOT_FOUND(-7),
    VALIDATION_ERROR(-8)
}

enum class UserStatus {
    EXISTS, CREATED, NOT_FOUND
}

enum class MessageKey {
    USER_NOT_FOUND,
}