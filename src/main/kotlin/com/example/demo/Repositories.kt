package com.example.demo

import jakarta.persistence.EntityManager
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
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
interface AddressRepository : BaseRepository<Address>

@Repository
interface RestaurantRepository : BaseRepository<Restaurant>

@Repository
interface MenuRepository : BaseRepository<Menu> {
    fun findAllByRestaurantId(restaurantId: Long): List<Menu>
}

@Repository
interface MenuItemRepository : BaseRepository<MenuItem> {
    fun findAllByMenuId(menuId: Long): List<MenuItem>
}

@Repository
interface OrderRepository : BaseRepository<Order> {
    fun findAllByRestaurantId(restaurantId: Long): List<Order>
}

@Repository
interface OrderItemRepository : BaseRepository<OrderItem>
