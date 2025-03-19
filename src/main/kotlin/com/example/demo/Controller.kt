package com.example.demo

import org.springframework.data.domain.Pageable
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService
) {
    @PostMapping("/otp-login")
    fun otpLogin(@RequestBody otpLogin: OtpLogin) = authService.otpLogin(otpLogin)

    @PostMapping("/request-otp")
    fun requestOtp(@RequestBody request: OtpRequest) = authService.requestOtp(request)

    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest) = authService.login(request)

    @PostMapping("/refresh")
    fun refresh(@RequestBody refreshRequest: RefreshTokenRequest) = authService.refreshToken(refreshRequest)
}

@RestController
@RequestMapping("/api/v1/users")
class UserController(
    private val userService: UserService
) {
    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @PostMapping()
    fun createUser(@RequestBody request: CreateUserRequest) = userService.createUser(request)

    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @GetMapping()
    fun getAllUsers(pageable: Pageable) = userService.getAllUsers(pageable)

    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @GetMapping("/{userId}")
    fun getUserById(@PathVariable userId: Long) = userService.getUserById(userId)

    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @PutMapping("/{userId}")
    fun updateUser(@PathVariable userId: Long, @RequestBody updateUserRequest: UpdateUserRequest) = userService.updateUser(userId, updateUserRequest)

    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @DeleteMapping("/{userId}")
    fun deleteUserById(@PathVariable userId: Long) = userService.deleteUser(userId)

    @PostMapping("/{userId}")
    fun addAddress(@PathVariable userId: Long, @RequestBody addressRequest: AddressRequest) = userService.addAddress(userId, addressRequest)
}

@RestController
@RequestMapping("/api/v1/restaurant")
class RestaurantController(
    private val restaurantService: RestaurantService
) {
    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @PostMapping
    fun createRestaurant(@RequestBody request: CreateRestaurantRequest) =
        restaurantService.create(request)

    @GetMapping("/{restaurantId}")
    fun getRestaurantById(@PathVariable restaurantId: Long) = restaurantService.getRestaurantById(restaurantId)
}

@RestController
@RequestMapping("/api/v1/category")
class CategoryController(
    private val categoryService: CategoryService
) {
    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @PostMapping
    fun createCategory(@RequestBody createCategoryRequest: CreateCategoryRequest) = categoryService.createCategory(createCategoryRequest)

    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @PutMapping("/{categoryId}")
    fun updateCategory(@PathVariable categoryId: Long, @RequestBody updateCategoryRequest: UpdateCategoryRequest) = categoryService.updateCategory(categoryId, updateCategoryRequest)

    @GetMapping("/{categoryId}")
    fun getCategoryById(@PathVariable categoryId: Long) = categoryService.getCategoryById(categoryId)

    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @GetMapping
    fun getAllCategories(pageable: Pageable) = categoryService.getAllCategories(pageable)

    @GetMapping("/available")
    fun getAllAvailableCategories(pageable: Pageable) = categoryService.getAllAvailableCategories(pageable)

    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @DeleteMapping("/{categoryId}")
    fun deleteCategory(@PathVariable categoryId: Long) = categoryService.deleteCategoryById(categoryId)
}

@RestController
@RequestMapping("/api/v1/products")
class ProductController(
    private val productService: ProductService
) {
    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @PostMapping
    fun createProduct(@RequestBody createProductRequest: CreateProductRequest) = productService.createProduct(createProductRequest)

    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @GetMapping("/{productId}")
    fun getProductById(@PathVariable productId: Long) = productService.getProductById(productId)

    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @GetMapping
    fun getAllProducts(pageable: Pageable) = productService.getAllProducts(pageable)

    @GetMapping("/available")
    fun getAllAvailableProducts(pageable: Pageable) = productService.getALlAvailableProducts(pageable)

    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @PutMapping("/{productId}")
    fun updateProduct(@PathVariable productId: Long, updateProductRequest: UpdateProductRequest) = productService.updateProduct(productId, updateProductRequest)

    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @DeleteMapping("/{productId}")
    fun deleteProduct(@PathVariable productId: Long) = productService.deleteProductById(productId)
}

@RestController
@RequestMapping("/api/v1/orders")
class OrderController(
    private val orderService: OrderService
) {
    @PostMapping("/{customerId}")
    fun createOrder(@PathVariable customerId: Long, @RequestBody createOrderRequest: CreateOrderRequest) = orderService.createOrder(customerId, createOrderRequest)

    @GetMapping("/{orderId}")
    fun getOrderById(@PathVariable orderId: Long) = orderService.getOrderById(orderId)

    @GetMapping("/customer/{customerId}")
    fun getCustomerOrders(@PathVariable customerId: Long, pageable: Pageable) = orderService.getCustomerOrders(customerId, pageable)

    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @GetMapping("/restaurant/{restaurantId}")
    fun getRestaurantOrders(@PathVariable restaurantId: Long, pageable: Pageable) = orderService.getRestaurantOrders(restaurantId, pageable)

    @PutMapping("/{orderId}/status")
    fun updateOrderStatus(@PathVariable orderId: Long, @RequestParam status: OrderStatus) = orderService.updateOrderStatus(orderId, status)

    @PostMapping("/{orderId}/payment")
    fun processPayment(@PathVariable orderId: Long, @RequestBody paymentRequest: PaymentRequest) = orderService.processPayment(orderId, paymentRequest)

    @PostMapping("/{orderId}/refund")
    fun refundOrder(@PathVariable orderId: Long) = orderService.refundOrder(orderId)
}

@RestController
@RequestMapping("/api/v1/cards")
class CardController(
    private val cardService: CardService
) {
    @PostMapping("/{userId}")
    fun addCard(@PathVariable userId: Long, @RequestBody request: AddCardRequest) = cardService.addCard(userId, request)

    @GetMapping("/{userId}")
    fun getUserCards(@PathVariable userId: Long) = cardService.getUserCards(userId)

    @PutMapping("/{userId}/{cardId}/default")
    fun setDefaultCard(@PathVariable userId: Long, @PathVariable cardId: Long) = cardService.setDefaultCard(userId, cardId)

    @PutMapping("/{userId}/cards/{cardId}/balance")
    fun updateCardBalance(@PathVariable userId: Long, @PathVariable cardId: Long, @RequestBody request: UpdateCardBalanceRequest) = cardService.updateCardBalance(userId, cardId, request.amount)

    @DeleteMapping("/{userId}/{cardId}")
    fun deleteCard(@PathVariable userId: Long, @PathVariable cardId: Long) = cardService.deleteCard(userId, cardId)
}

