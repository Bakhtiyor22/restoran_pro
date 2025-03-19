//// AuthServiceTest.kt
//package com.example.demo
//
//import com.fasterxml.jackson.databind.ObjectMapper
//import org.junit.jupiter.api.AfterEach
//import org.junit.jupiter.api.Assertions.*
//import org.junit.jupiter.api.BeforeEach
//import org.junit.jupiter.api.Test
//import org.springframework.beans.factory.annotation.Autowired
//import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
//import org.springframework.boot.test.context.SpringBootTest
//import org.springframework.http.MediaType
//import org.springframework.test.web.servlet.MockMvc
//import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
//import org.springframework.test.web.servlet.result.MockMvcResultMatchers
//import java.math.BigDecimal
//
//@SpringBootTest
//@AutoConfigureMockMvc
//class OrderFlowIntegrationTest {
//    @Autowired
//    private lateinit var mockMvc: MockMvc
//
//    @Autowired
//    private lateinit var userRepository: UserRepository
//
//    @Autowired
//    private lateinit var restaurantRepository: RestaurantRepository
//
//    @Autowired
//    private lateinit var categoryRepository: CategoryRepository
//
//    @Autowired
//    private lateinit var productRepository: ProductRepository
//
//    @Autowired
//    private lateinit var orderRepository: OrderRepository
//
//    @Autowired
//    private lateinit var cardRepository: CardRepository
//
//    @Autowired
//    private lateinit var objectMapper: ObjectMapper
//
//    @Autowired
//    private lateinit var jwtUtils: JwtUtils
//
//    private lateinit var authToken: String
//    private lateinit var testUser: User
//    private lateinit var testRestaurant: Restaurant
//    private lateinit var testProduct: Product
//    private lateinit var testCard: Card
//
//    @BeforeEach
//    fun setup() {
//        // Create test user
//        testUser = userRepository.save(User(
//            username = "testuser",
//            phoneNumber = "+998901234567",
//            password = "password",
//            role = Roles.CUSTOMER
//        ))
//
//        // Create test restaurant
//        testRestaurant = restaurantRepository.save(Restaurant(
//            name = "Test Restaurant",
//            contact = "123456",
//            location = "Test Location"
//        ))
//
//        // Create category
//        val category = categoryRepository.save(Category(
//            name = "Test Category",
//            description = "Test Description",
//            restaurant = testRestaurant
//        ))
//
//        // Create product
//        testProduct = productRepository.save(Product(
//            name = "Test Product",
//            price = BigDecimal("15.00"),
//            description = "Test Description",
//            image = "test.jpg",
//            category = category
//        ))
//
//        // Add card to user
//        testCard = cardRepository.save(Card(
//            cardNumber = "************1234",
//            expiryDate = "12/25",
//            cardHolderName = "Test User",
//            cardType = CardType.UZCARD,
//            balance = BigDecimal("500.00"),
//            isDefault = true,
//            user = testUser
//        ))
//
//        // Generate auth token
//        authToken = "Bearer " + jwtUtils.generateToken(testUser).accessToken
//    }
//
//    @Test
//    fun `createOrder should fail with zero quantity`() {
//        val createOrderRequest = CreateOrderRequest(
//            restaurantId = testRestaurant.id!!,
//            items = listOf(OrderItemRequest(testProduct.id!!, 0)),
//            paymentOption = PaymentOption.CARD,
//            deliveryAddress = AddressDTO(id = null,"123 Test St", "Test City", 10.0f, 20.0f)
//        )
//
//        mockMvc.perform(MockMvcRequestBuilders.post("/api/orders")
//            .contentType(MediaType.APPLICATION_JSON)
//            .header("Authorization", authToken)
//            .content(objectMapper.writeValueAsString(createOrderRequest)))
//            .andExpect(MockMvcResultMatchers.status().isBadRequest)
//    }
//
//    @Test
//    fun `createOrder should fail with negative quantity`() {
//        val createOrderRequest = CreateOrderRequest(
//            restaurantId = testRestaurant.id!!,
//            items = listOf(OrderItemRequest(testProduct.id!!, -1)),
//            paymentOption = PaymentOption.CARD,
//            deliveryAddress = AddressDTO(id = null,"123 Test St", "Test City", 10.0f, 20.0f)
//        )
//
//        mockMvc.perform(MockMvcRequestBuilders.post("/api/orders")
//            .contentType(MediaType.APPLICATION_JSON)
//            .header("Authorization", authToken)
//            .content(objectMapper.writeValueAsString(createOrderRequest)))
//            .andExpect(MockMvcResultMatchers.status().isBadRequest)
//    }
//
//    @Test
//    fun `processPayment should fail with zero amount`() {
//        val paymentRequest = PaymentRequest(
//            amount = BigDecimal.ZERO,
//            paymentOption = PaymentOption.CARD
//        )
//
//        mockMvc.perform(MockMvcRequestBuilders.post("/api/orders/1/payment")
//            .contentType(MediaType.APPLICATION_JSON)
//            .header("Authorization", authToken)
//            .content(objectMapper.writeValueAsString(paymentRequest)))
//            .andExpect(MockMvcResultMatchers.status().isBadRequest)
//    }
//
//    @Test
//    fun `processPayment should fail with negative amount`() {
//        val paymentRequest = PaymentRequest(
//            amount = BigDecimal("-10.00"),
//            paymentOption = PaymentOption.CARD
//        )
//
//        mockMvc.perform(MockMvcRequestBuilders.post("/api/orders/1/payment")
//            .contentType(MediaType.APPLICATION_JSON)
//            .header("Authorization", authToken)
//            .content(objectMapper.writeValueAsString(paymentRequest)))
//            .andExpect(MockMvcResultMatchers.status().isBadRequest)
//    }
//
//    @Test
//    fun `addCard should fail with expired card`() {
//        val addCardRequest = AddCardRequest(
//            cardNumber = "1234567812345678",
//            expiryDate = "12/20", // Expired date
//            cardHolderName = "Test User",
//            cardType = CardType.UZCARD,
//            balance = BigDecimal("100.00")
//        )
//
//        mockMvc.perform(MockMvcRequestBuilders.post("/api/cards")
//            .contentType(MediaType.APPLICATION_JSON)
//            .header("Authorization", authToken)
//            .content(objectMapper.writeValueAsString(addCardRequest)))
//            .andExpect(MockMvcResultMatchers.status().isBadRequest)
//    }
//
//    @Test
//    fun `full order flow from creation to refund`() {
//        // Calculate the expected total amount
//        val itemQuantity = 2
//        val itemPrice = testProduct.price
//        val itemTotal = itemPrice.multiply(BigDecimal(itemQuantity))
//        val serviceCharge = itemTotal.multiply(BigDecimal("0.05"))
//        val deliveryFee = BigDecimal("10000")
//        val totalAmount = itemTotal.add(serviceCharge).add(deliveryFee)
//
//        // Create payment request
//        val paymentRequest = PaymentRequest(
//            amount = totalAmount,
//            paymentOption = PaymentOption.CARD
//        )
//
//        // Address as DTO with an id
//        val addressDTO = AddressDTO(
//            id = null,
//            addressLine = "123 Test St",
//            city = "Test City",
//            longitude = 10.0f,
//            latitude = 20.0f
//        )
//
//        // Step 1: Create an order
//        val createOrderRequest = CreateOrderRequest(
//            restaurantId = testRestaurant.id!!,
//            items = listOf(OrderItemRequest(testProduct.id!!, itemQuantity)),
//            paymentOption = PaymentOption.CARD,
//            deliveryAddress = addressDTO,
//        )
//
//        val createOrderResponse = mockMvc.perform(MockMvcRequestBuilders.post("/api/orders")
//            .contentType(MediaType.APPLICATION_JSON)
//            .header("Authorization", authToken)
//            .content(objectMapper.writeValueAsString(createOrderRequest)))
//            .andExpect(MockMvcResultMatchers.status().isOk)
//            .andReturn()
//            .response
//            .contentAsString
//
//        val orderDto = objectMapper.readValue(createOrderResponse, OrderDTO::class.java)
//
//        // Verify order was created with correct status
//        assertEquals(OrderStatus.PENDING, orderDto.status)
//        assertEquals(testUser.id, orderDto.customerId)
//        assertEquals(testRestaurant.id, orderDto.orderItems.sumOf { itemTotal })
//        assertEquals(1, orderDto.orderItems.size)
//        assertEquals(testProduct.id, orderDto.orderItems[0].productId)
//        assertEquals(itemQuantity, orderDto.orderItems[0].quantity)
//
//        // Step 2: Process payment
//        val processPaymentRequest = PaymentRequest(
//            amount = orderDto.orderItems.sumOf { totalAmount },
//            paymentOption = PaymentOption.CARD
//        )
//
//        mockMvc.perform(MockMvcRequestBuilders.post("/api/orders/${orderDto.id}/payment")
//            .contentType(MediaType.APPLICATION_JSON)
//            .header("Authorization", authToken)
//            .content(objectMapper.writeValueAsString(processPaymentRequest)))
//            .andExpect(MockMvcResultMatchers.status().isOk)
//
//        // Verify order status changed to ACCEPTED
//        val updatedOrder = orderRepository.findById(orderDto.id!!).get()
//        assertEquals(OrderStatus.ACCEPTED, updatedOrder.status)
//
//        // Verify card balance was reduced
//        val updatedCard = cardRepository.findById(testCard.id!!).get()
//        val expectedBalance = BigDecimal("500.00").subtract(totalAmount)
//        assertEquals(0, expectedBalance.compareTo(updatedCard.balance))
//
//        mockMvc.perform(MockMvcRequestBuilders.put("/api/orders/${orderDto.id}/status")
//            .contentType(MediaType.APPLICATION_JSON)
//            .header("Authorization", authToken)
//            .content(OrderStatus.IN_PROGRESS.name))
//            .andExpect(MockMvcResultMatchers.status().isOk)
//
//        // Verify status updated
//        val inProgressOrder = orderRepository.findById(orderDto.id!!).get()
//        assertEquals(OrderStatus.IN_PROGRESS, inProgressOrder.status)
//
//        // Step 4: Update order status to COMPLETED
//        mockMvc.perform(MockMvcRequestBuilders.put("/api/orders/${orderDto.id}/status")
//            .contentType(MediaType.APPLICATION_JSON)
//            .header("Authorization", authToken)
//            .content(OrderStatus.COMPLETED.name))
//            .andExpect(MockMvcResultMatchers.status().isOk)
//
//        // Verify status updated
//        val completedOrder = orderRepository.findById(orderDto.id!!).get()
//        assertEquals(OrderStatus.COMPLETED, completedOrder.status)
//
//        // Step 5: Refund the order
//        mockMvc.perform(MockMvcRequestBuilders.post("/api/orders/${orderDto.id}/refund")
//            .header("Authorization", authToken))
//            .andExpect(MockMvcResultMatchers.status().isOk)
//
//        // Verify order is refunded
//        val refundedOrder = orderRepository.findById(orderDto.id!!).get()
//        assertEquals(OrderStatus.REFUNDED, refundedOrder.status)
//
//        // Verify card balance was restored
//        val refundedCard = cardRepository.findById(testCard.id!!).get()
//        assertEquals(0, BigDecimal("500.00").compareTo(refundedCard.balance))
//    }
//
//    @AfterEach
//    fun cleanup() {
//        orderRepository.deleteAll()
//        productRepository.deleteAll()
//        categoryRepository.deleteAll()
//        restaurantRepository.deleteAll()
//        cardRepository.deleteAll()
//        userRepository.deleteAll()
//    }
//}
//
//
