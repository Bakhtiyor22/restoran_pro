package com.example.demo

import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
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
    fun createMenu(createMenuRequest: createMenuRequest): MenuDTO
    fun addMenuItem(menuId: Long,  addMenuItem: addMenuItem): MenuItemDTO
    fun removeMenuItem(menuItemId: Long)
    fun updateMenuItem(menuItemId: Long, addMenuItem: addMenuItem): MenuItemDTO
}

interface OrderService{
    fun createOrder(customerId: Long, restaurantId: Long, createOrderRequest: createOrderRequest): OrderDTO
    fun updateOrderStatus(orderId: Long, newStatus: OrderStatus): OrderDTO
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
        if (!validatePhoneNumber(phoneNumber)) throw RuntimeException(otpRequest.phoneNumber)

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
            ?: throw UsernameNotFoundException("User not found")
        return org.springframework.security.core.userdetails.User(
            user.phoneNumber,
            user.password,
            emptyList()
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
            throw ValidationException()
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
        if(userRepository.findByPhoneNumber(createUserRequest.phoneNumber) != null) {
            throw DuplicateResourceException()
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
            .orElseThrow { ResourceNotFoundException("User not found") }

        updateUserRequest.username.let { user.username = it }
        updateUserRequest.phoneNumber.let { user.phoneNumber = it }

        val updatedUser = userRepository.save(user)
        return updatedUser.toDto()
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

    override fun createMenu(createMenuRequest: createMenuRequest): MenuDTO {
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

    override fun addMenuItem(menuId: Long, addMenuItem: addMenuItem): MenuItemDTO {
        val menu = menuRepository.findById(menuId).orElseThrow { ResourceNotFoundException(menuId) }
        val menuItem = MenuItem(addMenuItem.name, addMenuItem.price, addMenuItem.description, menu)
        return menuItemRepository.save(menuItem).toDto()
    }

    override fun removeMenuItem(menuItemId: Long) {
        val menuItem = menuItemRepository.findById(menuItemId).orElseThrow { ResourceNotFoundException(menuItemId) }
        menuItemRepository.delete(menuItem)
    }

    override fun updateMenuItem(menuItemId: Long,  addMenuItem: addMenuItem): MenuItemDTO {
        val menuItem = menuItemRepository.findById(menuItemId).orElseThrow { ResourceNotFoundException(menuItemId) }

       menuItem.apply {
           this.name = addMenuItem.name
           this.price = addMenuItem.price
           this.description = addMenuItem.description
       }

        return menuItemRepository.save(menuItem).toDto()
    }
}

@Service
@Transactional
class OrderServiceImpl(
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val menuItemRepository: MenuItemRepository,
    private val restaurantRepository: RestaurantRepository
) : OrderService {

    override fun createOrder(customerId: Long, restaurantId: Long, createOrderRequest: createOrderRequest): OrderDTO {
        val restaurant = restaurantRepository.findById(restaurantId).orElseThrow { ResourceNotFoundException(restaurantId) }
        val order = Order(
            customerId = customerId,
            restaurant = restaurant,
            totalAmount = BigDecimal.ZERO,
            paymentOption = createOrderRequest.paymentOption,
            status = OrderStatus.PENDING
        )
        val savedOrder = orderRepository.save(order)

        var total = BigDecimal.ZERO
        createOrderRequest.items.forEach { (menuItemId, qty) ->
            val menuItem = menuItemRepository.findById(menuItemId)
                .orElseThrow { ResourceNotFoundException(menuItemId) }
            val price = menuItem.price.multiply(BigDecimal(qty))
            total = total.add(price)
            val orderItem = OrderItem(
                order = savedOrder,
                menuItem = menuItem,
                quantity = qty,
                price = price
            )
            orderItemRepository.save(orderItem)
        }

        savedOrder.totalAmount = total
        return orderRepository.save(savedOrder).toDto()
    }

    override fun updateOrderStatus(orderId: Long, newStatus: OrderStatus): OrderDTO {
        val order = orderRepository.findById(orderId).orElseThrow { ResourceNotFoundException(orderId) }
        order.status = newStatus
        return orderRepository.save(order).toDto()
    }
}