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
    fun createOrder(userId: Long, restaurantId: Long, createOrderRequest: CreateOrderRequest): OrderDTO
    fun updateOrderStatus(orderId: Long, updateOrderStatusRequest: UpdateOrderStatusRequest): OrderDTO
    fun cancelOrder(orderId: Long): OrderDTO
    fun getUserOrders(userId: Long, pageable: Pageable): Page<OrderDTO>
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

interface CartService {
    fun addToCart(userId: Long, restaurantId: Long, request: AddToCartRequest): CartDTO
    fun getCart(userId: Long): CartDTO
    fun checkout(userId: Long, paymentRequest: PaymentRequest): OrderDTO
    fun removeFromCart(userId: Long, menuItemId: Long)
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
        val user = userRepository.findById(id)
            .orElseThrow { ResourceNotFoundException(id) }

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

    fun addAddress(userId: Long, addressRequest: AddressRequest): AddressDTO {
        val user = userRepository.findById(userId).orElseThrow { ResourceNotFoundException(userId) }
        val address = Address(
            addressLine = addressRequest.addressLine,
            city = addressRequest.city,
            state = addressRequest.state,
            postalCode = addressRequest.postalCode,
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
            throw ValidationException(createMenuRequest.category)
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
            throw InvalidInputException(addMenuItem.price)
        }

        val existingMenuItem = menuItemRepository.findByNameAndMenuId(addMenuItem.name, menuId)
        if (existingMenuItem != null) {
            throw DuplicateResourceException(addMenuItem.name)
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
class CartServiceImpl(
    private val cartRepository: CartRepository,
    private val menuItemRepository: MenuItemRepository,
    private val restaurantRepository: RestaurantRepository,
    private val paymentService: PaymentService,
    private val orderRepository: OrderRepository
) : CartService {

    override fun addToCart(userId: Long, restaurantId: Long, request: AddToCartRequest): CartDTO {
        if (request.quantity <= 0) {
            throw ValidationException(request.quantity)
        }

        val cart = cartRepository.findByCustomerId(userId)
            ?: createNewCart(userId, restaurantId)

        if (cart.restaurant.id != restaurantId) {
            throw ValidationException(restaurantId)
        }

        val menuItem = menuItemRepository.findById(request.menuItemId)
            .orElseThrow { ResourceNotFoundException(request.menuItemId) }

        val cartItem = cart.items.find { it.menuItem.id == request.menuItemId }
        if (cartItem != null) {
            cartItem.quantity += request.quantity
        } else {
            cart.items.add(CartItem(cart, menuItem, request.quantity))
        }

        return cartRepository.save(cart).toDto()
    }

    override fun getCart(userId: Long): CartDTO {
        return cartRepository.findByCustomerId(userId)?.toDto()
            ?: throw ResourceNotFoundException(userId)
    }

    override fun checkout(userId: Long, paymentRequest: PaymentRequest): OrderDTO {
        val cart = cartRepository.findByCustomerId(userId)
            ?: throw ResourceNotFoundException(userId)

        val totals = calculateCartTotals(cart)
        if (totals.total != paymentRequest.amount) {
            throw ValidationException(paymentRequest.amount)
        }

        val paymentResult = paymentService.processOrderPayment(userId, null, paymentRequest)
        if (paymentResult.code != 200) {
            throw ValidationException(paymentResult.message)
        }

        val order = createOrderFromCart(cart, totals)
        cartRepository.delete(cart)

        return order.toDto()
    }

    override fun removeFromCart(userId: Long, menuItemId: Long) {
        val cart = cartRepository.findByCustomerId(userId) ?: throw ResourceNotFoundException(userId)
        val removed = cart.items.removeIf { it.menuItem.id == menuItemId }
        if (!removed) {
            throw ResourceNotFoundException("Item not found in cart: $menuItemId")
        }
        cartRepository.save(cart)
    }

    private fun createNewCart(userId: Long, restaurantId: Long): Cart {
        val restaurant = restaurantRepository.findById(restaurantId)
            .orElseThrow { ResourceNotFoundException(restaurantId) }
        return Cart(customerId = userId, restaurant = restaurant)
    }

    private fun calculateCartTotals(cart: Cart): CartTotals {
        val subtotal = cart.items.fold(BigDecimal.ZERO) { acc, item ->
            acc + (item.menuItem.price * BigDecimal(item.quantity))
        }
        val serviceCharge = subtotal.multiply(cart.serviceChargePercent)
            .divide(BigDecimal(100))
        val discount = subtotal.multiply(cart.discountPercent)
            .divide(BigDecimal(100))
        val total = subtotal.plus(serviceCharge).plus(cart.deliveryFee).minus(discount)
        return CartTotals(subtotal, serviceCharge, cart.deliveryFee, discount, total)
    }

    private data class CartTotals(
        val subtotal: BigDecimal,
        val serviceCharge: BigDecimal,
        val deliveryFee: BigDecimal,
        val discount: BigDecimal,
        val total: BigDecimal
    )

    private fun createOrderFromCart(cart: Cart, totals: CartTotals): Order {
        val order = Order(
            customerId = cart.customerId,
            restaurant = cart.restaurant,
            totalAmount = totals.total,
            subtotal = totals.subtotal,
            serviceCharge = totals.serviceCharge,
            deliveryFee = totals.deliveryFee,
            discount = totals.discount,
            paymentOption = PaymentOption.CARD,
            status = OrderStatus.PAID,
            orderDate = LocalDateTime.now()
        )

        cart.items.forEach { cartItem ->
            order.orderItems.add(
                OrderItem(
                    order = order,
                    menuItem = cartItem.menuItem,
                    quantity = cartItem.quantity,
                    price = cartItem.menuItem.price
                )
            )
        }

        return orderRepository.save(order)
    }
}

fun Cart.toDto(): CartDTO {
    val subtotal = items.fold(BigDecimal.ZERO) { acc, item ->
        acc + (item.menuItem.price * BigDecimal(item.quantity))
    }
    val serviceCharge = subtotal.multiply(serviceChargePercent).divide(BigDecimal(100))
    val discount = subtotal.multiply(discountPercent).divide(BigDecimal(100))
    val total = subtotal.plus(serviceCharge).plus(deliveryFee).minus(discount)
    return CartDTO(
        id = id,
        customerId = customerId,
        restaurantId = restaurant.id!!,
        items = items.map { it.toDto() },
        subtotal = subtotal,
        serviceCharge = serviceCharge,
        deliveryFee = deliveryFee,
        discount = discount,
        total = total
    )
}

fun CartItem.toDto() = CartItemDTO(
    id = id,
    menuItemId = menuItem.id!!,
    name = menuItem.name,
    price = menuItem.price,
    quantity = quantity,
    subtotal = menuItem.price * BigDecimal(quantity)
)

@Service
@Transactional
class OrderServiceImpl(
    private val orderRepository: OrderRepository,
    private val menuItemRepository: MenuItemRepository,
    private val restaurantRepository: RestaurantRepository,
    private val cartRepository: CartRepository,
    private val paymentService: PaymentService
) : OrderService {

    data class CartTotals(
        val subtotal: BigDecimal,
        val serviceCharge: BigDecimal,
        val deliveryFee: BigDecimal,
        val discount: BigDecimal,
        val total: BigDecimal
    )

    private fun calculateCartOrderTotals(cart: Cart): CartTotals {
        val subtotal = cart.items.fold(BigDecimal.ZERO) { acc, item ->
            acc + (item.menuItem.price * BigDecimal(item.quantity))
        }
        val serviceCharge = subtotal.multiply(cart.serviceChargePercent)
            .divide(BigDecimal(100))
        val discount = subtotal.multiply(cart.discountPercent)
            .divide(BigDecimal(100))
        val total = subtotal.plus(serviceCharge).plus(cart.deliveryFee).minus(discount)
        return CartTotals(subtotal, serviceCharge, cart.deliveryFee, discount, total)
    }

    override fun createOrder(
        userId: Long,
        restaurantId: Long,
        createOrderRequest: CreateOrderRequest
    ): OrderDTO {
        return if (createOrderRequest.cartId != null) {
            createOrderFromCart(userId, createOrderRequest)
        } else {
            createDirectOrder(userId, restaurantId, createOrderRequest)
        }
    }

    override fun updateOrderStatus(
        orderId: Long,
        updateOrderStatusRequest: UpdateOrderStatusRequest
    ): OrderDTO {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResourceNotFoundException(orderId) }
        order.status = updateOrderStatusRequest.newStatus
        return orderRepository.save(order).toDto()
    }

    override fun cancelOrder(orderId: Long): OrderDTO {
        val order = orderRepository.findById(orderId)
            .orElseThrow { ResourceNotFoundException(orderId) }
        order.status = OrderStatus.CANCELLED
        return orderRepository.save(order).toDto()
    }

    override fun getUserOrders(userId: Long, pageable: Pageable): Page<OrderDTO> {
        return orderRepository.findAllByCustomerId(userId, pageable).map { it.toDto() }
    }

    private fun createOrderFromCart(
        userId: Long,
        createOrderRequest: CreateOrderRequest
    ): OrderDTO {
        val cart = cartRepository.findByCustomerId(userId)
            ?: throw ResourceNotFoundException("Cart not found")
        val totals = calculateCartOrderTotals(cart)
        val order = Order(
            customerId = userId,
            restaurant = cart.restaurant,
            totalAmount = totals.total,
            paymentOption = createOrderRequest.paymentOption,
            status = OrderStatus.PENDING,
            orderDate = LocalDateTime.now(),
            subtotal = totals.subtotal,
            serviceCharge = totals.serviceCharge,
            deliveryFee = totals.deliveryFee,
            discount = totals.discount
        )

        cart.items.forEach { cartItem ->
            order.orderItems.add(
                OrderItem(
                    order = order,
                    menuItem = cartItem.menuItem,
                    quantity = cartItem.quantity,
                    price = cartItem.menuItem.price
                )
            )
        }

        val savedOrder = orderRepository.save(order)
        val paymentResult = processPayment(userId, savedOrder.id!!, totals.total, createOrderRequest.paymentOption)
        savedOrder.status = if (paymentResult.code == 200) OrderStatus.PAID else OrderStatus.CANCELLED
        val finalOrder = orderRepository.save(savedOrder)
        if (paymentResult.code == 200) {
            cartRepository.delete(cart)
        }
        return finalOrder.toDto()
    }

    private fun createDirectOrder(
        userId: Long,
        restaurantId: Long,
        createOrderRequest: CreateOrderRequest
    ): OrderDTO {
        val restaurant = restaurantRepository.findById(restaurantId)
            .orElseThrow { ResourceNotFoundException(restaurantId) }

        val orderItems = createOrderRequest.items.map { item: OrderItemRequest ->
            val menuItem = menuItemRepository.findById(item.menuItemId)
                .orElseThrow { ResourceNotFoundException(item.menuItemId) }
            OrderItemTemp(menuItem, item.quantity, menuItem.price)
        }

        val totalAmount = calculateDirectOrderTotal(orderItems)

        val order = Order(
            customerId = userId,
            restaurant = restaurant,
            totalAmount = totalAmount,
            paymentOption = createOrderRequest.paymentOption,
            status = OrderStatus.PENDING,
            orderDate = LocalDateTime.now(),
            subtotal = totalAmount,
            serviceCharge = BigDecimal.ZERO,
            deliveryFee = BigDecimal.ZERO,
            discount = BigDecimal.ZERO
        )

        orderItems.forEach { item ->
            order.orderItems.add(
                OrderItem(
                    order = order,
                    menuItem = item.menuItem,
                    quantity = item.quantity,
                    price = item.price
                )
            )
        }

        val savedOrder = orderRepository.save(order)
        val paymentResult = processPayment(userId, savedOrder.id!!, totalAmount, createOrderRequest.paymentOption)
        savedOrder.status = if (paymentResult.code == 200) OrderStatus.PAID else OrderStatus.CANCELLED
        return orderRepository.save(savedOrder).toDto()
    }

    private fun calculateDirectOrderTotal(items: List<OrderItemTemp>): BigDecimal {
        return items.fold(BigDecimal.ZERO) { acc, item ->
            acc + (item.price * BigDecimal(item.quantity))
        }
    }

    private fun processPayment(
        userId: Long,
        orderId: Long,
        amount: BigDecimal,
        paymentOption: PaymentOption
    ): BaseMessage {
        val paymentRequest = PaymentRequest(amount, paymentOption)
        return paymentService.processOrderPayment(userId, orderId, paymentRequest)
    }
}

@Service
class PaymentServiceImpl(
    private val paymentRepository: PaymentRepository,
    private val orderRepository: OrderRepository,
    private val cardRepository: CardRepository
) : PaymentService {

    override fun processOrderPayment(userId: Long, orderId: Long?, paymentRequest: PaymentRequest): BaseMessage {
        val order = orderId?.let {
            orderRepository.findById(it).orElseThrow { ResourceNotFoundException(it) }
        }

        if (paymentRequest.paymentOption == PaymentOption.CARD) {
            val defaultCard = cardRepository.findByUserIdAndIsDefaultTrueAndDeletedFalse(userId)
                ?: throw ValidationException()

            if (defaultCard.balance < paymentRequest.amount) {
                val message = """
                    Insufficient funds on ${defaultCard.cardType} card. 
                    Available balance: ${defaultCard.balance}
                    Required amount: ${paymentRequest.amount}
                    Please use another payment option: CASH or select another card.
                """.trimIndent()
                throw ValidationException(message)
            }
        }

        var transaction = PaymentTransaction(
            userId = userId,
            amount = paymentRequest.amount,
            paymentOption = paymentRequest.paymentOption,
            paymentStatus = PaymentStatus.PENDING,
            orderId = orderId
        )
        transaction = paymentRepository.save(transaction)

        val result = doPayment(paymentRequest)

        if (result && paymentRequest.paymentOption == PaymentOption.CARD) {
            val defaultCard = cardRepository.findByUserIdAndIsDefaultTrueAndDeletedFalse(userId)!!
            defaultCard.balance = defaultCard.balance.subtract(paymentRequest.amount)
            cardRepository.save(defaultCard)
        }

        transaction.paymentStatus = if (result) PaymentStatus.SUCCESS else PaymentStatus.FAILED
        paymentRepository.save(transaction)

        order?.let {
            if (it.totalAmount != paymentRequest.amount) {
                throw ValidationException(paymentRequest.amount)
            }

            it.status = if (result) OrderStatus.IN_PROGRESS else OrderStatus.CANCELLED
            orderRepository.save(it)
        }

        return if (result) {
            BaseMessage(200, "To'lov qabul qilindi. Tranzaksiya ID: ${transaction.transactionId}")
        } else {
            BaseMessage(-1, "To'lov qabul qilinmadi. Transaction ID: ${transaction.transactionId}")
        }
    }

    private fun doPayment(paymentRequest: PaymentRequest): Boolean {
        return when (paymentRequest.paymentOption) {
            PaymentOption.CARD -> true  // Here you would integrate with a real payment gateway
            PaymentOption.CASH -> true  // Cash payments are typically handled manually
            PaymentOption.ONLINE -> true  // Online payments are typically handled by a third-party service
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

        card.balance = amount
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

