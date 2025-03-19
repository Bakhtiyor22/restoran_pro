package com.example.demo

import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.support.JpaEntityInformation
import org.springframework.data.jpa.repository.support.SimpleJpaRepository
import org.springframework.data.repository.NoRepositoryBean
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Configuration
@EnableJpaRepositories(
    basePackages = ["com.example.demo"],
    repositoryBaseClass = BaseRepositoryImpl::class
)
class JpaConfig

@NoRepositoryBean
interface BaseRepository<T : BaseEntity> : JpaRepository<T, Long>, JpaSpecificationExecutor<T> {
    fun trash(id: Long): T
    fun trashList(ids: List<Long>): List<T>
    fun findAllNotDeleted(pageable: Pageable): Page<T>
    fun findAllNotDeleted(): List<T>
    fun findByIdAndDeletedFalse(id: Long): T?
}

class BaseRepositoryImpl<T : BaseEntity>(
    entityInformation: JpaEntityInformation<T, Long>,
    entityManager: EntityManager,
) : SimpleJpaRepository<T, Long>(entityInformation, entityManager), BaseRepository<T> {
    val isNotDeletedSpecification = Specification<T> { root, _, cb -> cb.equal(root.get<Boolean>("deleted"), false) }


    @Transactional
    override fun trash(id: Long) = save(findById(id).get().apply { deleted = true })
    override fun findAllNotDeleted(pageable: Pageable) = findAll(isNotDeletedSpecification, pageable)
    override fun findAllNotDeleted(): List<T> = findAll(isNotDeletedSpecification)
    override fun findByIdAndDeletedFalse(id: Long) = findByIdOrNull(id)?.run { if (deleted) null else this }

    override fun trashList(ids: List<Long>): List<T> = ids.map { trash(it) }
}

@Repository
interface UserRepository : BaseRepository<User> {
    fun findByPhoneNumber(phoneNumber: String): User?
    fun findByRole(role: Roles): User?

}

@Repository
interface OtpRepository : BaseRepository<OtpEntity> {
    fun findByIdAndPhoneNumberAndDeletedFalse(id: Long, phoneNumber: String): OtpEntity?
}

@Repository
interface AddressRepository : BaseRepository<Address>{
    fun findByUserIdAndDeletedFalse(customerId: Long): Address?
}

@Repository
interface RestaurantRepository : BaseRepository<Restaurant>

@Repository
interface CategoryRepository : BaseRepository<Category> {
    fun findByNameIgnoreCaseAndRestaurantIdAndDeletedFalse(name: String, id: Long): Set<Category>?
}

@Repository
interface ProductRepository : BaseRepository<Product> {
    fun findByNameIgnoreCaseAndCategoryIdAndDeletedFalse(name: String, id: Long): Product?
    @Query("SELECT p FROM Product p WHERE p.id IN :ids AND p.deleted = false")
    fun findAllByIdAndDeletedFalse(@Param("ids") ids: List<Long>): List<Product>
}

interface OrderRepository : BaseRepository<Order> {
    fun findByCustomerId(customerId: Long, pageable: Pageable): Page<Order>
    fun findByRestaurantId(restaurantId: Long, pageable: Pageable): Page<Order>
    fun findByRestaurantIdAndStatus(restaurantId: Long, status: OrderStatus, pageable: Pageable): Page<Order>
}

interface OrderItemRepository : BaseRepository<OrderItem> {
    fun findByOrderId(orderId: Long): List<OrderItem>
}

@Repository
interface PaymentRepository : BaseRepository<PaymentTransaction> {
    fun findAllByUserIdOrderByTransactionTimeDesc(userId: Long): List<PaymentTransaction>
}

@Repository
interface CardRepository : BaseRepository<Card> {
    fun findByUserIdAndDeletedFalse(userId: Long): List<Card>
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByUserIdAndIsDefaultTrueAndDeletedFalse(userId: Long): Card?
    fun findByIdAndUserIdAndDeletedFalse(cardId: Long, userId: Long): Card?
}

@Repository
interface DiscountRepository : BaseRepository<Discount> {
    fun findByProductIdAndDeletedFalse(productId: Long): List<Discount>
    fun findByCategoryIdAndDeletedFalse(categoryId: Long): List<Discount>
    fun findByIsActiveAndDeletedFalse(isActive: Boolean): List<Discount>
}
