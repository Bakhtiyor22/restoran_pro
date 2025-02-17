package com.example.demo

import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

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
}

interface MenuService {
    fun createMenu(createMenuRequest: CreateMenuRequest): MenuDTO
    fun updateMenu(menuId: Long, updateMenuRequest: CreateMenuRequest): MenuDTO
    fun getAllMenus(pageable: Pageable): Page<MenuDTO>
    fun getMenuById(menuId: Long): MenuDTO
    fun deleteMenu(menuId: Long)
    fun addMenuItem(menuId: Long,  addMenuItem: AddMenuItem): MenuItemDTO
    fun removeMenuItem(menuId: Long, menuItemId: Long)
    fun updateMenuItem(menuItemId: Long, addMenuItem: AddMenuItem): MenuItemDTO
    fun getAllMenuItems(menuId: Long, pageable: Pageable): Page<MenuItemDTO>
}

interface OrderService{
    fun createOrder(customerId: Long, restaurantId: Long, createOrderRequest: CreateOrderRequest): OrderDTO
    fun updateOrderStatus(orderId: Long, updateOrderStatusRequest: UpdateOrderStatusRequest): OrderDTO
    fun cancelOrder(orderId: Long): OrderDTO
    fun getUserOrders(userId: Long, pageable: Pageable): Page<OrderDTO>
}

interface PaymentService {
    fun processOrderPayment(orderId: Long?, paymentRequest: PaymentRequest): BaseMessage
    fun getPaymentHistory(userId: Long): List<PaymentTransactionDTO>
}

@Service
class AuthServiceImpl(
    private val otpService: OTPService,
    private val userRepository: UserRepository,
    private val otpRepository: OtpRepository,
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

        val tokenResponse = jwtUtils.generateToken(user)
        return tokenResponse
    }
}

@Service
class CustomUserDetailsService(
    private val userRepository: UserRepository
) : UserDetailsService {

    override fun loadUserByUsername(phoneNumber: String): UserDetails {
        val user = userRepository.findByPhoneNumber(phoneNumber)
            ?: throw UsernameNotFoundException("User not found with phone: $phoneNumber")

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
    private val passwordEncoder: PasswordEncoder
) : OTPService {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val requestCounts = ConcurrentHashMap<String, Int>()
    private val MAX_ATTEMPTS = 3

    override fun generateOTP(phoneNumber: String): Long {
        val attempts = requestCounts.getOrDefault(phoneNumber, 0)
        if (attempts >= MAX_ATTEMPTS) {
            throw ValidationException({phoneNumber})
        }
        requestCounts[phoneNumber] = attempts + 1

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
    private val passwordEncoder: PasswordEncoder
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
        val user = userRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("User with id=$id not found for update") }

        if (updateUserRequest.phoneNumber != user.phoneNumber) {
            val existing = userRepository.findByPhoneNumber(updateUserRequest.phoneNumber)
            if (existing != null && existing.id != user.id) {
                throw DuplicateResourceException("Phone number already in use: ${updateUserRequest.phoneNumber}")
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
class RestaurantServiceImpl(
    private val restaurantRepository: RestaurantRepository,
): RestaurantService {

    override fun create(request: CreateRestaurantRequest): BaseMessage {
        if (request.name.isEmpty()) {
            throw InvalidInputException()
        }

        val restaurant = Restaurant(
            name = request.name,
            contact = request.contact,
            location = request.location
        )
        restaurantRepository.save(restaurant)
        return  BaseMessage.OK
    }

}

@Service
class MenuServiceImpl(
    private val menuRepository: MenuRepository,
    private val menuItemRepository: MenuItemRepository,
    private val restaurantRepository: RestaurantRepository
): MenuService {

    override fun createMenu(createMenuRequest: CreateMenuRequest): MenuDTO {
        val existingMenu = menuRepository.findByCategory(createMenuRequest.category)
        if (existingMenu != null) {
            throw ValidationException()
        }

        val restaurant = restaurantRepository.findById(createMenuRequest.restaurantId)
            .orElseThrow { ResourceNotFoundException(createMenuRequest.restaurantId) }
        val menu = Menu(
            name = createMenuRequest.name,
            description = createMenuRequest.description,
            category = createMenuRequest.category,
            restaurant = restaurant
        )

        return menuRepository.save(menu).toDto()
    }

    override fun updateMenu(menuId: Long, updateMenuRequest: CreateMenuRequest): MenuDTO {
        val menu = menuRepository.findById(menuId).orElseThrow { ResourceNotFoundException(menuId) }

        if (updateMenuRequest.category != menu.category) {
            val existingMenu = menuRepository.findByCategory(updateMenuRequest.category)
            if (existingMenu != null) {
                throw ValidationException(updateMenuRequest.category)
            }
        }

        menu.apply {
            name = updateMenuRequest.name
            description = updateMenuRequest.description
            category = updateMenuRequest.category
        }
        return menuRepository.save(menu).toDto()
    }

    override fun getAllMenus(pageable: Pageable): Page<MenuDTO> {
        return menuRepository.findAll(pageable).map { it.toDto() }
    }

    override fun getMenuById(menuId: Long): MenuDTO {
        return menuRepository.findByIdAndDeletedFalse(menuId)!!.toDto()
    }

    override fun deleteMenu(menuId: Long) {
        val menu = menuRepository.findById(menuId).orElseThrow { ResourceNotFoundException(menuId) }
        menuRepository.delete(menu)
    }

    override fun addMenuItem(menuId: Long, addMenuItem: AddMenuItem): MenuItemDTO {
        val menu = menuRepository.findById(menuId).orElseThrow { ResourceNotFoundException(menuId) }
        if (addMenuItem.price < BigDecimal.ZERO) {
            throw InvalidInputException("Price cannot be negative")
        }

        val existingMenuItem = menuItemRepository.findByNameAndMenuId(addMenuItem.name, menuId)
        if (existingMenuItem != null) {
            throw DuplicateResourceException("Menu item with name '${addMenuItem.name}' already exists in menu with id $menuId")
        }

        val menuItem = MenuItem(addMenuItem.name, addMenuItem.price, addMenuItem.description, menu)
        return menuItemRepository.save(menuItem).toDto()
    }

    override fun removeMenuItem(menuId: Long, menuItemId: Long) {
        val menuItem = menuItemRepository.findByIdAndMenuId(menuItemId, menuId)
            ?: throw ResourceNotFoundException(menuItemId)
        menuItemRepository.delete(menuItem)
    }

    override fun updateMenuItem(menuItemId: Long,  addMenuItem: AddMenuItem): MenuItemDTO {
        val menuItem = menuItemRepository.findById(menuItemId).orElseThrow { ResourceNotFoundException(menuItemId) }

       menuItem.apply {
           this.name = addMenuItem.name
           this.price = addMenuItem.price
           this.description = addMenuItem.description
       }

        return menuItemRepository.save(menuItem).toDto()
    }

    override fun getAllMenuItems(menuId: Long, pageable: Pageable): Page<MenuItemDTO> {
        val menu = menuRepository.findById(menuId).orElseThrow { ResourceNotFoundException(menuId) }
        return menuItemRepository.findAllByMenuId(menu.id!!, pageable).map { it.toDto() }
    }
}

@Service
@Transactional
class OrderServiceImpl(
    private val orderRepository: OrderRepository,
    private val menuItemRepository: MenuItemRepository,
    private val restaurantRepository: RestaurantRepository,
    private val paymentService: PaymentService
) : OrderService {

    override fun createOrder(
        customerId: Long,
        restaurantId: Long,
        createOrderRequest: CreateOrderRequest
    ): OrderDTO {
        val restaurant = restaurantRepository.findById(restaurantId)
            .orElseThrow { ResourceNotFoundException(restaurantId) }

        var totalAmount = BigDecimal.ZERO
        val orderItems = createOrderRequest.items.map { orderItemRequest ->
            val menuItem = menuItemRepository.findById(orderItemRequest.menuItemId)
                .orElseThrow { ResourceNotFoundException(orderItemRequest.menuItemId) }
            val itemPrice = menuItem.price.multiply(BigDecimal(orderItemRequest.quantity))
            totalAmount = totalAmount.add(itemPrice)

            OrderItemTemp(menuItem, orderItemRequest.quantity, itemPrice)
        }

        val paymentResponse = paymentService.processOrderPayment(
            orderId = null,
            PaymentRequest(
                userId = customerId,
                amount = totalAmount,
                paymentOption = createOrderRequest.paymentOption
            )
        )

        if (paymentResponse.code != 200) {
            throw RuntimeException(paymentResponse.message ?: "Payment Failed")
        }

        val order = Order(
            customerId = customerId,
            restaurant = restaurant,
            totalAmount = totalAmount,
            paymentOption = createOrderRequest.paymentOption,
            status = OrderStatus.PAID,
            orderDate = LocalDateTime.now()
        )

        val savedOrder = orderRepository.save(order)

        orderItems.forEach { tempItem ->
            val orderItem = OrderItem(
                order = savedOrder,
                menuItem = tempItem.menuItem,
                quantity = tempItem.quantity,
                price = tempItem.price
            )
            savedOrder.orderItems.add(orderItem)
        }

        return orderRepository.save(savedOrder).toDto()
    }

    override fun getUserOrders(userId: Long, pageable: Pageable): Page<OrderDTO> {
        return orderRepository.findAllByCustomerId(userId, pageable).map { it.toDto() }
    }

    override fun updateOrderStatus(orderId: Long, updateOrderStatusRequest: UpdateOrderStatusRequest): OrderDTO {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResourceNotFoundException(orderId) }

        if (order.status == OrderStatus.CANCELLED) {
            throw InvalidInputException("Cannot update cancelled order")
        }

        order.status = updateOrderStatusRequest.newStatus
        return orderRepository.save(order).toDto()
    }

    override fun cancelOrder(orderId: Long): OrderDTO {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResourceNotFoundException(orderId) }

        if (order.status == OrderStatus.CANCELLED) {
            throw InvalidInputException("Order is already cancelled")
        }

        order.status = OrderStatus.CANCELLED
        return orderRepository.save(order).toDto()
    }

    private data class OrderItemTemp(
        val menuItem: MenuItem,
        val quantity: Int,
        val price: BigDecimal
    )
}

@Service
class PaymentServiceImpl(
    private val paymentRepository: PaymentRepository,
    private val orderRepository: OrderRepository
) : PaymentService {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun processOrderPayment(orderId: Long?, paymentRequest: PaymentRequest): BaseMessage {
        val order = orderId?.let {
            orderRepository.findById(it).orElseThrow { ResourceNotFoundException(it) }
        }

        var transaction = PaymentTransaction(
            userId = paymentRequest.userId,
            amount = paymentRequest.amount,
            paymentOption = paymentRequest.paymentOption,
            paymentStatus = PaymentStatus.PENDING,
            orderId = orderId
        )
        transaction = paymentRepository.save(transaction)

        val result = doPayment(paymentRequest)
        transaction.paymentStatus = if (result) PaymentStatus.SUCCESS else PaymentStatus.FAILED
        paymentRepository.save(transaction)

        order?.let {
            if (it.totalAmount != paymentRequest.amount) {
                throw InvalidInputException("Payment amount does not match order total")
            }

            it.status = if (result) OrderStatus.IN_PROGRESS else OrderStatus.CANCELLED
            orderRepository.save(it)
        }

        return if (result) {
            BaseMessage(200, "Payment Succeeded. Transaction ID: ${transaction.transactionId}")
        } else {
            BaseMessage(-1, "Payment Failed. Transaction ID: ${transaction.transactionId}")
        }
    }

    override fun getPaymentHistory(userId: Long): List<PaymentTransactionDTO> = paymentRepository.findAllByUserIdOrderByTransactionTimeDesc(userId).map { it.toDto() }

    private fun doPayment(paymentRequest: PaymentRequest): Boolean {
        return paymentRequest.amount <= BigDecimal("10000000.00")
    }
}