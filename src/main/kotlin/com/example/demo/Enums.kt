package com.example.demo

import java.util.*

enum class Roles {
    CUSTOMER,
    MANAGER,
    DEV,
    EMPLOYEE
}

enum class PaymentOption {
    CASH, CARD
}

enum class PaymentStatus {
    PENDING,
    SUCCESS,
    FAILED
}

enum class OrderStatus {
    ACCEPTED, PENDING, IN_PROGRESS, CANCELLED, COMPLETED, REFUNDED, READY, REJECTED
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
    OTP_REQUEST,
    NEGATIVE_PRICE
}

enum class PaginationFieldName {
    PAGE,
    LIMIT
}

data class CardTypePattern(
    val pattern: Regex,
    val length: Int
)

enum class CardType(val pattern: Regex) {
    HUMO(Regex("^9860[0-9]{12}$")),
    UZCARD(Regex("^8600[0-9]{12}$")),
    VISA(Regex("^4[0-9]{12}(?:[0-9]{3})?$")),
    MASTERCARD(Regex("^5[1-5][0-9]{14}$"));
}