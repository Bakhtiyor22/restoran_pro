package com.example.demo

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.data.domain.PageRequest
import java.math.BigDecimal
import java.time.LocalDateTime

data class BaseMessage(
    val code: Int,
    val message: String?,
){
    companion object {
         val  OK = BaseMessage(200, "Success")
    }
}

data class OtpRequest(
    @field:Pattern(regexp = "^\\+998\\d{9}$", message = "Invalid phone number format")
    val phoneNumber: String = ""
)

data class OtpLogin(val phoneNumber: String, val otp: String, val otpId: Long)

data class OtpIdResponse(val smsCodeId: Long, val message: String?)


data class LoginRequest(
    @Pattern(regexp = "^\\+998\\d{9}$", message = "Invalid phone number format")
    val phoneNumber: String,
    @NotBlank val password: String
)

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String = "",
    val expired: Int // second
)

data class CreateRestaurantRequest(
    @NotBlank
    @Size(max = 100)
    val name: String,

    @NotBlank
    @Size(max = 20)
    val contact: String,

    @field:NotBlank
    @field:Size(max = 200)
    val location: String
)

data class CreateUserRequest(
    val username: String,
    @Pattern(regexp = "^\\+998\\d{9}$", message = "Notug'ri raqam")
    val phoneNumber: String,
    @NotBlank val password: String,
    @NotBlank val role: Roles,
)

data class UpdateUserRequest(
    @Size(min = 3, max = 50)
    val username: String,

    @Pattern(regexp = "^\\+998\\d{9}$", message = "Notug'ri raqam")
    val phoneNumber: String,
    @NotBlank val password: String
)

data class UserDTO(
    val id: Long,
    @Size(min = 3, max = 50, message = "Username must be 3-50 characters")
    val username: String,
    @Pattern(regexp = "^\\+998\\d{9}$", message = "Invalid phone number format")
    val phoneNumber: String,
    val role: Roles,
    val address: List<AddressDTO> = emptyList()
)

data class AddressDTO(
    val id: Long?,
    val addressLine: String,
    val city: String,
    val state: String,
    val postalCode: String,
    val longitude: Float,
    val latitude: Float
)

data class AddressRequest(
    val addressLine: String,
    val city: String,
    val state: String,
    val postalCode: String,
    val longitude: Float,
    val latitude: Float
)

data class UpdateOrderStatusRequest(
    val newStatus: OrderStatus
)

fun User.toDto() = UserDTO(
    id = this.id!!,
    username = this.username,
    phoneNumber = this.phoneNumber,
    role = this.role,
)

fun Address.toDto() = AddressDTO(
    id = this.id,
    addressLine = this.addressLine,
    city = this.city,
    state = this.state,
    postalCode = this.postalCode,
    longitude = this.longitude,
    latitude = this.latitude
)

data class CreateMenuRequest(
    val name: String,
    val description: String?,
    val category: MenuCategory,
    val restaurantId: Long
)

data class AddMenuItem(
    val name: String,
    val price: BigDecimal,
    val description: String?
)

data class MenuDTO(
    val id: Long,
    val name: String,
    val description: String?,
    val category: MenuCategory,
    val restaurantId: Long,
    val menuItems: List<MenuItemDTO>
)

data class MenuItemDTO(
    val id: Long,
    val name: String,
    val price: BigDecimal,
    val description: String?
)

fun MenuItem.toDto() = MenuItemDTO(
    id = this.id!!,
    name = this.name,
    price = this.price,
    description = this.description
)

fun Menu.toDto() = MenuDTO(
    id = this.id!!,
    name = this.name,
    description = this.description,
    category = this.category,
    restaurantId = this.restaurant.id!!,
    menuItems = this.menuItems.map { it.toDto() }
)

data class OrderItemRequest(
    val menuItemId: Long,
    val quantity: Int
)

data class CreateOrderRequest(
    val cartId: Long? = null,  // Optional - if null, direct order
    val items: List<OrderItemRequest> = emptyList(),  // Used for direct orders
    val paymentOption: PaymentOption
)

data class OrderDTO(
    val id: Long?,
    val customerId: Long,
    val restaurantId: Long?,
    val totalAmount: BigDecimal,
    val paymentOption: PaymentOption,
    val status: OrderStatus,
    val orderDate: LocalDateTime,
    val orderItems: List<OrderItemDTO>
)

data class OrderItemDTO(
    val id: Long?,
    val orderId: Long?,
    val menuItemId: Long?,
    val quantity: Int,
    val price: BigDecimal
)

data class OrderItemTemp(
    val menuItem: MenuItem,
    val quantity: Int,
    val price: BigDecimal
)

fun Order.toDto() = OrderDTO(
    id = this.id,
    customerId = this.customerId,
    restaurantId = this.restaurant.id,
    totalAmount = this.totalAmount,
    paymentOption = this.paymentOption,
    status = this.status,
    orderDate = this.orderDate,
    orderItems = this.orderItems.map { it.toDto() }
)

fun OrderItem.toDto() = OrderItemDTO(
    id = this.id,
    orderId = this.order.id,
    menuItemId = this.menuItem.id,
    quantity = this.quantity,
    price = this.price
)

data class PaymentRequest(
    val amount: BigDecimal,
    val paymentOption: PaymentOption
)

data class PaymentTransactionDTO(
    val id: Long,
    val transactionId: String,
    val userId: Long,
    val amount: BigDecimal,
    val paymentOption: PaymentOption,
    val paymentStatus: PaymentStatus,
    val transactionTime: LocalDateTime
)

fun PaymentTransaction.toDto(): PaymentTransactionDTO = PaymentTransactionDTO(
    id = this.id!!,
    transactionId = this.transactionId,
    userId = this.userId,
    amount = this.amount,
    paymentOption = this.paymentOption,
    paymentStatus = this.paymentStatus,
    transactionTime = this.transactionTime
)

data class CardDTO(
    val id: Long?,
    val cardNumber: String,
    val expiryDate: String,
    val cardHolderName: String,
    val cardType: CardType,
    val balance: BigDecimal,
    val isDefault: Boolean
)

data class AddCardRequest(
    val cardNumber: String,
    val expiryDate: String,
    val cardHolderName: String,
    val cardType: CardType,
    val balance: BigDecimal
)

fun Card.toDto() = CardDTO(
    id = this.id,
    cardNumber = this.cardNumber.takeLast(4).padStart(16, '*'),
    expiryDate = this.expiryDate,
    cardHolderName = this.cardHolderName,
    cardType = this.cardType,
    balance = this.balance,
    isDefault = this.isDefault
)

data class AddToCartRequest(
    val menuItemId: Long,
    val quantity: Int
)

data class CartItemDTO(
    val id: Long?,
    val menuItemId: Long,
    val name: String,
    val price: BigDecimal,
    val quantity: Int,
    val subtotal: BigDecimal
)

data class CartDTO(
    val id: Long?,
    val customerId: Long,
    val restaurantId: Long,
    val items: List<CartItemDTO>,
    val subtotal: BigDecimal,
    val serviceCharge: BigDecimal,
    val deliveryFee: BigDecimal,
    val discount: BigDecimal,
    val total: BigDecimal
)


data class UpdateCardBalanceRequest(
    @DecimalMin(value = "0.0", message = "Balans 0 dan katta bo'lishi kerak")
    val amount: BigDecimal
)

