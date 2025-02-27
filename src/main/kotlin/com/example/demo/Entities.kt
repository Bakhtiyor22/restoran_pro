package com.example.demo

import jakarta.persistence.*
import org.hibernate.annotations.ColumnDefault
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.math.BigDecimal
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
class Address(
    val addressLine: String,
    val city: String,
    val state: String,
    val postalCode: String,
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
    @OneToMany(mappedBy = "restaurant", cascade = [CascadeType.ALL], orphanRemoval = true)
    var menus: MutableList<Menu> = mutableListOf()
}

@Entity
@Table(name = "menu")
class Menu(
    var name: String,
    var description: String?,
    var category: MenuCategory,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurant_id")
    var restaurant: Restaurant
) : BaseEntity() {
    @OneToMany(mappedBy = "menu", cascade = [CascadeType.ALL], orphanRemoval = true)
    var menuItems: MutableList<MenuItem> = mutableListOf()
}

@Entity
@Table(name = "menu_item")
class MenuItem(
    var name: String,
    var price: BigDecimal,
    var description: String?,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menu_id")
    var menu: Menu
) : BaseEntity()

@Entity
@Table(name = "orders")
class Order(
    var customerId: Long,
    @ManyToOne
    var restaurant: Restaurant,
    var totalAmount: BigDecimal,
    var subtotal: BigDecimal,
    var serviceCharge: BigDecimal,
    var deliveryFee: BigDecimal,
    var discount: BigDecimal,
    @Enumerated(EnumType.STRING)
    var paymentOption: PaymentOption,
    @Enumerated(EnumType.STRING)
    var status: OrderStatus,
    var orderDate: LocalDateTime,
    @OneToMany(mappedBy = "order", cascade = [CascadeType.ALL], orphanRemoval = true)
    var orderItems: MutableList<OrderItem> = mutableListOf()
) : BaseEntity()

@Entity
@Table(name = "order_item")
class OrderItem(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    var order: Order,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menu_item_id")
    var menuItem: MenuItem,
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

    val transactionTime: LocalDateTime = LocalDateTime.now(),

    val transactionId: String = generateTransactionId()
): BaseEntity() {
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
    var cardStatus: Boolean = true,

    @Enumerated(EnumType.STRING)
    var cardType: CardType,

    var balance: BigDecimal = BigDecimal.ZERO,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    var user: User
): BaseEntity()

@Entity
@Table(name = "cart")
class Cart(
    var customerId: Long,
    @ManyToOne
    var restaurant: Restaurant,
    @OneToMany(mappedBy = "cart", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var items: MutableList<CartItem> = mutableListOf(),
    var serviceChargePercent: BigDecimal = BigDecimal.ZERO,
    var deliveryFee: BigDecimal = BigDecimal.ZERO,
    var discountPercent: BigDecimal = BigDecimal.ZERO,
) : BaseEntity() {
    fun addItem(item: CartItem) {
        items.add(item)
        item.cart = this
    }
}

@Entity
@Table(name = "cart_items")
class CartItem(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menu_item_id")
    val menuItem: MenuItem,
    var quantity: Int
) : BaseEntity() {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id")
    lateinit var cart: Cart
}