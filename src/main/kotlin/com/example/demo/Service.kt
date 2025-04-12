package com.example.demo

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.itextpdf.text.*
import com.itextpdf.text.pdf.PdfPCell
import com.itextpdf.text.pdf.PdfPTable
import com.itextpdf.text.pdf.PdfWriter
import jakarta.transaction.Transactional
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.hibernate.Hibernate
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.context.NoSuchMessageException
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*
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
    fun requestOtp(otpRequest: OtpRequest, chatId: Long?): OtpIdResponse
    fun otpLogin(otpLogin: OtpLogin, chatId: Long): TokenResponse
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
    fun getProductsByCategoryId(id: Long): List<Product>
    fun deleteProductById(id: Long)
    fun getNewlyAddedProducts(pageable: Pageable): Page<ProductDTO>
    fun searchProducts(
        name: String?,
        categoryId: Long?,
        minPrice: BigDecimal?,
        maxPrice: BigDecimal?,
        pageable: Pageable
    ): Page<ProductDTO>
}

interface OrderService {
    fun createOrder(createOrderRequest: CreateOrderRequest): OrderDTO
    fun createOrder(userId: Long, createOrderRequest: CreateOrderRequest): OrderDTO
    fun getOrderById(orderId: Long): OrderDTO
    fun getCustomerOrders(customerId: Long, pageable: Pageable): Page<OrderDTO>
    fun getRestaurantOrders(restaurantId: Long, pageable: Pageable): Page<OrderDTO>
    fun updateOrderStatus(orderId: Long, status: OrderStatus): OrderDTO
    fun processPayment(orderId: Long, paymentRequest: PaymentRequest): BaseMessage
    fun refundOrder(orderId: Long): BaseMessage
    fun searchOrders(
        startDate: LocalDate?,
        endDate: LocalDate?,
        status: OrderStatus?,
        paymentType: PaymentOption?,
        orderId: Long?,
        customerId: Long?,
        restaurantId: Long?,
        pageable: Pageable
    ): Page<OrderDTO>
    fun downloadOrdersAsExcel(
        startDate: LocalDate?,
        endDate: LocalDate?,
        status: OrderStatus?,
        paymentType: PaymentOption?,
        orderId: Long?,
        customerId: Long?,
        restaurantId: Long?
    ): ByteArray
    fun downloadOrdersAsPdf(
        startDate: LocalDate?,
        endDate: LocalDate?,
        status: OrderStatus?,
        paymentType: PaymentOption?,
        orderId: Long?,
        customerId: Long?,
        restaurantId: Long?
    ): ByteArray
    fun downloadOrdersAsCsv(
        startDate: LocalDate?,
        endDate: LocalDate?,
        status: OrderStatus?,
        paymentType: PaymentOption?,
        orderId: Long?,
        customerId: Long?,
        restaurantId: Long?
    ): ByteArray
    fun downloadOrdersAsJson(
        startDate: LocalDate?,
        endDate: LocalDate?,
        status: OrderStatus?,
        paymentType: PaymentOption?,
        orderId: Long?,
        customerId: Long?,
        restaurantId: Long?
    ): ByteArray
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

interface AddressService {
    fun addAddress(addressRequest: AddressRequest): AddressDTO
    fun getUserAddresses(userId: Long): List<AddressDTO>
    fun getAddressById(addressId: Long): AddressDTO
    fun updateAddress(addressId: Long, addressRequest: AddressRequest): AddressDTO
    fun deleteAddress(addressId: Long)
}

interface DownloadService {
    fun <T> generateExcel(data: List<T>, headers: List<String>, valueExtractors: List<(T) -> Any?>): ByteArray
    fun <T> generatePdf(data: List<T>, headers: List<String>, valueExtractors: List<(T) -> Any?>): ByteArray
    fun <T> generateCsv(data: List<T>, headers: List<String>, valueExtractors: List<(T) -> Any?>): ByteArray
    fun <T> generateJson(data: List<T>): ByteArray
}

interface DashboardService {
    fun getTotalSalesForCurrMonth(): Double
    fun mostSoldProducts(): List<Product>
    fun getSalesBetweenDates(startDate: LocalDate, endDate: LocalDate): Double
    fun getTotalSalesForCurrDay(): Double?
    fun getAverageDailySales(startDate: LocalDate, endDate: LocalDate): Double
    fun getLeastSoldProducts(): List<Product>
    fun getTopBuyers(limit: Int): List<Map<String, Any>>
    fun getLeastSellingProductsFor30Days(): List<LeastProductsResponse>
}

interface CartService {
    fun addItemToCart(chatId: Long, productId: Long, quantity: Int)
    fun updateItemQuantity(chatId: Long, productId: Long, quantity: Int)
    fun removeItemFromCart(chatId: Long, productId: Long)
    fun getCart(chatId: Long): CartDTO
    fun clearCart(chatId: Long)
}

interface UserStateService {
    fun getCurrentState(chatId: Long): BotState
    fun setState(chatId: Long, state: BotState)
    fun setPreviousState(chatId: Long, state: BotState)
    fun getPreviousState(chatId: Long): BotState
    fun getMenuState(chatId: Long): MenuStates
    fun setMenuState(chatId: Long, menuState: MenuStates)
    fun setTemporaryData(chatId: Long, key: String, value: String)
    fun getTemporaryData(chatId: Long, key: String): String?
    fun clearTemporaryData(chatId: Long)
}

interface LocaleService {
    fun getUserLocale(chatId: Long): Locale
    fun setUserLocale(chatId: Long, languageCode: String)
}

interface LocalizedMessageService {
    // New methods using MessageKey enum
    fun getMessage(key: MessageKey, chatId: Long, vararg args: Any): String
    fun getMessage(key: MessageKey, locale: Locale, vararg args: Any): String
    
    // Keep original methods for backward compatibility
    fun getMessage(key: String, chatId: Long, vararg args: Any): String
    fun getMessage(key: String, locale: Locale, vararg args: Any): String
}

@Service
class AuthServiceImpl(
    private val otpService: OTPService,
    private val userRepository: UserRepository,
    private val jwtUtils: JwtUtils,
    private val passwordEncoder: PasswordEncoder,
    private val userStateRepository: UserStateRepository,
) : AuthService {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun requestOtp(otpRequest: OtpRequest, chatId: Long?): OtpIdResponse {
        val phoneNumber = otpRequest.phoneNumber
        if (!validatePhoneNumber(phoneNumber)) throw InvalidInputException(phoneNumber)

        // First check if there's an existing user with this chatId
        val user = if (chatId != null) userRepository.findByTelegramChatId(chatId) else null

        // Instead of directly updating the phone number, store it temporarily
        // We'll store it in the user's temporary data until verified
        if (user != null) {
            // Store temporarily, but don't update user record yet
            val userState = userStateRepository.findByUserId(user.id!!) ?: run {
                val newState = UserState(user = user)
                userStateRepository.save(newState)
            }

            // Store phone number as temporary data
            val tempData = userState.temporaryData
            val dataMap = if (tempData.isNullOrEmpty()) {
                mutableMapOf<String, String>()
            } else {
                try {
                    val objectMapper = ObjectMapper()
                    objectMapper.readValue(tempData, object : TypeReference<MutableMap<String, String>>() {})
                } catch (e: Exception) {
                    mutableMapOf<String, String>()
                }
            }

            dataMap["pending_phone_number"] = phoneNumber
            userState.temporaryData = ObjectMapper().writeValueAsString(dataMap)
            userStateRepository.save(userState)

            logger.info("Stored pending phone number $phoneNumber for verification")
        } else {
            // User doesn't exist with this chatId
            logger.error("No user found for chatId $chatId during OTP request")
            throw ResourceNotFoundException(chatId)
        }

        val otpId = otpService.generateOTP(phoneNumber)
        return OtpIdResponse(otpId, "OTP sent to your phone")
    }

    override fun otpLogin(otpLogin: OtpLogin, chatId: Long): TokenResponse {
        val validOtp = otpService.validateOTP(otpLogin.phoneNumber, otpLogin.otp, otpLogin.otpId)
        if (!validOtp) {
            throw InvalidInputException(otpLogin.otp)
        }

        val user = userRepository.findByTelegramChatId(chatId)
            ?: throw ResourceNotFoundException("User not found for chat ID: $chatId")

        user.phoneNumber = otpLogin.phoneNumber
        userRepository.save(user)

        return jwtUtils.generateToken(user)
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
    private val MAX_ATTEMPTS = 10
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
}


@Service
class AddressServiceImpl(
    private val addressRepository: AddressRepository,
    private val userRepository: UserRepository
) : AddressService {
    override fun addAddress(addressRequest: AddressRequest): AddressDTO {
        val user = getCurrentUser(userRepository)

        val address = Address(
            addressLine = addressRequest.addressLine,
            city = addressRequest.city,
            longitude = addressRequest.longitude,
            latitude = addressRequest.latitude,
            user = user
        )
        return addressRepository.save(address).toDto()
    }

    override fun getUserAddresses(userId: Long): List<AddressDTO> {
        return addressRepository.findAllByUserIdAndDeletedFalse(userId).map { it.toDto() }
    }

    override fun getAddressById(addressId: Long): AddressDTO {
        val currentUser = getCurrentUser(userRepository)
        val address = addressRepository.findByIdAndDeletedFalse(addressId)
            ?: throw ResourceNotFoundException(addressId)

        if (address.user?.id != currentUser.id) throw ForbiddenException()

        return address.toDto()
    }

    override fun updateAddress(addressId: Long, addressRequest: AddressRequest): AddressDTO {
        val currentUser = getCurrentUser(userRepository)
        val address = addressRepository.findByIdAndDeletedFalse(addressId)
            ?: throw ResourceNotFoundException(addressId)

        if (address.user?.id != currentUser.id) {
            throw ForbiddenException()
        }

        address.addressLine = addressRequest.addressLine
        address.city = addressRequest.city
        address.longitude = addressRequest.longitude
        address.latitude = addressRequest.latitude

        return addressRepository.save(address).toDto()
    }

    override fun deleteAddress(addressId: Long) {
        val address = addressRepository.findByIdAndDeletedFalse(addressId)
            ?: throw ResourceNotFoundException(addressId)
        address.deleted = true
        addressRepository.save(address)
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
            nameUz = createCategoryRequest.nameUz!!,
            nameRu = createCategoryRequest.nameRu!!,
            description = createCategoryRequest.description?: "",
            descriptionUz = createCategoryRequest.descriptionUz,
            descriptionRu = createCategoryRequest.descriptionRu,
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
            nameUz = createProductRequest.nameUz!!,
            nameRu = createProductRequest.nameRu!!,
            description = createProductRequest.description ?: "",
            descriptionUz = createProductRequest.descriptionUz,
            descriptionRu = createProductRequest.descriptionRu,
            price = createProductRequest.price,
            currency = createProductRequest.currency.currencyCode,
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

    override fun getProductsByCategoryId(id: Long): List<Product> {
        return productRepository.findByCategoryIdAndDeletedFalse(id)
    }

    override fun deleteProductById(id: Long) {
        val product = productRepository.findById(id).orElseThrow { ResourceNotFoundException(id) }
        product.deleted = true
        productRepository.save(product)
    }

    override fun getNewlyAddedProducts(pageable: Pageable): Page<ProductDTO> {
        return productRepository.findAllSortByCreatedDateDesc(pageable).map { it.toDto() }
    }

    override fun searchProducts(
        name: String?,
        categoryId: Long?,
        minPrice: BigDecimal?,
        maxPrice: BigDecimal?,
        pageable: Pageable
    ): Page<ProductDTO> {
        var spec: Specification<Product> = Specification.where(null)

        name?.let {
            spec = spec.and { root, _, criteriaBuilder ->
                criteriaBuilder.like(
                    criteriaBuilder.lower(root.get("name")),
                    "%${it.lowercase()}%"
                )
            }
        }

        categoryId?.let {
            spec = spec.and { root, _, criteriaBuilder ->
                criteriaBuilder.equal(root.get<Category>("category").get<Long>("id"), it)
            }
        }

        if (minPrice != null && maxPrice != null) {
            spec = spec.and { root, _, criteriaBuilder ->
                criteriaBuilder.between(root.get("price"), minPrice, maxPrice)
            }
        } else if (minPrice != null) {
            spec = spec.and { root, _, criteriaBuilder ->
                criteriaBuilder.greaterThanOrEqualTo(root.get("price"), minPrice)
            }
        } else if (maxPrice != null) {
            spec = spec.and { root, _, criteriaBuilder ->
                criteriaBuilder.lessThanOrEqualTo(root.get("price"), maxPrice)
            }
        }

        spec = spec.and { root, _, criteriaBuilder ->
            criteriaBuilder.equal(root.get<Boolean>("deleted"), false)
        }

        return productRepository.findAll(spec, pageable).map { it.toDto() }
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
    private val userRepository: UserRepository,
    private val downloadService: DownloadServiceImpl
) : OrderService {
    @Transactional
    override fun createOrder(createOrderRequest: CreateOrderRequest): OrderDTO {
        val user = getCurrentUser(userRepository)

        val restaurant = restaurantRepository.findByIdAndDeletedFalse(createOrderRequest.restaurantId)
            ?: throw ResourceNotFoundException(createOrderRequest.restaurantId)

        val address = addressRepository.findByIdAndDeletedFalse(createOrderRequest.addressId)
            ?: throw ResourceNotFoundException(createOrderRequest.addressId)

        if (address.user?.id != user.id) {
            throw ForbiddenException(address.user?.id)
        }

        val productIds = createOrderRequest.items.map { it.productId }
        val products = productRepository.findAllByIdAndDeletedFalse(productIds)

        val foundIds = products.map { it.id }.toSet()
        val missingIds = productIds.filterNot { foundIds.contains(it) }

        if (missingIds.isNotEmpty()) throw ResourceNotFoundException(missingIds)

        val subtotal = calculateSubtotal(createOrderRequest.items, products)
        val serviceCharge = calculateServiceCharge(subtotal)
        val deliveryFee = calculateDeliveryFee(address.toString())
        val totalAmount = subtotal.add(serviceCharge).add(deliveryFee).subtract(BigDecimal.TEN)

        products.forEach { product ->
            if (product.category.restaurant.id != restaurant.id) throw InvalidInputException(
                product.id
            )
        }

        val order = Order(
            user = user,
            restaurant = restaurant,
            paymentOption = createOrderRequest.paymentOption,
            status = OrderStatus.PENDING,
            address = address,
            orderDate = LocalDate.now(),
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

        paymentService.processOrderPayment(
            user.id!!,
            savedOrder.id,
            PaymentRequest(savedOrder.totalAmount, savedOrder.paymentOption)
        )

        return savedOrder.toDto()
    }

    @Transactional
    override fun createOrder(userId: Long, createOrderRequest: CreateOrderRequest): OrderDTO {
        val user = userRepository.findByIdAndDeletedFalse(userId)

        val restaurant = restaurantRepository.findByIdAndDeletedFalse(createOrderRequest.restaurantId)
            ?: throw ResourceNotFoundException(createOrderRequest.restaurantId)

        val address = addressRepository.findByIdAndDeletedFalse(createOrderRequest.addressId)
            ?: throw ResourceNotFoundException(createOrderRequest.addressId)

        if (address.user?.id != user?.id) {
            throw ForbiddenException(address.user?.id)
        }

        val productIds = createOrderRequest.items.map { it.productId }
        val products = productRepository.findAllByIdAndDeletedFalse(productIds)

        val foundIds = products.map { it.id }.toSet()
        val missingIds = productIds.filterNot { foundIds.contains(it) }

        if (missingIds.isNotEmpty()) throw ResourceNotFoundException(missingIds)

        val subtotal = calculateSubtotal(createOrderRequest.items, products)
        val serviceCharge = calculateServiceCharge(subtotal)
        val deliveryFee = calculateDeliveryFee(address.toString())
        val totalAmount = subtotal.add(serviceCharge).add(deliveryFee).subtract(BigDecimal.TEN)

        products.forEach { product ->
            if (product.category.restaurant.id != restaurant.id) throw InvalidInputException(
                product.id
            )
        }

        val order = Order(
            user = user!!,
            restaurant = restaurant,
            paymentOption = createOrderRequest.paymentOption,
            status = OrderStatus.PENDING,
            address = address,
            orderDate = LocalDate.now(),
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

        paymentService.processOrderPayment(
            user.id!!,
            savedOrder.id,
            PaymentRequest(savedOrder.totalAmount, savedOrder.paymentOption)
        )

        return savedOrder.toDto()
    }

    override fun getOrderById(orderId: Long): OrderDTO {
        return orderRepository.findByIdAndDeletedFalse(orderId)?.toDto() ?: throw ResourceNotFoundException(orderId)
    }

    override fun getCustomerOrders(customerId: Long, pageable: Pageable): Page<OrderDTO> {
        return orderRepository.findByUserIdAndDeletedFalse(customerId, pageable).map { it.toDto() }
    }

    override fun getRestaurantOrders(restaurantId: Long, pageable: Pageable): Page<OrderDTO> {
        return orderRepository.findByRestaurantId(restaurantId, pageable).map { it.toDto() }
    }

    override fun updateOrderStatus(orderId: Long, status: OrderStatus): OrderDTO {
        val order = orderRepository.findByIdAndDeletedFalse(orderId) ?: throw ResourceNotFoundException(orderId)

        validateStatusTransition(order.status, status)

        if (order.status != OrderStatus.COMPLETED) {
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

        if (order.status != OrderStatus.PENDING) {
            throw ValidationException(order.status)
        }

        if (order.totalAmount != paymentRequest.amount) {
            throw ValidationException(paymentRequest.amount)
        }

        val result = paymentService.processOrderPayment(
            order.user.id!!, orderId, paymentRequest
        )
        if (result.code == 200) {
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
            val defaultCard = cardRepository.findByUserIdAndIsDefaultTrueAndDeletedFalse(order.user.id!!)
            defaultCard?.let {
                it.balance = it.balance.add(order.totalAmount)
                cardRepository.save(it)
            }
        } else if (order.paymentOption == PaymentOption.CASH) throw InvalidInputException(order.paymentOption)

        val transaction = PaymentTransaction(
            userId = order.user.id!!,
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

    override fun searchOrders(
        startDate: LocalDate?,
        endDate: LocalDate?,
        status: OrderStatus?,
        paymentType: PaymentOption?,
        orderId: Long?,
        customerId: Long?,
        restaurantId: Long?,
        pageable: Pageable
    ): Page<OrderDTO> {
        var spec: Specification<Order> = Specification.where(null)//dynamic searching uchun kerak boladi

        if (startDate != null && endDate != null) {
            spec = spec.and { root, _, criteriaBuilder ->
                criteriaBuilder.between(root.get("orderDate"), startDate, endDate)
            }
        } else if (startDate != null) {
            spec = spec.and { root, _, criteriaBuilder ->
                criteriaBuilder.greaterThanOrEqualTo(root.get("orderDate"), startDate)
            }
        } else if (endDate != null) {
            spec = spec.and { root, _, criteriaBuilder ->
                criteriaBuilder.lessThanOrEqualTo(root.get("orderDate"), endDate)
            }
        }

        status?.let {
            spec = spec.and { root, _, criteriaBuilder ->
                criteriaBuilder.equal(root.get<OrderStatus>("status"), it)
            }
        }

        paymentType?.let {
            spec = spec.and { root, _, criteriaBuilder ->
                criteriaBuilder.equal(root.get<PaymentOption>("paymentOption"), it)
            }
        }

        orderId?.let {
            spec = spec.and { root, _, criteriaBuilder ->
                criteriaBuilder.equal(root.get<Long>("id"), it)
            }
        }

        customerId?.let {
            spec = spec.and { root, _, criteriaBuilder ->
                criteriaBuilder.equal(root.get<User>("user").get<Long>("id"), it)
            }
        }

        restaurantId?.let {
            spec = spec.and { root, _, criteriaBuilder ->
                criteriaBuilder.equal(root.get<Restaurant>("restaurant").get<Long>("id"), it)
            }
        }

        spec = spec.and { root, _, criteriaBuilder ->
            criteriaBuilder.equal(root.get<Boolean>("deleted"), false)
        }

        return orderRepository.findAll(spec, pageable).map { it.toDto() }
    }

    override fun downloadOrdersAsExcel(
        startDate: LocalDate?,
        endDate: LocalDate?,
        status: OrderStatus?,
        paymentType: PaymentOption?,
        orderId: Long?,
        customerId: Long?,
        restaurantId: Long?
    ): ByteArray {
        val pageable = PageRequest.of(0, Int.MAX_VALUE)
        val orders = searchOrders(startDate, endDate, status, paymentType, orderId, customerId, restaurantId, pageable).content

        val headers = listOf("Order ID", "Customer", "Restaurant", "Status", "Payment", "Total", "Date")
        val valueExtractors: List<(OrderDTO) -> Any?> = listOf(
            { it.id },
            { it.userId},
            { it.restaurantId },
            { it.status },
            { it.paymentOption },
            { it.totalAmount },
            { it.orderDate }
        )

        return downloadService.generateExcel(orders, headers, valueExtractors)
    }

    override fun downloadOrdersAsPdf(
        startDate: LocalDate?,
        endDate: LocalDate?,
        status: OrderStatus?,
        paymentType: PaymentOption?,
        orderId: Long?,
        customerId: Long?,
        restaurantId: Long?
    ): ByteArray {
        val pageable = PageRequest.of(0, Int.MAX_VALUE)
        val orders = searchOrders(startDate, endDate, status, paymentType, orderId, customerId, restaurantId, pageable).content

        val headers = listOf("Order ID", "Customer", "Restaurant", "Status", "Payment", "Total", "Date")
        val valueExtractors: List<(OrderDTO) -> Any?> = listOf(
            { it.id },
            { it.userId},
            { it.restaurantId },
            { it.status },
            { it.paymentOption },
            { it.totalAmount },
            { it.orderDate }
        )

        return downloadService.generatePdf(orders, headers, valueExtractors)
    }

    override fun downloadOrdersAsCsv(
        startDate: LocalDate?,
        endDate: LocalDate?,
        status: OrderStatus?,
        paymentType: PaymentOption?,
        orderId: Long?,
        customerId: Long?,
        restaurantId: Long?
    ): ByteArray {
        val pageable = PageRequest.of(0, Int.MAX_VALUE)
        val orders = searchOrders(startDate, endDate, status, paymentType, orderId, customerId, restaurantId, pageable).content

        val headers = listOf("Order ID", "Customer", "Restaurant", "Status", "Payment", "Total", "Date")
        val valueExtractors: List<(OrderDTO) -> Any?> = listOf(
            { it.id },
            { it.userId},
            { it.restaurantId },
            { it.status },
            { it.paymentOption },
            { it.totalAmount },
            { it.orderDate }
        )

        return downloadService.generateCsv(orders, headers, valueExtractors)
    }

    override fun downloadOrdersAsJson(
        startDate: LocalDate?,
        endDate: LocalDate?,
        status: OrderStatus?,
        paymentType: PaymentOption?,
        orderId: Long?,
        customerId: Long?,
        restaurantId: Long?
    ): ByteArray {
        val pageable = PageRequest.of(0, Int.MAX_VALUE)
        val orders = searchOrders(startDate, endDate, status, paymentType, orderId, customerId, restaurantId, pageable).content
        return downloadService.generateJson(orders)
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
            if (it.user.id != userId) {
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

@Service
class DownloadServiceImpl(
    private val objectMapper: ObjectMapper
) : DownloadService {

    override fun <T> generateExcel(
        data: List<T>,
        headers: List<String>,
        valueExtractors: List<(T) -> Any?>
    ): ByteArray {
        if (headers.size != valueExtractors.size) throw IllegalArgumentException()

        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Data")

        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { index, header ->
            headerRow.createCell(index).setCellValue(header)
        }//bu row yaratib chiqebdi

        data.forEachIndexed { rowIndex, item ->
            val row = sheet.createRow(rowIndex + 1)
            valueExtractors.forEachIndexed { colIndex, extractor ->
                val cell = row.createCell(colIndex)
                when (val value = extractor(item)) {
                    null -> cell.cellType = CellType.BLANK
                    is String -> cell.setCellValue(value)
                    is Number -> cell.setCellValue(value.toDouble())
                    is Boolean -> cell.setCellValue(value)
                    is LocalDate -> cell.setCellValue(value.toString())
                    is LocalDateTime -> cell.setCellValue(value.toString())
                    else -> cell.setCellValue(value.toString())
                }
            }
        }

        for (i in headers.indices) {
            sheet.autoSizeColumn(i)
        }

        val outputStream = ByteArrayOutputStream()
        workbook.write(outputStream)
        workbook.close()//har doim yopish kerak ekan

        return outputStream.toByteArray()
    }

    override fun <T> generatePdf(
        data: List<T>,
        headers: List<String>,
        valueExtractors: List<(T) -> Any?>
    ): ByteArray {
        if (headers.size != valueExtractors.size) throw IllegalArgumentException()

        val document = Document(PageSize.A4.rotate())
        val outputStream = ByteArrayOutputStream()
        PdfWriter.getInstance(document, outputStream)

        document.open()
        val table = PdfPTable(headers.size)
        table.widthPercentage = 100f

        headers.forEach { header ->
            val cell = PdfPCell(Phrase(header))
            cell.horizontalAlignment = Element.ALIGN_CENTER
            cell.backgroundColor = BaseColor.LIGHT_GRAY
            table.addCell(cell)
        }

        data.forEach { item ->
            valueExtractors.forEach { extractor ->
                val value = extractor(item)?.toString() ?: ""
                table.addCell(value)
            }
        }//data shu yerda extract bolebdi

        document.add(table)
        document.close()

        return outputStream.toByteArray()
    }

    override fun <T> generateCsv(
        data: List<T>,
        headers: List<String>,
        valueExtractors: List<(T) -> Any?>
    ): ByteArray {
        if (headers.size != valueExtractors.size) throw IllegalArgumentException()

        val outputStream = ByteArrayOutputStream()
        val writer = outputStream.writer(StandardCharsets.UTF_8)
        val csvPrinter = CSVPrinter(writer, CSVFormat.DEFAULT.withHeader(*headers.toTypedArray()))

        data.forEach { item ->
            csvPrinter.printRecord(valueExtractors.map { extractor -> extractor(item)?.toString() ?: "" })
        }

        csvPrinter.flush()
        csvPrinter.close()

        return outputStream.toByteArray()
    }

    override fun <T> generateJson(data: List<T>): ByteArray {
        return objectMapper.writeValueAsBytes(data)
    }
}

@Service
class DashboardServiceImpl(
    private val orderItemRepository: OrderItemRepository,
    private val orderRepository: OrderRepository
): DashboardService {
    override fun getTotalSalesForCurrMonth(): Double {
        return orderRepository.findTotalSalesForCurrentMonth().toDouble()
    }

    override fun mostSoldProducts(): List<Product> {
        return orderItemRepository.findMostSoldProducts()
    }

    override fun getSalesBetweenDates(startDate: LocalDate, endDate: LocalDate): Double {
        return orderRepository.findSalesBetweenDates(startDate, endDate).toDouble()
    }

    override fun getTotalSalesForCurrDay(): Double? {
        return orderRepository.findTotalSalesForCurrentDay()?.toDouble()?: throw ResourceNotFoundException()
    }

    override fun getAverageDailySales(startDate: LocalDate, endDate: LocalDate): Double {
        val dailySales = orderRepository.findDailySales(startDate, endDate)
        if (dailySales.isEmpty()) return 0.0

        val totalAmount = dailySales.sumOf { (it[0] as BigDecimal).toDouble() }
        return totalAmount / dailySales.size
    }

    override fun getLeastSoldProducts(): List<Product> {
        return orderItemRepository.findLeastSoldProducts()
    }

    override fun getTopBuyers(limit: Int): List<Map<String, Any>> {
        val pageable = PageRequest.of(0, limit)
        val results = orderRepository.findTopBuyers(pageable)

        return results.map { row ->
            mapOf(
                "user" to (row[0] as User).toDto(),
                "orderCount" to (row[1] as Long),
                "totalSpent" to (row[2] as BigDecimal)
            )
        }
    }

    override fun getLeastSellingProductsFor30Days(): List<LeastProductsResponse> {
        return orderItemRepository.findLeastSoldProductsForLast30Days()
    }
}

@Service
@Transactional
class CartServiceImpl(
    private val cartRepository: CartRepository,
    private val userRepository: UserRepository,
    private val productRepository: ProductRepository
) : CartService {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun getCart(chatId: Long): CartDTO {
        try {
            logger.debug("Getting cart for chatId: {}", chatId)

            // Find user by Telegram chatId
            val user = userRepository.findByTelegramChatId(chatId)
                ?: throw ResourceNotFoundException("User not found for chatId: $chatId")

            // Get or create cart with items eagerly fetched
            val cart = cartRepository.findByUserIdAndDeletedFalse(user.id!!) ?: return CartDTO(emptyList())

            // Use Hibernate.initialize to force initialization of the items collection
            Hibernate.initialize(cart.items)

            // Map to CartDTO
            return CartDTO(
                items = cart.items.map { item ->
                    CartItemDTO(
                        product = item.product.toDto(),
                        quantity = item.quantity
                    )
                }
            )
        } catch (e: Exception) {
            logger.error("Error retrieving cart for chatId {}: {}", chatId, e.message, e)
            throw RuntimeException("Failed to retrieve cart: ${e.message}")
        }
    }

    override fun addItemToCart(chatId: Long, productId: Long, quantity: Int) {
        val user = userRepository.findByTelegramChatId(chatId)
            ?: throw ResourceNotFoundException("User not found for chat ID: $chatId")

        var cart = cartRepository.findByUserIdAndDeletedFalse(user.id!!)
        if (cart == null) {
            cart = Cart(user = user)
            cartRepository.save(cart)
        }

        val product = productRepository.findByIdAndDeletedFalse(productId)
            ?: throw ResourceNotFoundException("Product not found: $productId")

        // Check if item already exists in cart
        val existingItem = cart.items.find { it.product.id == productId }
        if (existingItem != null) {
            existingItem.quantity += quantity
        } else {
            val cartItem = CartItem(
                cart = cart,
                product = product,
                quantity = quantity
            )
            cart.items.add(cartItem)
        }

        cartRepository.save(cart)
    }

    override fun updateItemQuantity(chatId: Long, productId: Long, quantity: Int) {
        if (quantity <= 0) {
            removeItemFromCart(chatId, productId)
            return
        }

        val user = userRepository.findByTelegramChatId(chatId)
            ?: throw ResourceNotFoundException("User not found for chat ID: $chatId")

        val cart = cartRepository.findByUserIdAndDeletedFalse(user.id!!)
            ?: throw ResourceNotFoundException("Cart not found for user")

        val cartItem = cart.items.find { it.product.id == productId }
            ?: throw ResourceNotFoundException("Item not found in cart")

        cartItem.quantity = quantity
        cartRepository.save(cart)
    }

    override fun removeItemFromCart(chatId: Long, productId: Long) {
        val user = userRepository.findByTelegramChatId(chatId)
            ?: throw ResourceNotFoundException("User not found for chat ID: $chatId")

        val cart = cartRepository.findByUserIdAndDeletedFalse(user.id!!)
            ?: return

        val itemToRemove = cart.items.find { it.product.id == productId } ?: return
        cart.items.remove(itemToRemove)
        cartRepository.save(cart)
    }

    override fun clearCart(chatId: Long) {
        val user = userRepository.findByTelegramChatId(chatId)
            ?: throw ResourceNotFoundException("User not found for chat ID: $chatId")

        val cart = cartRepository.findByUserIdAndDeletedFalse(user.id!!) ?: return
        cart.items.clear()
        cartRepository.save(cart)
    }
}

@Service
class LocalizedMessageServiceImpl(
    private val messageSource: MessageSource,
    private val localeService: LocaleService
) : LocalizedMessageService {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun getMessage(key: String, chatId: Long, vararg args: Any): String {
        val userLocale = localeService.getUserLocale(chatId)
        return getMessage(key, userLocale, *args)
    }

    override fun getMessage(key: String, locale: Locale, vararg args: Any): String {
        return try {
            messageSource.getMessage(key, args, locale)
        } catch (e: NoSuchMessageException) {
            logger.warn("Missing translation for key '{}' and locale '{}'. Using key as fallback.", key, locale)
            key
        } catch (e: Exception) {
            logger.error("Error retrieving message for key '{}', locale '{}': {}", key, locale, e.message)
            key
        }
    }

    override fun getMessage(key: MessageKey, chatId: Long, vararg args: Any): String {
        val userLocale = localeService.getUserLocale(chatId)
        return getMessage(key, userLocale, *args)
    }

    override fun getMessage(key: MessageKey, locale: Locale, vararg args: Any): String {
        return try {
            messageSource.getMessage(key.name, args, locale)
        } catch (e: NoSuchMessageException) {
            logger.warn("Missing translation for key '{}' and locale '{}'. Using key as fallback.", key, locale)
            key.name
        } catch (e: Exception) {
            logger.error("Error retrieving message for key '{}', locale '{}': {}", key, locale, e.message)
            key.name
        }
    }
}

@Service
class UserStateServiceImpl(
    private val userStateRepository: UserStateRepository,
    private val userRepository: UserRepository
) : UserStateService {
    private val objectMapper = ObjectMapper()
    private val logger = LoggerFactory.getLogger(javaClass)

    fun getUserState(chatId: Long): UserState {
        val user = userRepository.findByTelegramChatId(chatId) ?: throw ResourceNotFoundException(chatId)

        return userStateRepository.findByUserId(user.id!!) ?: run {
            val newState = UserState(user = user)
            userStateRepository.save(newState)
        }
    }

    override fun getCurrentState(chatId: Long): BotState {
        return try {
            val user = userRepository.findByTelegramChatId(chatId)
            if (user == null) {
                logger.warn("No user found for chat $chatId, returning START state")
                return BotState.START
            }

            val userState = userStateRepository.findByUserId(user.id!!)
            userState?.currentState ?: BotState.START
        } catch (e: Exception) {
            logger.warn("Error getting state for chat $chatId: ${e.message}")
            BotState.START
        }
    }

    override fun setState(chatId: Long, state: BotState) {
        try {
            val user = userRepository.findByTelegramChatId(chatId)
            if (user == null) {
                logger.warn("Cannot set state for chat $chatId: User not found")
                return
            }

            var userState = userStateRepository.findByUserId(user.id!!)
            if (userState == null) {
                userState = UserState(user = user)
            }

            userState.previousState = userState.currentState
            userState.currentState = state
            userStateRepository.save(userState)
        } catch (e: Exception) {
            logger.warn("Could not set state for chat $chatId: ${e.message}")
        }
    }

    override fun setPreviousState(chatId: Long, state: BotState) {
        try {
            val userState = getUserState(chatId)
            userState.previousState = state
            userStateRepository.save(userState)
        } catch (e: Exception) {
            logger.warn("Could not set previous state for chat $chatId: ${e.message}")
        }
    }

    override fun getPreviousState(chatId: Long): BotState {
        return try {
            getUserState(chatId).previousState ?: BotState.START
        } catch (e: Exception) {
            BotState.START
        }
    }

    override fun getMenuState(chatId: Long): MenuStates {
        return try {
            getUserState(chatId).menuState
        } catch (e: Exception) {
            MenuStates.MAIN_MENU
        }
    }

    override fun setMenuState(chatId: Long, menuState: MenuStates) {
        try {
            val userState = getUserState(chatId)
            userState.menuState = menuState
            userStateRepository.save(userState)
        } catch (e: Exception) {
            logger.warn("Could not set menu state for chat $chatId: ${e.message}")
        }
    }

    override fun getTemporaryData(chatId: Long, key: String): String? {
        try {
            val userState = getUserState(chatId)
            if (userState.temporaryData.isNullOrEmpty()) return null

            val dataMap = objectMapper.readValue(userState.temporaryData, object : TypeReference<Map<String, String>>() {})
            return dataMap[key]
        } catch (e: Exception) {
            logger.warn("Could not get temporary data for chat $chatId: ${e.message}")
            return null
        }
    }

    override fun setTemporaryData(chatId: Long, key: String, value: String) {
        try {
            val userState = getUserState(chatId)
            val dataMap = if (userState.temporaryData.isNullOrEmpty()) {
                mutableMapOf()
            } else {
                try {
                    objectMapper.readValue(userState.temporaryData, object : TypeReference<MutableMap<String, String>>() {})
                } catch (e: Exception) {
                    mutableMapOf()
                }
            }

            dataMap[key] = value
            userState.temporaryData = objectMapper.writeValueAsString(dataMap)
            userStateRepository.save(userState)
        } catch (e: Exception) {
            logger.warn("Could not set temporary data for chat $chatId: ${e.message}")
        }
    }

    override fun clearTemporaryData(chatId: Long) {
        try {
            val userState = getUserState(chatId)
            userState.temporaryData = null
            userStateRepository.save(userState)
        } catch (e: Exception) {
            logger.warn("Could not clear temporary data for chat $chatId: ${e.message}")
        }
    }
}

@Service
class LocaleServiceImpl(
    private val userRepository: UserRepository,
    private val userStateRepository: UserStateRepository
) : LocaleService {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun getUserLocale(chatId: Long): Locale {
        try {
            val user = userRepository.findByTelegramChatId(chatId) ?: return Locale.forLanguageTag("uz")
            val userState = userStateRepository.findByUserId(user.id!!)

            val temporaryData = userState?.temporaryData
            if (temporaryData != null) {
                val objectMapper = ObjectMapper()
                val dataMap = try {
                    objectMapper.readValue(temporaryData, object : TypeReference<Map<String, String>>() {})
                } catch (e: Exception) {
                    null
                }

                val locale = dataMap?.get("locale")
                if (locale != null) {
                    return Locale.forLanguageTag(locale)
                }
            }

            return Locale.forLanguageTag("uz")
        } catch (e: Exception) {
            return Locale.forLanguageTag("uz")
        }
    }

    override fun setUserLocale(chatId: Long, languageCode: String) {
        try {
            val user = userRepository.findByTelegramChatId(chatId) ?: return
            val userState = userStateRepository.findByUserId(user.id!!) ?: return

            val objectMapper = ObjectMapper()
            val dataMap = if (userState.temporaryData.isNullOrEmpty()) {
                mutableMapOf()
            } else {
                try {
                    objectMapper.readValue(userState.temporaryData, object : TypeReference<MutableMap<String, String>>() {})
                } catch (e: Exception) {
                    mutableMapOf()
                }
            }

            dataMap["locale"] = languageCode
            userState.temporaryData = objectMapper.writeValueAsString(dataMap)
            userStateRepository.save(userState)
        } catch (e: Exception) {
            logger.error(e.message)
        }
    }
}