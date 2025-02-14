package com.example.demo

import org.springframework.data.domain.Pageable
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
    @PostMapping()
    fun createUser(@RequestBody request: CreateUserRequest) = userService.createUser(request)

    @GetMapping()
    fun getAllUsers(
      pageable: Pageable
    ) = userService.getAllUsers(pageable)

    @GetMapping("/{id}")
    fun getUserById(@PathVariable id: Long) = userService.getUserById(id)

    @PutMapping("/{id}")
    fun updateUser(@PathVariable id: Long, @RequestBody updateUserRequest: UpdateUserRequest) = userService.updateUser(id, updateUserRequest)

    @DeleteMapping("/{id}")
    fun deleteUserById(@PathVariable id: Long) = userService.deleteUser(id)
}

@RestController
@RequestMapping("/api/v1/restaurant")
class RestaurantController(
    private val restaurantService: RestaurantService
) {
    @PostMapping
    fun createRestaurant(@RequestBody request: CreateRestaurantRequest) =
        restaurantService.create(request)
}

@RestController
@RequestMapping("/api/v1/menu")
class MenuController(
    private val menuService: MenuService
) {
    @PostMapping
    fun createMenu(@RequestBody createMenuRequest: createMenuRequest) = menuService.createMenu(createMenuRequest)

    @PatchMapping("/{menuId}")
    fun addMenuItem(@PathVariable menuId: Long, @RequestBody addMenuItem: addMenuItem) = menuService.addMenuItem(menuId, addMenuItem)
}