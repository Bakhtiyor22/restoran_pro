// AuthServiceTest.kt
package com.example.demo

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.security.crypto.password.PasswordEncoder
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

@ExtendWith(MockitoExtension::class)
class AuthServiceTest {
    @Mock
    private lateinit var otpService: OTPService

    @Mock
    private lateinit var userRepository: UserRepository

    @Mock
    private lateinit var otpRepository: OtpRepository

    @Mock
    private lateinit var jwtUtils: JwtUtils

    @Mock
    private lateinit var passwordEncoder: PasswordEncoder

    @Mock
    private lateinit var messageSourceService: MessageSourceService

    @InjectMocks
    private lateinit var authService: AuthServiceImpl

    @Test
    fun `requestOtp should generate OTP for valid phone number`() {
        // given
        val phoneNumber = "+998901234567"
        val otpRequest = OtpRequest(phoneNumber)
        val expectedOtpId = 123L
        val messageText = "OTP sent"

        `when`(otpService.generateOTP(phoneNumber)).thenReturn(expectedOtpId)
        `when`(messageSourceService.getMessage(MessageKey.OTP_REQUEST)).thenReturn(messageText)

        // when
        val result = authService.requestOtp(otpRequest)

        // then
        assertEquals(expectedOtpId, result.smsCodeId)
        assertEquals(messageText, result.message)
    }

    @Test
    fun `requestOtp should throw InvalidInputException for invalid phone number`() {
        // given
        val invalidPhone = "123"
        val otpRequest = OtpRequest(invalidPhone)

        // when/then
        assertThrows(InvalidInputException::class.java) {
            authService.requestOtp(otpRequest)
        }
    }
}

// UserServiceTest.kt
@ExtendWith(MockitoExtension::class)
class UserServiceTest {
    @Mock
    private lateinit var userRepository: UserRepository

    @Mock
    private lateinit var passwordEncoder: PasswordEncoder

    @Mock
    private lateinit var addressRepository: AddressRepository

    @InjectMocks
    private lateinit var userService: UserServiceImpl

    @Test
    fun `createUser should create new user successfully`() {
        // given
        val request = CreateUserRequest(
            username = "test",
            phoneNumber = "+998901234567",
            password = "password",
            role = Roles.CUSTOMER
        )

        val encodedPassword = "encoded"
        val savedUser = User(
            username = request.username,
            phoneNumber = request.phoneNumber,
            password = encodedPassword,
            role = request.role
        ).apply { id = 1L }

        `when`(passwordEncoder.encode(request.password)).thenReturn(encodedPassword)
        `when`(userRepository.save(any())).thenReturn(savedUser)

        // when
        val result = userService.createUser(request)

        // then
        assertEquals(savedUser.id, result.id)
        assertEquals(request.username, result.username)
        assertEquals(request.phoneNumber, result.phoneNumber)
        assertEquals(request.role, result.role)
    }

    @Test
    fun `createUser should throw DuplicateResourceException for existing phone number`() {
        // given
        val request = CreateUserRequest(
            username = "test",
            phoneNumber = "+998901234567",
            password = "password",
            role = Roles.CUSTOMER
        )

        `when`(userRepository.findByPhoneNumber(request.phoneNumber))
            .thenReturn(User("existing", request.phoneNumber, "pwd", Roles.CUSTOMER))

        // when/then
        assertThrows(DuplicateResourceException::class.java) {
            userService.createUser(request)
        }
    }
}

@ExtendWith(MockitoExtension::class)
class CartServiceTest {

    @Mock
    private lateinit var cartRepository: CartRepository

    @Mock
    private lateinit var menuItemRepository: MenuItemRepository

    @Mock
    private lateinit var restaurantRepository: RestaurantRepository

    @Mock
    private lateinit var paymentService: PaymentService

    @Mock
    private lateinit var orderRepository: OrderRepository

    @InjectMocks
    private lateinit var cartService: CartServiceImpl

    @Test
    fun `addToCart should create new cart if none exists and add item`() {
        // given
        val userId = 1L
        val restaurantId = 1L
        val addToCartRequest = AddToCartRequest(menuItemId = 1L, quantity = 2)

        val restaurant = Restaurant(
            name = "Test Restaurant",
            contact = "1234",
            location = "Test Location"
        ).apply { id = restaurantId }

        val menu = Menu(
            name = "Test Menu",
            description = "Test Description",
            category = MenuCategory.FOODS,
            restaurant = restaurant
        ).apply { id = 1L }

        val menuItem = MenuItem(
            name = "Item 1",
            price = BigDecimal("10.00"),
            description = "Test Item Description",
            menu = menu
        ).apply { id = 1L }

        val newCart = Cart(
            customerId = userId,
            restaurant = restaurant
        ).apply { id = 1L }
        newCart.items = mutableListOf()

        // when
        `when`(cartRepository.findByCustomerId(userId)).thenReturn(null)
        `when`(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant))
        `when`(menuItemRepository.findById(1L)).thenReturn(Optional.of(menuItem))
        `when`(cartRepository.save(any(Cart::class.java))).thenAnswer { it.arguments[0] }

        val result = cartService.addToCart(userId, restaurantId, addToCartRequest)

        // then
        assertNotNull(result)
        assertEquals(1, result.items.size)
        assertEquals(2, result.items[0].quantity)
        verify(cartRepository).save(any(Cart::class.java))
    }

    @Test
    fun `getCart should return existing cart`() {
        // given
        val userId = 1L
        val restaurant = Restaurant(
            name = "Test Restaurant",
            contact = "1234",
            location = "Test Location"
        ).apply { id = 1L }

        val menu = Menu(
            name = "Test Menu",
            description = "Test Description",
            category = MenuCategory.FOODS,
            restaurant = restaurant
        ).apply { id = 1L }

        val menuItem = MenuItem(
            name = "Item 1",
            price = BigDecimal("10.00"),
            description = "Test Item Description",
            menu = menu
        ).apply { id = 1L }

        val cart = Cart(
            customerId = userId,
            restaurant = restaurant
        ).apply { id = 1L }

        cart.items = mutableListOf(CartItem(cart, menuItem, 1))

        // when
        `when`(cartRepository.findByCustomerId(userId)).thenReturn(cart)

        val result = cartService.getCart(userId)

        // then
        assertNotNull(result)
        assertEquals(1, result.items.size)
    }

    @Test
    fun `checkout should validate payment amount, process payment and delete cart on success`() {
        // given
        val userId = 1L
        val restaurant = Restaurant(
            name = "Test Restaurant",
            contact = "1234",
            location = "Test Location"
        ).apply { id = 1L }

        val menu = Menu(
            name = "Test Menu",
            description = "Test Description",
            category = MenuCategory.FOODS,
            restaurant = restaurant
        ).apply { id = 1L }

        val menuItem = MenuItem(
            name = "Item 1",
            price = BigDecimal("10.00"),
            description = "Test Item Description",
            menu = menu
        ).apply { id = 1L }

        val cart = Cart(
            customerId = userId,
            restaurant = restaurant
        ).apply {
            id = 1L
            items = mutableListOf(CartItem(this, menuItem, 2))
            serviceChargePercent = BigDecimal("10")
            discountPercent = BigDecimal("5")
            deliveryFee = BigDecimal("5000")
        }

        val paymentRequest = PaymentRequest(
            amount = BigDecimal("25.00"),
            paymentOption = PaymentOption.CARD
        )

        val order = Order(
            customerId = userId,
            restaurant = restaurant,
            totalAmount = BigDecimal("25.00"),
            subtotal = BigDecimal("20.00"),
            serviceCharge = BigDecimal("2.00"),
            deliveryFee = BigDecimal("5000"),
            discount = BigDecimal("2.00"),
            paymentOption = PaymentOption.CARD,
            status = OrderStatus.PAID,
            orderDate = LocalDateTime.now()
        ).apply { id = 1L }

        // when
        `when`(cartRepository.findByCustomerId(userId)).thenReturn(cart)
        `when`(paymentService.processOrderPayment(eq(userId), any(), any()))
            .thenReturn(BaseMessage(200, "Payment successful"))
        `when`(orderRepository.save(any())).thenReturn(order)

        val result = cartService.checkout(userId, paymentRequest)

        // then
        assertNotNull(result)
        assertEquals(OrderStatus.PAID, result.status)
        verify(cartRepository).delete(cart)
    }

    @Test
    fun `removeFromCart should throw exception if item not found`() {
        // given
        val userId = 1L
        val menuItemId = 99L
        val restaurant = Restaurant(
            name = "Test Restaurant",
            contact = "1234",
            location = "Test Location"
        ).apply { id = 1L }

        val cart = Cart(
            customerId = userId,
            restaurant = restaurant
        ).apply {
            id = 1L
            items = mutableListOf()
        }

        // when
        `when`(cartRepository.findByCustomerId(userId)).thenReturn(cart)

        // then
        val exception = assertThrows<ResourceNotFoundException> {
            cartService.removeFromCart(userId, menuItemId)
        }
        assertEquals("Item not found in cart: $menuItemId", exception.message)
    }
}

// PaymentServiceTest.kt
@ExtendWith(MockitoExtension::class)
class PaymentServiceTest {

    @Mock
    private lateinit var paymentRepository: PaymentRepository

    @Mock
    private lateinit var orderRepository: OrderRepository

    @Mock
    private lateinit var cardRepository: CardRepository

    @InjectMocks
    private lateinit var paymentService: PaymentServiceImpl

    @Test
    fun `processOrderPayment should throw ResourceNotFoundException when order id not found`() {
        // given
        val userId = 1L
        val orderId = 999L
        val paymentRequest = PaymentRequest(amount = BigDecimal("50.00"), paymentOption = PaymentOption.CARD)
        `when`(orderRepository.findById(orderId)).thenReturn(Optional.empty())

        // when/then
        assertThrows(ResourceNotFoundException::class.java) {
            paymentService.processOrderPayment(userId, orderId, paymentRequest)
        }
        verify(orderRepository).findById(orderId)
    }

    @Test
    fun `processOrderPayment should succeed for online payment without card validation`() {
        // given
        val userId = 1L
        val paymentRequest = PaymentRequest(amount = BigDecimal("100.00"), paymentOption = PaymentOption.ONLINE)
        // online payment does not require order lookup or card checks
        `when`(paymentRepository.save(any(PaymentTransaction::class.java))).thenAnswer { it.arguments[0] }

        // when
        val result = paymentService.processOrderPayment(userId, null, paymentRequest)

        // then
        assertEquals(200, result.code)
        verifyNoInteractions(cardRepository)
    }

    @Test
    fun `processOrderPayment should throw ValidationException for CARD payment if default card not found`() {
        // given
        val userId = 2L
        val paymentRequest = PaymentRequest(amount = BigDecimal("100.00"), paymentOption = PaymentOption.CARD)
        `when`(cardRepository.findByUserIdAndIsDefaultTrueAndDeletedFalse(userId)).thenReturn(null)

        // when/then
        assertThrows(ValidationException::class.java) {
            paymentService.processOrderPayment(userId, null, paymentRequest)
        }
        verify(cardRepository).findByUserIdAndIsDefaultTrueAndDeletedFalse(userId)
    }

    @Test
    fun `processOrderPayment should throw ValidationException for CARD payment if insufficient funds`() {
        // given
        val userId = 3L
        val paymentRequest = PaymentRequest(amount = BigDecimal("150.00"), paymentOption = PaymentOption.CARD)
        val defaultCard = Card(
            cardNumber = "********1234",
            expiryDate = "12/25",
            cardHolderName = "Test Card",
            cardType = CardType.UZCARD,
            balance = BigDecimal("100.00"),
            isDefault = true,
            user = User("test", "+998901234567", "pwd", Roles.CUSTOMER)
        )
        `when`(cardRepository.findByUserIdAndIsDefaultTrueAndDeletedFalse(userId)).thenReturn(defaultCard)

        // when/then
        assertThrows(ValidationException::class.java) {
            paymentService.processOrderPayment(userId, null, paymentRequest)
        }
        verify(cardRepository).findByUserIdAndIsDefaultTrueAndDeletedFalse(userId)
    }

    @Test
    fun `processOrderPayment for CARD payment should succeed and update card balance`() {
        // given
        val userId = 4L
        val orderId = 10L
        val paymentRequest = PaymentRequest(amount = BigDecimal("50.00"), paymentOption = PaymentOption.CARD)
        val defaultCard = Card(
            cardNumber = "********1234",
            expiryDate = "12/25",
            cardHolderName = "Test Card",
            cardType = CardType.UZCARD,
            balance = BigDecimal("100.00"),
            isDefault = true,
            user = User("test", "+998901234567", "pwd", Roles.CUSTOMER)
        )
        val order = Order(
            customerId = userId,
            restaurant = Restaurant(name = "Rest", contact = "998", location = "Loc").apply { id = 1L },
            totalAmount = BigDecimal("50.00"),
            paymentOption = PaymentOption.CARD,
            status = OrderStatus.PENDING,
            orderDate = java.time.LocalDateTime.now(),
            subtotal = BigDecimal("40.00"),
            serviceCharge = BigDecimal("5.00"),
            deliveryFee = BigDecimal("5.00"),
            discount = BigDecimal("0.00")
        )
        order.id = orderId

        `when`(orderRepository.findById(orderId)).thenReturn(Optional.of(order))
        `when`(cardRepository.findByUserIdAndIsDefaultTrueAndDeletedFalse(userId)).thenReturn(defaultCard)
        `when`(paymentRepository.save(any(PaymentTransaction::class.java))).thenAnswer { it.arguments[0] }
        `when`(orderRepository.save(any(Order::class.java))).thenAnswer { it.arguments[0] }

        // when
        val result = paymentService.processOrderPayment(userId, orderId, paymentRequest)

        // then
        assertEquals(200, result.code)
        assertEquals(BigDecimal("50.00"), defaultCard.balance) // 100 - 50 = 50
        verify(cardRepository, times(2)).save(any(Card::class.java))
    }
}


@ExtendWith(MockitoExtension::class)
class OrderServiceTest {

    @Mock
    private lateinit var orderRepository: OrderRepository

    @Mock
    private lateinit var menuItemRepository: MenuItemRepository

    @Mock
    private lateinit var restaurantRepository: RestaurantRepository

    @Mock
    private lateinit var cartRepository: CartRepository

    @Mock
    private lateinit var paymentService: PaymentService

    @InjectMocks
    private lateinit var orderService: OrderServiceImpl

    @Test
    fun `createOrder should create order from cart when cartId provided`() {
        // given
        val userId = 1L
        val cartId = 1L
        val createOrderRequest = CreateOrderRequest(cartId = cartId, paymentOption = PaymentOption.CARD)
        val restaurant = Restaurant("Test Restaurant", "1234", "Test Location").apply { id = 1L }
        val menuItem = MenuItem("Item 1", BigDecimal("10.00"), "Test Item Description", Menu("Test Menu", "Test Description", MenuCategory.FOODS, restaurant)).apply { id = 1L }
        val cart = Cart(userId, restaurant).apply {
            id = cartId
            items = mutableListOf(CartItem(this, menuItem, 2))
        }

        `when`(cartRepository.findByCustomerId(userId)).thenReturn(cart)
        `when`(orderRepository.save(any(Order::class.java))).thenAnswer { it.arguments[0] }
        `when`(paymentService.processOrderPayment(eq(userId), any(), any())).thenReturn(BaseMessage(200, "Payment successful"))

        // when
        val result = orderService.createOrder(userId, restaurant.id!!, createOrderRequest)

        // then
        assertNotNull(result)
        assertEquals(OrderStatus.PAID, result.status)
        verify(cartRepository).delete(cart)
    }

    @Test
    fun `createOrder should create direct order when cartId not provided`() {
        // given
        val userId = 1L
        val restaurantId = 1L
        val createOrderRequest = CreateOrderRequest(
            items = listOf(OrderItemRequest(menuItemId = 1L, quantity = 2)),
            paymentOption = PaymentOption.CARD
        )
        val restaurant = Restaurant("Test Restaurant", "1234", "Test Location").apply { id = restaurantId }
        val menuItem = MenuItem("Item 1", BigDecimal("10.00"), "Test Item Description", Menu("Test Menu", "Test Description", MenuCategory.FOODS, restaurant)).apply { id = 1L }

        `when`(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant))
        `when`(menuItemRepository.findById(1L)).thenReturn(Optional.of(menuItem))
        `when`(orderRepository.save(any(Order::class.java))).thenAnswer { it.arguments[0] }
        `when`(paymentService.processOrderPayment(eq(userId), any(), any())).thenReturn(BaseMessage(200, "Payment successful"))

        // when
        val result = orderService.createOrder(userId, restaurantId, createOrderRequest)

        // then
        assertNotNull(result)
        assertEquals(OrderStatus.PAID, result.status)
    }

    @Test
    fun `updateOrderStatus should update status successfully`() {
        // given
        val orderId = 1L
        val updateOrderStatusRequest = UpdateOrderStatusRequest(newStatus = OrderStatus.IN_PROGRESS)
        val order = Order(1L, Restaurant("Test Restaurant", "1234", "Test Location"), BigDecimal("20.00"), BigDecimal("20.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, PaymentOption.CARD, OrderStatus.PENDING, LocalDateTime.now()).apply { id = orderId }

        `when`(orderRepository.findById(orderId)).thenReturn(Optional.of(order))
        `when`(orderRepository.save(any(Order::class.java))).thenAnswer { it.arguments[0] }

        // when
        val result = orderService.updateOrderStatus(orderId, updateOrderStatusRequest)

        // then
        assertNotNull(result)
        assertEquals(OrderStatus.IN_PROGRESS, result.status)
    }

    @Test
    fun `cancelOrder should update order status to CANCELLED`() {
        // given
        val orderId = 1L
        val order = Order(1L, Restaurant("Test Restaurant", "1234", "Test Location"), BigDecimal("20.00"), BigDecimal("20.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, PaymentOption.CARD, OrderStatus.PENDING, LocalDateTime.now()).apply { id = orderId }

        `when`(orderRepository.findById(orderId)).thenReturn(Optional.of(order))
        `when`(orderRepository.save(any(Order::class.java))).thenAnswer { it.arguments[0] }

        // when
        val result = orderService.cancelOrder(orderId)

        // then
        assertNotNull(result)
        assertEquals(OrderStatus.CANCELLED, result.status)
    }

    @Test
    fun `getUserOrders should return paginated orders`() {
        // given
        val userId = 1L
        val orders = listOf(
            Order(1L, Restaurant("Test Restaurant", "1234", "Test Location"), BigDecimal("20.00"), BigDecimal("20.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, PaymentOption.CARD, OrderStatus.PENDING, LocalDateTime.now()).apply { id = 1L },
            Order(1L, Restaurant("Test Restaurant", "1234", "Test Location"), BigDecimal("30.00"), BigDecimal("30.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, PaymentOption.CARD, OrderStatus.PENDING, LocalDateTime.now()).apply { id = 2L }
        )
        val pageable = PageRequest.of(0, 10)
        val pagedOrders = PageImpl(orders, pageable, orders.size.toLong())

        `when`(orderRepository.findAllByCustomerId(userId, pageable)).thenReturn(pagedOrders)

        // when
        val result = orderService.getUserOrders(userId, pageable)

        // then
        assertEquals(2, result.totalElements)
    }
}