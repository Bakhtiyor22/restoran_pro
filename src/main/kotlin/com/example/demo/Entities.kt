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
    @OneToMany(mappedBy = "user", fetch = FetchType.EAGER) var cards: MutableList<Card> = mutableListOf(),
    @OneToMany(mappedBy = "user", fetch = FetchType.EAGER) var addresses: MutableList<Address> = mutableListOf()
) : BaseEntity()

@Entity
@Table(name = "addresses")
class Address(
    val addressLine: String,
    val city: String,
    val longitude: Float,
    val latitude: Float,
    @ManyToOne @JoinColumn(name = "user_id") private var user: User? = null
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
    var description: String,
    @ManyToOne(fetch = FetchType.EAGER) @JoinColumn(name = "restaurant_id") var restaurant: Restaurant
) : BaseEntity()

@Entity
@Table(name = "products")
class Product(
    var name: String,
    var price: BigDecimal,
    var description: String,
    var image: String?,
    @ManyToOne @JoinColumn(name = "category_id") var category: Category,
) : BaseEntity()

@Entity
@Table(name = "orders")
class Order(
    var customerId: Long,
    @ManyToOne var restaurant: Restaurant,
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

