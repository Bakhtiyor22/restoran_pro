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
    @Column(nullable = false) @ColumnDefault(value = "false") var deleted: Boolean = false,//I should implement the soft deletion
)

@Entity
@Table(name = "users")
class User(
    var username: String,
    var phoneNumber: String,
    var password: String,
    @Enumerated(EnumType.STRING) var role: Roles,
    @OneToMany(mappedBy = "user", fetch = FetchType.EAGER) var addresses: List<Address> = mutableListOf()
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
    @Column(name = "customer_id") var customerId: Long,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "restaurant_id") var restaurant: Restaurant,
    var totalAmount: BigDecimal,
    @Enumerated(EnumType.STRING) var paymentOption: PaymentOption,
    @Enumerated(EnumType.STRING) var status: OrderStatus,
    var orderDate: LocalDateTime = LocalDateTime.now()
) : BaseEntity() {
    @OneToMany(mappedBy = "order", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    var orderItems: MutableList<OrderItem> = mutableListOf()
}

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