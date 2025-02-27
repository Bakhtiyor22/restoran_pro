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

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest) = authService.login(request)
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
    fun getAllUsers(
      pageable: Pageable
    ) = userService.getAllUsers(pageable)

    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @GetMapping("/{id}")
    fun getUserById(@PathVariable id: Long) = userService.getUserById(id)

    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @PutMapping("/{id}")
    fun updateUser(@PathVariable id: Long, @RequestBody updateUserRequest: UpdateUserRequest) = userService.updateUser(id, updateUserRequest)

    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @DeleteMapping("/{id}")
    fun deleteUserById(@PathVariable id: Long) = userService.deleteUser(id)
}

@RestController
@RequestMapping("/api/v1/restaurant")
class RestaurantController(
    private val restaurantService: RestaurantService
) {
    @PreAuthorize("hasRole('DEV')")
    @PostMapping
    fun createRestaurant(@RequestBody request: CreateRestaurantRequest) =
        restaurantService.create(request)
}

@RestController
@RequestMapping("/api/v1/menus")
class MenuController(
    private val menuService: MenuService
) {
    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @PostMapping
    fun createMenu(@RequestBody createMenuRequest: CreateMenuRequest) = menuService.createMenu(createMenuRequest)

    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @PutMapping("/{menuId}")
    fun updateMenu(@PathVariable menuId: Long, @RequestBody updateMenuRequest: CreateMenuRequest) = menuService.updateMenu(menuId, updateMenuRequest)

    @GetMapping
    fun getAllMenus(pageable: Pageable) = menuService.getAllMenus(pageable)

    @PreAuthorize("hasAnyRole('MANAGER','DEV','EMPLOYEE')")
    @GetMapping("/{menuId}")
    fun getMenuById(@PathVariable menuId: Long) = menuService.getMenuById(menuId)

    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @DeleteMapping("/{menuId}")
    fun deleteMenuById(@PathVariable menuId: Long) = menuService.deleteMenu(menuId)

    @PreAuthorize("hasAnyRole('MANAGER','DEV','EMPLOYEE')")
    @PatchMapping("/{menuId}/items")
    fun addMenuItem(@PathVariable menuId: Long, @RequestBody addMenuItem: AddMenuItem) = menuService.addMenuItem(menuId, addMenuItem)

    @PreAuthorize("hasAnyRole('MANAGER','DEV','EMPLOYEE')")
    @PatchMapping("/{menuId}/items/{menuItemId}/remove")
    fun removeMenuItem(@PathVariable menuId: Long, @PathVariable menuItemId: Long) = menuService.removeMenuItem(menuId, menuItemId)

    @PreAuthorize("hasAnyRole('MANAGER','DEV','EMPLOYEE')")
    @PutMapping("/items/{menuItemId}")
    fun updateMenuItem(@PathVariable menuItemId: Long, @RequestBody addMenuItem: AddMenuItem) = menuService.updateMenuItem(menuItemId, addMenuItem)

    @GetMapping("/{menuId}/items")
    fun getAllMenuItems(@PathVariable menuId: Long, pageable: Pageable) = menuService.getAllMenuItems(menuId, pageable)
}

@RestController
@RequestMapping("/api/v1/orders")
class OrderController(
    private val orderService: OrderService
) {
    @PostMapping("/{userId}/{restaurantId}")
    fun createOrder(@PathVariable userId: Long, @PathVariable restaurantId: Long, @RequestBody createOrderRequest: CreateOrderRequest
    ) = orderService.createOrder(userId, restaurantId, createOrderRequest)

    @PreAuthorize("hasAnyRole('MANAGER','DEV')")
    @PutMapping("/{orderId}/status")
    fun updateOrderStatus(@PathVariable orderId: Long, @RequestBody updateOrderStatusRequest: UpdateOrderStatusRequest
    ) = orderService.updateOrderStatus(orderId, updateOrderStatusRequest)

    @GetMapping("/user/{userId}")
    fun getUserOrders(@PathVariable userId: Long, pageable: Pageable) = orderService.getUserOrders(userId, pageable)
}

@RestController
@RequestMapping("/api/v1/payments")
class PaymentController (
    private val paymentService: PaymentService
) {
    @PostMapping("/pay/{userId}/{orderId}")
    fun processOrderPayment(@PathVariable userId: Long,  @PathVariable orderId: Long?, @RequestBody paymentRequest: PaymentRequest) = paymentService.processOrderPayment(userId, orderId, paymentRequest)

    @GetMapping("/history/{userId}")
    fun getPaymentHistory(@PathVariable userId: Long) = paymentService.getPaymentHistory(userId)

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
@RequestMapping("/api/v1/cart")
class CartController(
    private val cartService: CartService
) {
    @PostMapping("/{userId}/{restaurantId}/items")
    fun addToCart(@PathVariable userId: Long, @PathVariable restaurantId: Long, @RequestBody request: AddToCartRequest) = cartService.addToCart(userId, restaurantId, request)

    @GetMapping("/{userId}")
    fun getCart(@PathVariable userId: Long) = cartService.getUserCart(userId)

    @GetMapping("/{userId}/checkout")
    fun getCheckoutPreview(@PathVariable userId: Long) = cartService.getCheckoutPreview(userId)

    @PostMapping("/{userId}/complete")
    fun completeOrder(@PathVariable userId: Long, @RequestBody paymentRequest: PaymentRequest) = cartService.completeOrder(userId, paymentRequest)

    @PutMapping("/{userId}/clear")
    fun clearCart(@PathVariable userId: Long) = cartService.clearCart(userId)

    @PatchMapping("/{userId}/{menuItemId}")
    fun removeFromCart(@PathVariable userId: Long, @PathVariable menuItemId: Long ) = cartService.removeFromCart(userId, menuItemId)
}