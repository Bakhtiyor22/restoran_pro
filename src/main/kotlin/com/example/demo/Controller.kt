package com.example.demo

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService
) {
    @PostMapping("/otp-login")
    fun otpLogin(@RequestBody otpLogin: OtpLogin, @RequestBody chatId: Long?) = authService.otpLogin(otpLogin, chatId!!)

    @PostMapping("/request-otp")
    fun requestOtp(@RequestBody request: OtpRequest, @RequestBody chatId: Long?) = authService.requestOtp(request, chatId!!)

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

    @GetMapping("/search")
    fun searchProducts(
        @RequestParam(required = false) name: String?,
        @RequestParam(required = false) categoryId: Long?,
        @RequestParam(required = false) minPrice: BigDecimal?,
        @RequestParam(required = false) maxPrice: BigDecimal?,
        @RequestParam(required = false) sortByNew: Boolean = false,
        pageable: Pageable
    ): Page<ProductDTO> {
        if (sortByNew) {
            return productService.getNewlyAddedProducts(pageable)
        }
        return productService.searchProducts(name, categoryId, minPrice, maxPrice, pageable)
    }
}

@RestController
@RequestMapping("/api/v1/orders")
class OrderController(
    private val orderService: OrderService,
    private val downloadService: DownloadService
) {
    @PostMapping
    fun createOrder(@RequestBody createOrderRequest: CreateOrderRequest) = orderService.createOrder(createOrderRequest)

    @GetMapping("/{orderId}")
    fun getOrderById(@PathVariable orderId: Long) = orderService.getOrderById(orderId)

    @GetMapping("/customer/{userId}")
    fun getCustomerOrders(@PathVariable userId: Long, pageable: Pageable) = orderService.getCustomerOrders(userId, pageable)

    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @GetMapping("/restaurant/{restaurantId}")
    fun getRestaurantOrders(@PathVariable restaurantId: Long, pageable: Pageable) = orderService.getRestaurantOrders(restaurantId, pageable)

    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @PutMapping("/{orderId}/status")
    fun updateOrderStatus(@PathVariable orderId: Long, @RequestParam status: OrderStatus) = orderService.updateOrderStatus(orderId, status)

    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @PostMapping("/{orderId}/payment")
    fun processPayment(@PathVariable orderId: Long, @RequestBody paymentRequest: PaymentRequest) = orderService.processPayment(orderId, paymentRequest)

    @PostMapping("/{orderId}/refund")
    fun refundOrder(@PathVariable orderId: Long) = orderService.refundOrder(orderId)

    @GetMapping("/search")
    fun searchOrders(
        @RequestParam(required = false) startDate: LocalDate?,
        @RequestParam(required = false) endDate: LocalDate?,
        @RequestParam(required = false) status: OrderStatus?,
        @RequestParam(required = false) paymentType: PaymentOption?,
        @RequestParam(required = false) orderId: Long?,
        @RequestParam(required = false) customerId: Long?,
        @RequestParam(required = false) restaurantId: Long?,
        pageable: Pageable
    ): Page<OrderDTO> {
        return orderService.searchOrders(
            startDate, endDate, status, paymentType,
            orderId, customerId, restaurantId, pageable
        )
    }

    @GetMapping("/download/excel")
    fun downloadOrdersExcel(
        @RequestParam startDate: LocalDate?,
        @RequestParam endDate: LocalDate?,
        @RequestParam status: OrderStatus?,
        @RequestParam paymentType: PaymentOption?,
        @RequestParam orderId: Long?,
        @RequestParam customerId: Long?,
        @RequestParam restaurantId: Long?
    ): ResponseEntity<ByteArray> {
        val bytes = orderService.downloadOrdersAsExcel(startDate, endDate, status, paymentType, orderId, customerId, restaurantId)

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=orders.xlsx")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(bytes)
    }

    @GetMapping("/download/pdf")
    fun downloadOrdersPdf(
        @RequestParam startDate: LocalDate?,
        @RequestParam endDate: LocalDate?,
        @RequestParam status: OrderStatus?,
        @RequestParam paymentType: PaymentOption?,
        @RequestParam orderId: Long?,
        @RequestParam customerId: Long?,
        @RequestParam restaurantId: Long?
    ): ResponseEntity<ByteArray> {
        val bytes = orderService.downloadOrdersAsExcel(startDate, endDate, status, paymentType, orderId, customerId, restaurantId)

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=orders.pdf")
            .contentType(MediaType.APPLICATION_PDF)
            .body(bytes)
    }

    @GetMapping("/download/csv")
    fun downloadOrdersCsv(
        @RequestParam startDate: LocalDate?,
        @RequestParam endDate: LocalDate?,
        @RequestParam status: OrderStatus?,
        @RequestParam paymentType: PaymentOption?,
        @RequestParam orderId: Long?,
        @RequestParam customerId: Long?,
        @RequestParam restaurantId: Long?
    ): ResponseEntity<ByteArray> {
        val bytes = orderService.downloadOrdersAsExcel(startDate, endDate, status, paymentType, orderId, customerId, restaurantId)

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=orders.csv")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(bytes)
    }

    @GetMapping("/download/json")
    fun downloadOrdersJson(
        @RequestParam startDate: LocalDate?,
        @RequestParam endDate: LocalDate?,
        @RequestParam status: OrderStatus?,
        @RequestParam paymentType: PaymentOption?,
        @RequestParam orderId: Long?,
        @RequestParam customerId: Long?,
        @RequestParam restaurantId: Long?
    ): ResponseEntity<ByteArray> {
        val bytes = orderService.downloadOrdersAsExcel(startDate, endDate, status, paymentType, orderId, customerId, restaurantId)

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=orders.json")
            .contentType(MediaType.APPLICATION_JSON)
            .body(bytes)
    }
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

@RestController
@RequestMapping("/api/v1/addresses")
class AddressController(private val addressService: AddressService) {

    @PostMapping
    fun addAddress(@RequestBody request: AddressRequest): AddressDTO = addressService.addAddress(request)

    @GetMapping("/{addressId}")
    fun getAddress(@PathVariable addressId: Long): AddressDTO = addressService.getAddressById(addressId)

    @PutMapping("/{addressId}")
    fun updateAddress(
        @PathVariable addressId: Long,
        @RequestBody request: AddressRequest
    ): AddressDTO = addressService.updateAddress(addressId, request)


    @DeleteMapping("/{addressId}")
    fun deleteAddress(@PathVariable addressId: Long) = addressService.deleteAddress(addressId)
}

@RestController
@RequestMapping("/api/v1/dashboard")
class DashboardController(private val dashboardService: DashboardService) {

    @GetMapping("/sales/monthly")
    fun getMonthlySales(): Map<String, Any> = mapOf("totalSales" to dashboardService.getTotalSalesForCurrMonth())


    @GetMapping("/sales/daily")
    fun getDailySales(): Map<String, Any> = mapOf("totalSales" to dashboardService.getTotalSalesForCurrDay()!!)


    @GetMapping("/sales/range")
    fun getSalesInRange(
        @RequestParam startDate: LocalDate,
        @RequestParam endDate: LocalDate
    ): Map<String, Any> = mapOf(
            "totalSales" to dashboardService.getSalesBetweenDates(startDate, endDate),
            "averageDailySales" to dashboardService.getAverageDailySales(startDate, endDate)
    )

    @GetMapping("/products/best-selling")
    fun getBestSellingProducts(): List<ProductDTO> = dashboardService.mostSoldProducts().map { it.toDto() }

    @GetMapping("/buyers/top")
    fun getTopBuyers(@RequestParam(defaultValue = "10") limit: Int): List<Map<String, Any>> = dashboardService.getTopBuyers(limit)

    @GetMapping("/products/least-selling/30-days")
    fun getLeastSellingProductsFor30Days(): List<LeastProductsResponse> = dashboardService.getLeastSellingProductsFor30Days()
}