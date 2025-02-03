package com.example.demo

import jakarta.persistence.*
import org.hibernate.annotations.ColumnDefault
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
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
    val phoneNumber: String,
    var password: String,
    @Enumerated(EnumType.STRING) var role: Roles,
    @OneToMany(mappedBy = "user", fetch = FetchType.EAGER) private var addresses: List<Address> = mutableListOf()
) : BaseEntity()

@Entity
@Table(name = "addresses")
class Address(
    val addressLine: String,
    val city: String,
    val state: String,
    val postalCode:String,
    val longitude: Float,
    val latitude: Float,

    @ManyToOne @JoinColumn(name = "user_id") private val user: User? = null
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
