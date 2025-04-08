package com.example.demo
import jakarta.persistence.*
import org.hibernate.annotations.ColumnDefault
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*

@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
open class BaseEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @CreatedDate @Temporal(TemporalType.TIMESTAMP) var createdDate: Date? = null,
    @LastModifiedDate @Temporal(TemporalType.TIMESTAMP) var modifiedDate: Date? = null,
    @CreatedBy var createdBy: String? = null,
    @LastModifiedBy var modifiedBy: String? = null,
    @Column(nullable = false) @ColumnDefault(value = "false") var deleted: Boolean = false,
)

@Entity
@Table(name = "users")
class User(
    var username: String,
    var phoneNumber: String,
    var password: String,
    @Enumerated(EnumType.STRING) var role: Roles,
    var telegramChatId: Long? = null,
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY, cascade = [CascadeType.ALL], orphanRemoval = false) var cards: MutableList<Card> = mutableListOf(),
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY, cascade = [CascadeType.ALL], orphanRemoval = false) var addresses: MutableList<Address> = mutableListOf(),
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY, cascade = [CascadeType.ALL], orphanRemoval = false) var orders: MutableList<Order> = mutableListOf()
) : BaseEntity()

@Entity
@Table(name = "user_states")
class UserState(
    @OneToOne @JoinColumn(name = "user_id") var user: User,
    @Enumerated(EnumType.STRING) var currentState: BotState = BotState.START,
    @Enumerated(EnumType.STRING) var previousState: BotState? = null,
    @Enumerated(EnumType.STRING) var menuState: MenuStates = MenuStates.MAIN_MENU,
    @Column(columnDefinition = "TEXT") var temporaryData: String? = null  // JSON storage for state-related data
) : BaseEntity()

@Entity
@Table(name = "addresses")
class Address(
    var addressLine: String,
    var city: String,
    var longitude: Float,
    var latitude: Float,
    @ManyToOne @JoinColumn(name = "user_id") var user: User? = null
) : BaseEntity()

@Entity
@Table(name = "otps")
class OtpEntity(
    val phoneNumber: String,
    val otpLogin: String,
    val sentTime: LocalDateTime,
    val expiredAt: LocalDateTime,
    var checked: Boolean
) : BaseEntity()

@Entity
@Table(name = "restaurant")
class Restaurant(
    var name: String,
    var contact: String,
    var location: String
) : BaseEntity() {
    @OneToMany(mappedBy = "restaurant", cascade = [CascadeType.ALL], orphanRemoval = true) var categories: MutableList<Category> = mutableListOf()
}

@Entity
@Table(name = "categories")
class Category(
    var name: String,
    var nameUz: String,
    var nameRu: String,
    var descriptionUz: String?,
    var descriptionRu: String?,
    var description: String?,
    @ManyToOne(fetch = FetchType.EAGER) @JoinColumn(name = "restaurant_id") var restaurant: Restaurant
) : BaseEntity()

@Entity
@Table(name = "products")
class Product(
    var name: String,
    var nameUz: String,
    var nameRu: String,
    var price: BigDecimal,
    var currency: String,
    var descriptionUz: String?,
    var descriptionRu: String?,
    var description: String?,
    var image: String?,
    @ManyToOne @JoinColumn(name = "category_id") var category: Category,
) : BaseEntity()

@Entity
@Table(name = "orders")
class Order(
    @ManyToOne @JoinColumn(name = "user_id") var user: User,
    @ManyToOne @JoinColumn(name = "restaurant_id") var restaurant: Restaurant,
    @Enumerated(EnumType.STRING) var paymentOption: PaymentOption,
    @Enumerated(EnumType.STRING) var status: OrderStatus,
    var orderDate: LocalDate,
    var totalAmount: BigDecimal,
    @OneToMany(mappedBy = "order", cascade = [CascadeType.ALL], orphanRemoval = true) var orderItems: MutableList<OrderItem> = mutableListOf(),
    @ManyToOne @JoinColumn(name = "address_id") var address: Address
) : BaseEntity()

@Entity
@Table(name = "order_item")
class OrderItem(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    var order: Order,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    var product: Product,
    var quantity: Int,
    var price: BigDecimal
) : BaseEntity()

@Entity
@Table(name = "payment_transactions")
class PaymentTransaction(
    val userId: Long,
    val amount: BigDecimal,
    val orderId: Long?,
    @Enumerated(EnumType.STRING)
    val paymentOption: PaymentOption,

    @Enumerated(EnumType.STRING)
    var paymentStatus: PaymentStatus,
    @Column(nullable = false) var isRefund: Boolean = false,

    val transactionTime: LocalDateTime = LocalDateTime.now(),

    val transactionId: String = generateTransactionId()
): BaseEntity() {
    var retryCount: Int = 0
    companion object {
        fun generateTransactionId(): String = "TX" + System.currentTimeMillis()
    }
}

@Entity
@Table(name = "cards")
class Card(
    var cardNumber: String,
    var expiryDate: String,
    var cardHolderName: String,
    var isDefault: Boolean = false,

    @Enumerated(EnumType.STRING)
    var cardType: CardType,

    var balance: BigDecimal = BigDecimal.ZERO,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    var user: User
): BaseEntity()

@Entity
@Table(name = "carts")
class Cart(
    @ManyToOne @JoinColumn(name = "user_id") var user: User,
    @OneToMany(mappedBy = "cart", cascade = [CascadeType.ALL], orphanRemoval = true) var items: MutableList<CartItem> = mutableListOf()
) : BaseEntity()

@Entity
@Table(name = "cart_items")
class CartItem(
    @ManyToOne @JoinColumn(name = "cart_id") var cart: Cart,
    @ManyToOne @JoinColumn(name = "product_id") var product: Product,
    var quantity: Int
) : BaseEntity()

@Entity
@Table(name = "order_data")
class OrderData(
    @ManyToOne @JoinColumn(name = "user_id") var user: User,
    @ManyToOne @JoinColumn(name = "address_id") var address: Address? = null,
    @Enumerated(EnumType.STRING) var paymentOption: PaymentOption = PaymentOption.CASH,
    @ManyToOne @JoinColumn(name = "restaurant_id") var restaurant: Restaurant? = null
) : BaseEntity()

