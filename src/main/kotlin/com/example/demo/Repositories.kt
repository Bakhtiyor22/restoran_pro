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
import java.math.BigDecimal
import java.time.LocalDate
import java.util.*

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
    fun findByTelegramChatId(chatId: Long): User?
}

@Repository
interface UserStateRepository : BaseRepository<UserState> {
    fun findByUserId(userId: Long): UserState?
}

@Repository
interface OtpRepository : BaseRepository<OtpEntity> {
    fun findByIdAndPhoneNumberAndDeletedFalse(id: Long, phoneNumber: String): OtpEntity?
}

@Repository
interface AddressRepository : BaseRepository<Address>{
    fun findByIdAndUserIdAndDeletedFalse(addressId: Long, userId: Long): Address?
    fun findAllByUserIdAndDeletedFalse(userId: Long): List<Address>
}

@Repository
interface RestaurantRepository : BaseRepository<Restaurant>

@Repository
interface CategoryRepository : BaseRepository<Category> {
    fun findByNameContainingIgnoreCase(name: String): Category?
    fun findByNameIgnoreCaseAndRestaurantIdAndDeletedFalse(name: String, id: Long): Set<Category>?
}

@Repository
interface ProductRepository : BaseRepository<Product> {
//    @Query("SELECT pr FROM Product pr WHERE LOWER(pr.name) LIKE LOWER(CONCAT('%', :name, '%'))")
//    fun findByName(@Param("name") name: String?): List<Product>
    fun findByCategoryIdAndDeletedFalse(id: Long): List<Product>

    override fun findByIdAndDeletedFalse(id: Long): Product?

    fun findByNameIgnoreCaseAndCategoryIdAndDeletedFalse(name: String, id: Long): Product?

    @Query("SELECT p FROM Product p WHERE p.id IN :ids AND p.deleted = false")
    fun findAllByIdAndDeletedFalse(@Param("ids") ids: List<Long>): List<Product>

    @Query("SELECT p FROM Product p WHERE p.deleted = false ORDER BY p.createdDate DESC")
    fun findAllSortByCreatedDateDesc(pageable: Pageable): Page<Product>
}

interface OrderRepository : BaseRepository<Order> {
    fun findByUserIdAndDeletedFalse(customerId: Long, pageable: Pageable): Page<Order>
    fun findByRestaurantId(restaurantId: Long, pageable: Pageable): Page<Order>

    @Query("SELECT SUM(o.totalAmount) FROM Order o WHERE o.status = 'COMPLETED' and YEAR(o.orderDate) = YEAR(CURRENT_DATE) AND MONTH(o.orderDate) = MONTH(CURRENT_DATE) ")
    fun findTotalSalesForCurrentMonth(): BigDecimal

    @Query("SELECT SUM(o.totalAmount) FROM Order o WHERE o.status = 'COMPLETED' and o.orderDate = CURRENT_DATE")
    fun findTotalSalesForCurrentDay(): BigDecimal?

    @Query("SELECT SUM(o.totalAmount) FROM Order o WHERE o.status = 'COMPLETED' and o.orderDate BETWEEN :startDate AND :endDate")
    fun findSalesBetweenDates(@Param("startDate") startDate: LocalDate, @Param("endDate") endDate: LocalDate): BigDecimal

    @Query("SELECT SUM(o.totalAmount) as total, o.orderDate as date FROM Order o WHERE o.status = 'COMPLETED' and o.orderDate BETWEEN :startDate AND :endDate GROUP BY o.orderDate")
    fun findDailySales(@Param("startDate") startDate: LocalDate, @Param("endDate") endDate: LocalDate): List<Array<Any>>

    @Query("SELECT o.user, COUNT(o) as orderCount, SUM(o.totalAmount) as totalSpent FROM Order o GROUP BY o.user ORDER BY totalSpent DESC")
    fun findTopBuyers(pageable: Pageable): List<Array<Any>>
}

interface OrderItemRepository : BaseRepository<OrderItem> {
    @Query("SELECT oi.product FROM OrderItem oi GROUP BY oi.product ORDER BY SUM(oi.quantity) DESC")
    fun findMostSoldProducts(): List<Product>

    @Query("SELECT oi.product FROM OrderItem oi GROUP BY oi.product ORDER BY SUM(oi.quantity) ASC")
    fun findLeastSoldProducts(): List<Product>

    @Query(value = """
                        SELECT p.name, p.id, max(o.created_date) as lastOrderedDate, COALESCE(SUM(ot.quantity), 0) as total_sold
                From products p
                         left join order_item ot On p.id = ot.product_id
                         left join orders o ON ot.order_id = o.id
                where o.created_date > current_timestamp - interval '30 day' OR o.created_date IS NULL
                GROUP BY p.name, p.id
                ORDER BY total_sold, lastOrderedDate DESC
                LIMIT 10;
    """, nativeQuery = true)
    fun findLeastSoldProductsForLast30Days(): List<LeastProductsResponse>
}

@Repository
interface OrderDataRepository : BaseRepository<OrderData> {
    fun findByUserIdAndDeletedFalse(userId: Long): OrderData?
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
interface CartRepository : BaseRepository<Cart> {
    fun findByUserIdAndDeletedFalse(id: Long): Cart?
}

