package com.example.demo

import io.jsonwebtoken.Jwts
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.security.GeneralSecurityException
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

interface OTPService {
    fun generateOTP(phoneNumber: String): Long
    fun validateOTP(phoneNumber: String, otpCode: String, otpId: Long): Boolean
}

interface EskizService {
    fun sendMessage(msg: String, phoneNumber: String): Boolean
}

interface AuthService {
    fun requestOtp(otpRequest: OtpRequest): OtpIdResponse
    fun otpLogin(otpLogin: OtpLogin): TokenResponse
    fun login(request: LoginRequest): TokenResponse
    fun refreshToken(request: RefreshTokenRequest): TokenResponse
}

interface MessageSourceService {
    fun getMessage(key: MessageKey): String
}

interface UserService {
    fun createUser(createUserRequest: CreateUserRequest): UserDTO
    fun getAllUsers(pageable: Pageable): Page<UserDTO>
    fun getUserById(id: Long): UserDTO?
    fun updateUser(id: Long, updateUserRequest: UpdateUserRequest): UserDTO
    fun deleteUser(id: Long)
    fun addAddress(userId: Long, addressRequest: AddressRequest): AddressDTO
}

interface RestaurantService {
    fun create(request: CreateRestaurantRequest):BaseMessage
    fun getRestaurantById(restaurantId: Long): RestaurantDTO
}

interface CategoryService {
    fun createCategory(createCategoryRequest: CreateCategoryRequest): CategoryDTO
    fun updateCategory(categoryId: Long, updateCategoryRequest: UpdateCategoryRequest): CategoryDTO
    fun getAllAvailableCategories(pageable: Pageable): Page<CategoryDTO>
    fun getAllCategories(pageable: Pageable): Page<CategoryDTO>
    fun getCategoryById(categoryId: Long): CategoryDTO
    fun deleteCategoryById(categoryId: Long)
}

interface ProductService {
    fun createProduct(createProductRequest: CreateProductRequest): ProductDTO
    fun updateProduct(productId: Long, updateProductRequest: UpdateProductRequest): ProductDTO?
    fun getALlAvailableProducts(pageable: Pageable): Page<ProductDTO>
    fun getAllProducts(pageable: Pageable): Page<ProductDTO>
    fun getProductById(id: Long): ProductDTO?
    fun deleteProductById(id: Long)
}

interface OrderService {
    fun createOrder(customerId: Long, createOrderRequest: CreateOrderRequest): OrderDTO
    fun getOrderById(orderId: Long): OrderDTO
    fun getCustomerOrders(customerId: Long, pageable: Pageable): Page<OrderDTO>
    fun getRestaurantOrders(restaurantId: Long, pageable: Pageable): Page<OrderDTO>
    fun updateOrderStatus(orderId: Long, status: OrderStatus): OrderDTO
    fun processPayment(orderId: Long, paymentRequest: PaymentRequest): BaseMessage
    fun refundOrder(orderId: Long): BaseMessage
}

interface PaymentService {
    fun processOrderPayment(userId: Long, orderId: Long?, paymentRequest: PaymentRequest): BaseMessage
    fun getPaymentHistory(userId: Long): List<PaymentTransactionDTO>
}

interface CardService {
    fun addCard(userId: Long, request: AddCardRequest): CardDTO
    fun getUserCards(userId: Long): List<CardDTO>
    fun setDefaultCard(userId: Long, cardId: Long): CardDTO
    fun deleteCard(userId: Long, cardId: Long)
    fun updateCardBalance(userId: Long, cardId: Long, amount: BigDecimal): CardDTO
}

interface DiscountService {
    fun createDiscount(request: CreateDiscountRequest): DiscountDTO
    fun getActiveDiscounts(): List<DiscountDTO>
    fun calculateDiscount(productId: Long): BigDecimal
    fun deactivateDiscount(discountId: Long)
}

@Service
class AuthServiceImpl(
    private val otpService: OTPService,
    private val userRepository: UserRepository,
    private val jwtUtils: JwtUtils,
    private val passwordEncoder: PasswordEncoder,
    private val messageSourceService: MessageSourceService
) : AuthService {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun requestOtp(otpRequest: OtpRequest): OtpIdResponse {
        val phoneNumber = otpRequest.phoneNumber
        if (!validatePhoneNumber(phoneNumber)) throw InvalidInputException(phoneNumber)

        val foundUser = userRepository.findByPhoneNumber(phoneNumber)
        val userStatus = when {
            foundUser != null -> UserStatus.EXISTS
            else -> UserStatus.NOT_FOUND
        }

        val otpId = otpService.generateOTP(phoneNumber)

        val messageText = messageSourceService.getMessage(MessageKey.OTP_REQUEST)
        return OtpIdResponse(otpId, messageText).also { logger.info("userStatus = $userStatus") }
    }

    override fun otpLogin(otpLogin: OtpLogin): TokenResponse {
        val validOtp = otpService.validateOTP(otpLogin.phoneNumber, otpLogin.otp, otpLogin.otpId)
        if (!validOtp) {
            throw InvalidInputException(otpLogin.otp)
        }

        var user = userRepository.findByPhoneNumber(otpLogin.phoneNumber)
        val userStatus = when {
            user != null -> UserStatus.EXISTS
            else -> UserStatus.CREATED
        }
        if (user == null) {
            user = User(
                username = "",
                phoneNumber = otpLogin.phoneNumber,
                password = "",
                role = Roles.CUSTOMER
            )
            userRepository.save(user)
        }

        val tokenResponse = jwtUtils.generateToken(user)
        logger.info("userStatus = $userStatus")
        return tokenResponse
    }

    override fun login(request: LoginRequest): TokenResponse {
        if (!validatePhoneNumber(request.phoneNumber)) throw InvalidInputException(request.phoneNumber)

        val user = userRepository.findByPhoneNumber(request.phoneNumber)
            ?: throw UserNotFoundException()

        if (!passwordEncoder.matches(request.password, user.password)) {
            throw InvalidInputException(request.password)
        }

        val currentLocale = LocaleContextHolder.getLocale().language
        return jwtUtils.generateToken(user, currentLocale)
    }

    override fun refreshToken(request: RefreshTokenRequest): TokenResponse {
        if (!jwtUtils.validateToken(request.refreshToken))
            throw InvalidInputException(request.refreshToken)

        val claims = jwtUtils.getJwtParser()
            .build()
            .parseClaimsJws(request.refreshToken)
            .body

        val tokenType = claims.get("tokenType", String::class.java)
        if (tokenType != "REFRESH") throw InvalidInputException(tokenType)

        val phoneNumber = claims.subject
        val user = userRepository.findByPhoneNumber(phoneNumber) ?: throw UserNotFoundException()
        val locale = jwtUtils.extractLocale(request.refreshToken)

        return jwtUtils.generateToken(user, locale)
    }
}

@Service
class CustomUserDetailsService(
    private val userRepository: UserRepository
) : UserDetailsService {

    override fun loadUserByUsername(phoneNumber: String): UserDetails {
        val user = userRepository.findByPhoneNumber(phoneNumber)
            ?: throw ResourceNotFoundException(phoneNumber)

        val authorities = listOf(SimpleGrantedAuthority("ROLE_${user.role.name}"))

        return org.springframework.security.core.userdetails.User(
            user.phoneNumber,
            user.password,
            authorities
        )
    }
}

@Service
class EskizImp : EskizService {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun sendMessage(msg: String, phoneNumber: String): Boolean {
        logger.info("Send message success , $phoneNumber , message : $msg")
        return true
    }
}

@Service
class MessageSourceServiceImpl(
    private val messageSource: MessageSource
) : MessageSourceService {
    override fun getMessage(key: MessageKey): String {
        return messageSource.getMessage(key.name, null, LocaleContextHolder.getLocale())
    }
}

@Service
class OTPServiceImpl(
    private val eskizService: EskizService,
    private val otpRepository: OtpRepository,
    private val passwordEncoder: PasswordEncoder,
    private val redisTemplate: RedisTemplate<String, Any>
) : OTPService {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val MAX_ATTEMPTS = 3
    private val ATTEMPTS_TTL = 24L // hours

    override fun generateOTP(phoneNumber: String): Long {
        val otpAttemptsKey = "otp:attempts:$phoneNumber"
        val operations = redisTemplate.opsForValue()

        val attempts = operations.get(otpAttemptsKey)?.toString()?.toInt() ?: 0

        if (attempts >= MAX_ATTEMPTS) {
            throw ValidationException(phoneNumber)
        }

        operations.set(otpAttemptsKey, (attempts + 1).toString())
        redisTemplate.expire(otpAttemptsKey, ATTEMPTS_TTL, TimeUnit.HOURS)

        val otpCode = (100000..999999).random().toString()

        val now = LocalDateTime.now()
        val entity = OtpEntity(
            phoneNumber = phoneNumber,
            otpLogin = passwordEncoder.encode(otpCode),
            sentTime = now,
            expiredAt = now.plusMinutes(1),
            checked = false
        )
        val savedRecord = otpRepository.save(entity)

        val message = "Sizning otpiz $otpCode"
        eskizService.sendMessage(message, phoneNumber)
        logger.info("Generated OTP=$otpCode for phoneNumber=$phoneNumber, DB ID=${savedRecord.id}")

        return savedRecord.id!!
    }

    override fun validateOTP(phoneNumber: String, otpCode: String, otpId: Long): Boolean {
        val record = otpRepository.findByIdAndPhoneNumberAndDeletedFalse(otpId, phoneNumber)
            ?: return false.also { logger.warn("No OtpEntity found for id=$otpId & phone=$phoneNumber") }

        val isExpired = record.expiredAt.isBefore(LocalDateTime.now())
        if (isExpired || record.checked) {
            logger.warn("OTP expired or used for id=$otpId, phone=$phoneNumber")
            return false
        }

        val isMatch = passwordEncoder.matches(otpCode, record.otpLogin)
        if (isMatch) {
            record.checked = true
            otpRepository.save(record)

            val otpAttemptsKey = "otp:attempts:$phoneNumber"
            redisTemplate.delete(otpAttemptsKey)
        } else {
            logger.warn("OTP mismatch => phone=$phoneNumber, code=$otpCode, stored=${record.otpLogin}")
        }
        return isMatch
    }
}

@Service
class UserServiceImpl(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val addressRepository: AddressRepository
) : UserService{
    override fun createUser(createUserRequest: CreateUserRequest): UserDTO {
        if (createUserRequest.phoneNumber.isBlank()) {
            throw InvalidInputException(createUserRequest.phoneNumber)
        }

        if (userRepository.findByPhoneNumber(createUserRequest.phoneNumber) != null) {
            throw DuplicateResourceException(createUserRequest.phoneNumber)
        }

        val user = User(
            username = createUserRequest.username,
            phoneNumber = createUserRequest.phoneNumber,
            password = passwordEncoder.encode(createUserRequest.password),
            role = createUserRequest.role
        )
        return userRepository.save(user).toDto()
    }

    override fun getAllUsers(pageable: Pageable): Page<UserDTO> {
       return userRepository.findAllNotDeleted(pageable).map { it.toDto() }
    }

    override fun getUserById(id: Long): UserDTO? {
        val user = userRepository.findById(id).orElseThrow { ResourceNotFoundException(id) }
        return user.toDto()
    }

    override fun updateUser(id: Long, updateUserRequest: UpdateUserRequest): UserDTO {
        val user = userRepository.findById(id).orElseThrow { ResourceNotFoundException(id) }

        if (updateUserRequest.phoneNumber != user.phoneNumber) {
            val existing = userRepository.findByPhoneNumber(updateUserRequest.phoneNumber)
            if (existing != null && existing.id != user.id) {
                throw DuplicateResourceException(updateUserRequest.phoneNumber)
            }
        }

        user.username = updateUserRequest.username.ifBlank { user.username }
        user.phoneNumber = updateUserRequest.phoneNumber.ifBlank { user.phoneNumber }

        if (updateUserRequest.password.isNotBlank()) {
            user.password = passwordEncoder.encode(updateUserRequest.password)
        }

        return userRepository.save(user).toDto()
    }

    override fun deleteUser(id: Long) {
        val existing = userRepository.findById(id).orElseThrow { ResourceNotFoundException(id) }
        existing.deleted = true
        userRepository.save(existing)
    }

    override fun addAddress(userId: Long, addressRequest: AddressRequest): AddressDTO {
        val user = userRepository.findById(userId).orElseThrow { ResourceNotFoundException(userId) }
        val address = Address(
            addressLine = addressRequest.addressLine,
            city = addressRequest.city,
            longitude = addressRequest.longitude,
            latitude = addressRequest.latitude,
            user = user
        )
        return addressRepository.save(address).toDto()
    }
}

@Service
class RestaurantServiceImpl(
    private val restaurantRepository: RestaurantRepository,
): RestaurantService {

    override fun create(request: CreateRestaurantRequest): BaseMessage {
        if (request.name.isEmpty()) {
            throw InvalidInputException(request.name)
        }

        val restaurant = Restaurant(
            name = request.name,
            contact = request.contact,
            location = request.location
        )
        restaurantRepository.save(restaurant)
        return  BaseMessage.OK
    }

    override fun getRestaurantById(restaurantId: Long): RestaurantDTO {
        return restaurantRepository.findByIdAndDeletedFalse(restaurantId)?.toDto() ?: throw ResourceNotFoundException(restaurantId)
    }
}

@Service
class CategoryServiceImpl(
    private val categoryRepository: CategoryRepository,
    private val restaurantRepository: RestaurantRepository
) : CategoryService {
    override fun createCategory(createCategoryRequest: CreateCategoryRequest): CategoryDTO {
        if(createCategoryRequest.name.isEmpty()) throw InvalidInputException(createCategoryRequest.name)

        val restaurant = restaurantRepository.findById(createCategoryRequest.restaurantId)
            .orElseThrow { ResourceNotFoundException(createCategoryRequest.restaurantId) }

        val existingCategories = categoryRepository.findByNameIgnoreCaseAndRestaurantIdAndDeletedFalse(
            createCategoryRequest.name,
            createCategoryRequest.restaurantId
        )

        if (!existingCategories.isNullOrEmpty()) {
            throw DuplicateResourceException(createCategoryRequest.name)
        }

        val category = Category(
            name = createCategoryRequest.name,
            description = createCategoryRequest.description?: "",
            restaurant = restaurant
        )

        return categoryRepository.save(category).toDto()
    }

    override fun updateCategory(categoryId: Long, updateCategoryRequest: UpdateCategoryRequest): CategoryDTO {
        val existingCategory = categoryRepository.findById(categoryId).orElseThrow { ResourceNotFoundException(categoryId) }

        if(updateCategoryRequest.name.isEmpty()) throw InvalidInputException(updateCategoryRequest.name)

        if(existingCategory.name != updateCategoryRequest.name) {
            val duplicateCategory = categoryRepository.findByNameIgnoreCaseAndRestaurantIdAndDeletedFalse(
                updateCategoryRequest.name,
                existingCategory.restaurant.id!!
            )
            if (duplicateCategory != null) {
                throw DuplicateResourceException(updateCategoryRequest.name)
            }
        }

        existingCategory.apply {
            name = updateCategoryRequest.name
            description = updateCategoryRequest.description?: ""
        }

        return categoryRepository.save(existingCategory).toDto()
    }

    override fun getAllAvailableCategories(pageable: Pageable): Page<CategoryDTO> {
        return categoryRepository.findAllNotDeleted(pageable).map { it.toDto() }
    }

    override fun getAllCategories(pageable: Pageable): Page<CategoryDTO> {
        return categoryRepository.findAll(pageable).map { it.toDto() }
    }

    override fun getCategoryById(categoryId: Long): CategoryDTO {
        return categoryRepository.findByIdAndDeletedFalse(categoryId)?.toDto() ?: throw ResourceNotFoundException(categoryId)
    }

    override fun deleteCategoryById(categoryId: Long) {
       val category = categoryRepository.findById(categoryId).orElseThrow { ResourceNotFoundException(categoryId) }
        category.deleted = true
        categoryRepository.save(category)
    }
}

@Service
class ProductServiceImpl(
    private val productRepository: ProductRepository,
    private val categoryRepository: CategoryRepository
): ProductService {
    override fun createProduct(createProductRequest: CreateProductRequest): ProductDTO {
        if(createProductRequest.name.isEmpty()) throw InvalidInputException(createProductRequest.name)

        val category = categoryRepository.findByIdAndDeletedFalse(createProductRequest.categoryId)
            ?: throw ResourceNotFoundException(createProductRequest.categoryId)

        val existingProduct = productRepository.findByNameIgnoreCaseAndCategoryIdAndDeletedFalse(
            createProductRequest.name,
            createProductRequest.categoryId
        )

        if (existingProduct != null) {
            throw DuplicateResourceException(createProductRequest.name)
        }

        val product = Product(
            name = createProductRequest.name,
            description = createProductRequest.description ?: "",
            price = createProductRequest.price,
            image = createProductRequest.image ?: "",
            category = category
        )
        return productRepository.save(product).toDto()
    }

    override fun updateProduct(productId: Long, updateProductRequest: UpdateProductRequest): ProductDTO {
        val product = productRepository.findByIdAndDeletedFalse(productId)
            ?: throw ResourceNotFoundException(productId)

        val category = categoryRepository.findByIdAndDeletedFalse(updateProductRequest.categoryId)
            ?: throw ResourceNotFoundException(updateProductRequest.categoryId)

        if (product.name != updateProductRequest.name) {
            val existingProduct = productRepository.findByNameIgnoreCaseAndCategoryIdAndDeletedFalse(
                updateProductRequest.name,
                updateProductRequest.categoryId
            )

            if (existingProduct != null && existingProduct.id != productId) {
                throw DuplicateResourceException(updateProductRequest.name)
            }
        }

        product.apply {
            name = updateProductRequest.name
            description = updateProductRequest.description ?: ""
            price = updateProductRequest.price
            image = updateProductRequest.image ?: ""
            this.category = category
        }

        return productRepository.save(product).toDto()
    }

    override fun getALlAvailableProducts(pageable: Pageable): Page<ProductDTO> {
        return productRepository.findAllNotDeleted(pageable).map { it.toDto() }
    }

    override fun getAllProducts(pageable: Pageable): Page<ProductDTO> {
        return productRepository.findAll(pageable).map { it.toDto() }
    }

    override fun getProductById(id: Long): ProductDTO? {
        return productRepository.findByIdAndDeletedFalse(id)?.toDto()
    }

    override fun deleteProductById(id: Long) {
        val product = productRepository.findById(id).orElseThrow { ResourceNotFoundException(id) }
        product.deleted = true
        productRepository.save(product)
    }
}

@Service
class OrderServiceImpl(
    private val orderRepository: OrderRepository,
    private val productRepository: ProductRepository,
    private val restaurantRepository: RestaurantRepository,
    private val paymentService: PaymentService,
    private val addressRepository: AddressRepository,
    private val cardRepository: CardRepository,
    private val paymentRepository: PaymentRepository,
    private val userRepository: UserRepository
) : OrderService {
    @Transactional
    override fun createOrder(customerId: Long, createOrderRequest: CreateOrderRequest): OrderDTO {
        val authenticationName = SecurityContextHolder.getContext().authentication.name
        val loggedInUser = userRepository.findByPhoneNumber(authenticationName) ?: throw ForbiddenException()

        if (loggedInUser.id != customerId) throw ForbiddenException()

        val restaurant = restaurantRepository.findByIdAndDeletedFalse(createOrderRequest.restaurantId) ?: throw ResourceNotFoundException(createOrderRequest.restaurantId)
        val customer = userRepository.findByIdAndDeletedFalse(customerId) ?: throw ResourceNotFoundException(customerId)

        val defaultAddress = addressRepository.findByUserIdAndDeletedFalse(customerId)
        val newAddress = defaultAddress == null

        val finalAddress = if (newAddress){
            val address = Address(
                user = customer,
                addressLine = createOrderRequest.deliveryAddress.addressLine,
                city = createOrderRequest.deliveryAddress.city,
                longitude = createOrderRequest.deliveryAddress.longitude,
                latitude = createOrderRequest.deliveryAddress.latitude
            )
            addressRepository.save(address)
        } else {
            defaultAddress
        }

        val productIds = createOrderRequest.items.map { it.productId }
        val products = productRepository.findAllByIdAndDeletedFalse(productIds)

        val foundIds = products.map { it.id }.toSet()
        val missingIds = productIds.filterNot { foundIds.contains(it) }

        if (missingIds.isNotEmpty()) throw ResourceNotFoundException(missingIds)

        val subtotal = calculateSubtotal(createOrderRequest.items, products)
        val serviceCharge = calculateServiceCharge(subtotal)
        val deliveryFee = calculateDeliveryFee(createOrderRequest.deliveryAddress.toString())
        val discount = calculateDiscount(subtotal, customerId)
        val totalAmount = subtotal.add(serviceCharge).add(deliveryFee).subtract(discount)

        products.forEach{ product -> if(product.category.restaurant.id != restaurant.id) throw InvalidInputException(product.id) }

        val order = Order(
            customerId = customerId,
            restaurant = restaurant,
            paymentOption = createOrderRequest.paymentOption,
            status = OrderStatus.PENDING,
            address = finalAddress!!,
            orderDate = LocalDateTime.now(),
            totalAmount = totalAmount
        )

        createOrderRequest.items.forEach { item -> if (item.quantity <= 0) throw InvalidInputException(item.quantity) }

        val orderItems = createOrderRequest.items.map { item ->
            val product = products.first { it.id == item.productId }
            OrderItem(
                order = order,
                product = product,
                quantity = item.quantity,
                price = product.price
            )
        }

        order.orderItems = orderItems.toMutableList()
        val savedOrder = orderRepository.save(order)

        paymentService.processOrderPayment(customerId, savedOrder.id, PaymentRequest(savedOrder.totalAmount, savedOrder.paymentOption))

        return savedOrder.toDto()
    }

    override fun getOrderById(orderId: Long): OrderDTO {
        return orderRepository.findByIdAndDeletedFalse(orderId)?.toDto()?: throw ResourceNotFoundException(orderId)
    }

    override fun getCustomerOrders(customerId: Long, pageable: Pageable): Page<OrderDTO> {
        return orderRepository.findByCustomerId(customerId, pageable).map { it.toDto() }
    }

    override fun getRestaurantOrders(restaurantId: Long, pageable: Pageable): Page<OrderDTO> {
        return orderRepository.findByRestaurantId(restaurantId, pageable).map { it.toDto() }
    }

    override fun updateOrderStatus(orderId: Long, status: OrderStatus): OrderDTO {
        val order = orderRepository.findByIdAndDeletedFalse(orderId) ?: throw ResourceNotFoundException(orderId)

        validateStatusTransition(order.status, status)

        if(order.status != OrderStatus.COMPLETED){
            order.status = status
            return orderRepository.save(order).toDto()
        }

        return order.toDto()
    }

    override fun processPayment(orderId: Long, paymentRequest: PaymentRequest): BaseMessage {
        val order = orderRepository.findByIdAndDeletedFalse(orderId) ?: throw ResourceNotFoundException(orderId)

        if (paymentRequest.amount <= BigDecimal.ZERO) {
            throw InvalidInputException(paymentRequest.amount)
        }

        if(order.status != OrderStatus.PENDING){
            throw ValidationException(order.status)
        }

        if (order.totalAmount != paymentRequest.amount) {
            throw ValidationException(paymentRequest.amount)
        }

        val result = paymentService.processOrderPayment(order.customerId, orderId, paymentRequest)
        if(result.code == 200){
            order.status = OrderStatus.ACCEPTED
            orderRepository.save(order)
        }

        return result
    }

    override fun refundOrder(orderId: Long): BaseMessage {
        val order = orderRepository.findByIdAndDeletedFalse(orderId) ?: throw ResourceNotFoundException(orderId)

        if (order.status == OrderStatus.REFUNDED) {
            throw ValidationException(order.status)
        }

        if (order.status != OrderStatus.COMPLETED && order.status != OrderStatus.ACCEPTED) {
            throw ValidationException(order.status)
        }

        if (order.paymentOption == PaymentOption.CARD) {
            val defaultCard = cardRepository.findByUserIdAndIsDefaultTrueAndDeletedFalse(order.customerId)
            defaultCard?.let {
                it.balance = it.balance.add(order.totalAmount)
                cardRepository.save(it)
            }
        } else if (order.paymentOption == PaymentOption.CASH) throw InvalidInputException(order.paymentOption)

        val transaction = PaymentTransaction(
            userId = order.customerId,
            amount = order.totalAmount,
            paymentOption = order.paymentOption,
            paymentStatus = PaymentStatus.SUCCESS,
            orderId = order.id,
            isRefund = true
        )
         paymentRepository.save(transaction)

        order.status = OrderStatus.REFUNDED
        orderRepository.save(order)

        return BaseMessage(200, "Buyurtma muvaffaqiyatli qaytarildi. Tranzaksiya ID: ${transaction.transactionId}")
    }

    private fun calculateSubtotal(items: List<OrderItemRequest>, products: List<Product>): BigDecimal {
        return items.sumOf { item ->
            val product = products.first { it.id == item.productId }
            product.price.multiply(BigDecimal(item.quantity))
        }
    }

    private fun calculateServiceCharge(subtotal: BigDecimal): BigDecimal {
        return subtotal.multiply(BigDecimal("0.05"))
    }

    private fun calculateDeliveryFee(deliveryAddress: String?): BigDecimal {
        return if (deliveryAddress != null) BigDecimal("10000") else BigDecimal.ZERO
    }

    private fun calculateDiscount(subtotal: BigDecimal, customerId: Long): BigDecimal {
        return BigDecimal.ZERO
    }

    private fun validateStatusTransition(currentStatus: OrderStatus, newStatus: OrderStatus) {
        val validTransitions = mapOf(
            OrderStatus.PENDING to setOf(OrderStatus.IN_PROGRESS, OrderStatus.REJECTED, OrderStatus.CANCELLED),
            OrderStatus.IN_PROGRESS to setOf(OrderStatus.ACCEPTED, OrderStatus.CANCELLED),
            OrderStatus.ACCEPTED to setOf(OrderStatus.COMPLETED, OrderStatus.CANCELLED),
            OrderStatus.COMPLETED to emptySet(),
            OrderStatus.REJECTED to emptySet(),
            OrderStatus.CANCELLED to emptySet()
        )

        if (!validTransitions[currentStatus]!!.contains(newStatus)) {
            throw ValidationException("Cannot transition from $currentStatus to $newStatus")
        }
    }

}

@Service
class PaymentServiceImpl(
    private val paymentRepository: PaymentRepository,
    private val orderRepository: OrderRepository,
    private val cardRepository: CardRepository
) : PaymentService {
    
    private val MAX_RETRIES = 3;

    override fun processOrderPayment(userId: Long, orderId: Long?, paymentRequest: PaymentRequest): BaseMessage {
        val order = orderId?.let {
            orderRepository.findById(it).orElseThrow { ResourceNotFoundException(it) }
        }

        order?.let {
            if (it.totalAmount != paymentRequest.amount) {
                throw ValidationException(
                    "To'lov (${paymentRequest.amount}) mos kelmadi (${it.totalAmount})"
                )
            }
        }

        if (paymentRequest.amount <= BigDecimal.ZERO) throw InvalidInputException(MessageKey.NEGATIVE_PRICE)

        order?.let {
            if (it.customerId != userId) {
                throw InvalidInputException()
            }
        }

        order?.let {
            if (it.status == OrderStatus.ACCEPTED) {
                throw ValidationException(order)
            }
        }

        if (paymentRequest.paymentOption == PaymentOption.CARD) {
            val defaultCard = cardRepository.findByUserIdAndIsDefaultTrueAndDeletedFalse(userId)
                ?: throw ValidationException()

            if (defaultCard.balance < paymentRequest.amount) {
                val message = """
                    Yetarli emas ${defaultCard.cardType} kartada. 
                    Bor Balans: ${defaultCard.balance}
                    Kerakli summa: ${paymentRequest.amount}
                    Bo'lmasa boshqa usul orqali to'lang: Naqd yoki boshqa karta qo'shing!
                """.trimIndent()
                throw ValidationException(message)
            }
        }

        var transaction = PaymentTransaction(
            userId = userId,
            amount = paymentRequest.amount,
            paymentOption = paymentRequest.paymentOption,
            paymentStatus = PaymentStatus.PENDING,
            orderId = orderId,
            isRefund = false
        )
        transaction = paymentRepository.save(transaction)

        if (transaction.retryCount >= MAX_RETRIES) {
            throw ValidationException("Max shansdan o'tib ketdi!")
        }
        transaction.retryCount++
        paymentRepository.save(transaction)

        val result = doPayment(paymentRequest)

        if (result && paymentRequest.paymentOption == PaymentOption.CARD) {
            val defaultCard = cardRepository.findByUserIdAndIsDefaultTrueAndDeletedFalse(userId)!!
            defaultCard.balance = defaultCard.balance.subtract(paymentRequest.amount)
            cardRepository.save(defaultCard)
        }

        transaction.paymentStatus = if (result) PaymentStatus.SUCCESS else PaymentStatus.FAILED
        paymentRepository.save(transaction)

        return if (result) {
            BaseMessage(200, "To'lov qabul qilindi. Tranzaksiya ID: ${transaction.transactionId}")
        } else {
            BaseMessage(-1, "To'lov qabul qilinmadi. Transaction ID: ${transaction.transactionId}")
        }
    }

    private fun doPayment(paymentRequest: PaymentRequest): Boolean {
        return when (paymentRequest.paymentOption) {
            PaymentOption.CARD -> Random.nextDouble() < 0.9
            PaymentOption.CASH -> true
        }
    }

    override fun getPaymentHistory(userId: Long): List<PaymentTransactionDTO> {
        return paymentRepository.findAllByUserIdOrderByTransactionTimeDesc(userId).map { it.toDto() }
    }
}

@Service
class CardServiceImpl(
    private val cardRepository: CardRepository,
    private val userRepository: UserRepository,
    private val cardValidator: CardValidator
) : CardService {

    override fun addCard(userId: Long, request: AddCardRequest): CardDTO {
        val user = userRepository.findByIdAndDeletedFalse(userId)
            ?: throw ResourceNotFoundException(userId)

        if (!cardValidator.validateCard(request.cardNumber, request.cardType)) {
            throw InvalidInputException(request.cardType)
        }

        val last4Digits = request.cardNumber.takeLast(4)
        val existingCards = cardRepository.findByUserIdAndDeletedFalse(userId)

        if (existingCards.any { it.cardNumber.takeLast(4) == last4Digits }) {
            throw DuplicateResourceException(last4Digits)
        }

        val isFirstCard = cardRepository.findByUserIdAndDeletedFalse(userId).isEmpty()

        val card = Card(
            cardNumber = maskCardNumber(request.cardNumber),
            expiryDate = request.expiryDate,
            cardHolderName = request.cardHolderName,
            isDefault = isFirstCard,
            cardType = request.cardType,
            balance = BigDecimal.ZERO,
            user = user
        )

        return cardRepository.save(card).toDto()
    }

    override fun updateCardBalance(userId: Long, cardId: Long, amount: BigDecimal): CardDTO {
        if (amount < BigDecimal.ZERO) {
            throw InvalidInputException(amount)
        }

        val card = cardRepository.findByIdAndUserIdAndDeletedFalse(cardId, userId)
            ?: throw ResourceNotFoundException(cardId)

        card.balance = card.balance.add(amount)
        return cardRepository.save(card).toDto()
    }

    override fun getUserCards(userId: Long): List<CardDTO> {
        return cardRepository.findByUserIdAndDeletedFalse(userId).map { it.toDto() }
    }

    override fun setDefaultCard(userId: Long, cardId: Long): CardDTO {
        val card = cardRepository.findByIdAndUserIdAndDeletedFalse(cardId, userId)
            ?: throw ResourceNotFoundException(cardId)

        cardRepository.findByUserIdAndIsDefaultTrueAndDeletedFalse(userId)?.let {
            it.isDefault = false
            cardRepository.save(it)
        }

        card.isDefault = true
        return cardRepository.save(card).toDto()
    }

    override fun deleteCard(userId: Long, cardId: Long) {
        val card = cardRepository.findByIdAndUserIdAndDeletedFalse(cardId, userId)
            ?: throw ResourceNotFoundException(cardId)

        card.deleted = true
        cardRepository.save(card)
    }

    private fun maskCardNumber(number: String): String {
        return "*".repeat(12) + number.takeLast(4)
    }
}
