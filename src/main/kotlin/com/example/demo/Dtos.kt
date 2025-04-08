package com.example.demo

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*

data class BaseMessage(
    val code: Int,
    val message: String?,
){
    companion object {
         val  OK = BaseMessage(200, "Success")
    }
}

data class OtpRequest(
    @Pattern(regexp = "^\\+998\\d{9}$", message = "Invalid phone number format")
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
    val expired: Long // second
)

data class RefreshTokenRequest(
    @NotBlank val refreshToken: String
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
    val longitude: Float,
    val latitude: Float
)

data class AddressRequest(
    val addressLine: String,
    val city: String,
    val longitude: Float,
    val latitude: Float
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
    longitude = this.longitude,
    latitude = this.latitude
)

data class CreateRestaurantRequest(
    val name: String,
    val contact: String,
    val location: String
)

data class RestaurantDTO(
    val id: Long,
    val name: String,
    val contact: String,
    val location: String,
)

fun Restaurant.toDto() = RestaurantDTO(
    id = this.id!!,
    name = this.name,
    contact = this.contact,
    location = this.location,
)

data class CreateCategoryRequest(
    val name: String,
    val nameUz: String?,
    val nameRu: String?,
    val descriptionUz: String?,
    val descriptionRu: String?,
    val description: String?,
    val restaurantId: Long
)

data class UpdateCategoryRequest(
    val name: String,
    val nameUz: String?,
    val nameRu: String?,
    val descriptionUz: String?,
    val descriptionRu: String?,
    val description: String?,
)

data class CategoryDTO(
    val id: Long,
    val name: String,
    val nameUz: String?,
    val nameRu: String?,
    val description: String,
    val descriptionUz: String?,
    val descriptionRu: String?,
    val restaurantId: Long
)

fun Category.toDto() = CategoryDTO(
    id = this.id!!,
    name = this.name,
    nameUz = this.nameUz,
    nameRu =this.nameRu,
    description = this.description!!,
    descriptionUz = this.descriptionUz,
    descriptionRu =this.descriptionRu,
    restaurantId = this.restaurant.id!!
)

data class ProductDTO(
    val id: Long,
    val name: String,
    val nameUz: String?,
    val nameRu: String?,
    val price: BigDecimal,
    val currency: String,
    val description: String?,
    val descriptionUz: String?,
    val descriptionRu: String?,
    val image: String?,
    val category: CategoryDTO,
    val createdDate: Date?
)

data class CreateProductRequest(
    @NotBlank val name: String,
    val nameUz: String?,
    val nameRu: String?,
    @DecimalMin(value = "0.01") val price: BigDecimal,
    val currency: Currency,
    val description: String?,
    val descriptionUz: String?,
    val descriptionRu: String?,
    val image: String?,
    val categoryId: Long
)

data class UpdateProductRequest(
    @NotBlank val name: String,
    val nameUz: String?,
    val nameRu: String?,
    @DecimalMin(value = "0.01") val price: BigDecimal,
    val description: String?,
    val descriptionUz: String?,
    val descriptionRu: String?,
    val image: String?,
    val categoryId: Long
)

fun Product.toDto() = ProductDTO(
    id = this.id!!,
    name = this.name,
    nameUz = this.nameUz,
    nameRu = this.nameRu,
    price = this.price,
    currency = this.currency ?: "UZS", // Provide default value when null
    description = this.description,
    descriptionUz = this.descriptionUz,
    descriptionRu = this.descriptionRu,
    image = this.image ?: "",
    category = this.category.toDto(),
    createdDate = this.createdDate
)

data class CreateOrderRequest(
    val restaurantId: Long,
    val items: List<OrderItemRequest>,
    val paymentOption: PaymentOption,
    val addressId: Long,
)

data class OrderItemRequest(
    val productId: Long,
    @DecimalMin(value = "0.01") val quantity: Int,
    val price: BigDecimal
)

data class OrderDTO(
    val id: Long?,
    val userId: Long,
    val restaurantId: Long?,
    val paymentOption: PaymentOption,
    val status: OrderStatus,
    val orderDate: LocalDateTime,
    val totalAmount: BigDecimal,
    val orderItems: List<OrderItemDTO>,
    val addressId: AddressDTO
)

fun Order.toDto() = OrderDTO(
    id = this.id,
    userId = this.user.id!!,
    restaurantId = this.restaurant.id,
    paymentOption = this.paymentOption,
    status = this.status,
    orderDate = this.orderDate.atStartOfDay(),
    totalAmount = totalAmount,
    orderItems = this.orderItems.map { it.toDto() },
    addressId = address.toDto()
)

fun OrderItem.toDto() = OrderItemDTO(
    id = this.id,
    orderId = this.order.id,
    productId = product.id,
    quantity = this.quantity,
    price = this.price
)

data class OrderItemDTO(
    val id: Long?,
    val orderId: Long?,
    val productId: Long?,
    val quantity: Int,
    val price: BigDecimal
)

data class PaymentRequest(
    @DecimalMin(value = "0.01") val amount: BigDecimal,
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
    @Pattern(regexp = "^(0[1-9]|1[0-2])/([0-9]{2})$", message = "MM/YY")
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

data class UpdateCardBalanceRequest(
    @DecimalMin(value = "0.0", message = "Balans 0 dan katta bo'lishi kerak")
    val amount: BigDecimal
)

interface LeastProductsResponse {
    val name: String
    val id: Long
    val lastOrderedDate: LocalDate?
    val total_sold: Int
}

data class CartDTO(
    val items: List<CartItemDTO> = emptyList()
)

data class CartItemDTO(
    val product: ProductDTO,
    val quantity: Int
)

