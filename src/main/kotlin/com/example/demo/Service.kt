package com.example.demo

import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtUtils: JwtUtils
) {
    fun otpLogin(userName: String, phoneNumber: String, otp: String): String {
        validatePhoneNumber(phoneNumber)
        if (otp != "12345") {
            throw IllegalArgumentException("Invalid OTP")
        }
        var user = userRepository.findByPhoneNumber(phoneNumber)
        if (user == null) {
            user = User(
                username = userName,
                phoneNumber = phoneNumber,
                password = passwordEncoder.encode(""),
                role = Roles.CUSTOMER
            )
            userRepository.save(user)
        }
        return jwtUtils.generateToken(user.username, user.role.name)
    }

    fun loginWithPassword(phoneNumber: String, password: String): String {
        validatePhoneNumber(phoneNumber)
        val user = userRepository.findByPhoneNumber(phoneNumber)
            ?: throw IllegalArgumentException("User not found")
        if (!passwordEncoder.matches(password, user.password)) {
            throw IllegalArgumentException("Invalid credentials")
        }
        return jwtUtils.generateToken(user.username, user.role.name)
    }
}

@Service
class UserService(
    private val userRepository: UserRepository
) {
    fun findUserById(id: Long): User {
        return userRepository.findById(id)
            .orElseThrow { IllegalArgumentException("User not found") }
    }

    fun updateUser(user: User): User {
        return userRepository.save(user)
    }

    fun deleteUserById(id: Long) {
        userRepository.deleteById(id)
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
