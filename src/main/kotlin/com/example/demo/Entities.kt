package com.example.demo

import com.example.demo.enums.Role
import jakarta.persistence.*
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import org.hibernate.annotations.ColumnDefault
import org.hibernate.internal.util.StringHelper
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
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
@Table(name = "_user")
class User(
    val username: String,
    val phoneNumber: String,
    var password: String,
    @Enumerated(EnumType.STRING) var role: Role,
    @OneToMany(fetch = FetchType.EAGER) @JoinColumn(name = "address_id") private var addresses: List<Address> = mutableListOf()
) : BaseEntity()

@Entity
@Table(name = "address")
class Address(
    val addressLine: String,
    val city: String,
    val state: String,
    val postalCode:String,
    val longitude: Float,
    val latitude: Float

) : BaseEntity()

