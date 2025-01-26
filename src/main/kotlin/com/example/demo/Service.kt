package com.example.demo

import org.slf4j.LoggerFactory
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

interface OTPService {
    fun generateOTP(phoneNumber: String): String
    fun validateOTP(phoneNumber: String, otp: String): Boolean
}

interface  EskizService{
    fun sendMessage(msg:String, phoneNumber: String):Boolean
}

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val otpService: OTPService,
    private val jwtUtils: JwtUtils
) {

    fun otpLogin(phoneNumber: String, otp: String): String {
        val valid = otpService.validateOTP(phoneNumber, otp)
        if (!valid) {
            throw IllegalArgumentException("Invalid OTP!")
        }
        var user = userRepository.findByPhoneNumber(phoneNumber)
        if (user == null) {
            user = User(
                phoneNumber = phoneNumber,
                password = "",
                role = Roles.CUSTOMER
            )
            userRepository.save(user)
        }
        return jwtUtils.generateToken(user.phoneNumber, user.role.name)
    }

    fun requestOtp(phoneNumber: String): String {
        otpService.generateOTP(phoneNumber)
        return "OTP sent, check your phone!"
    }

    fun login(phoneNumber: String, password: String): String {
        val user = userRepository.findByPhoneNumber(phoneNumber)
            ?: throw IllegalArgumentException("User not found")
        if (user.password.isBlank()) {
            throw IllegalArgumentException("No password set for this user. Please use OTP or set a password.")
        }
        return jwtUtils.generateToken(phoneNumber, user.role.name)
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


@Service
class EskizImp:EskizService{
    private  val logger = LoggerFactory.getLogger(javaClass)

    override fun sendMessage(msg: String, phoneNumber: String): Boolean {
        logger.info("Send message success , $phoneNumber , message : $msg")
        return true
    }
}

@Service
class OTPServiceImpl(private val eskizService: EskizService) : OTPService {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val otpStore = ConcurrentHashMap<String, String>()
    private val otpTimestampStore = ConcurrentHashMap<String, Long>()
    private val otpValidityDuration = 60 * 1000

    override fun generateOTP(phoneNumber: String): String {
        val otp = (100000..999999).random().toString()
        otpStore[phoneNumber] = otp
        otpTimestampStore[phoneNumber] = System.currentTimeMillis()
        val message = "Your OTP is $otp"
        eskizService.sendMessage(message, phoneNumber)
        logger.info("Generated OTP=$otp for phoneNumber=$phoneNumber")
        return otp
    }

    override fun validateOTP(phoneNumber: String, otp: String): Boolean {
        val storedOtp = otpStore[phoneNumber]
        val timestamp = otpTimestampStore[phoneNumber]
        val currentTime = System.currentTimeMillis()

        if (storedOtp == null || timestamp == null || currentTime - timestamp > otpValidityDuration) {
            otpStore.remove(phoneNumber)
            otpTimestampStore.remove(phoneNumber)
            return false
        }

        return (storedOtp == otp).also {
            logger.info("Validate OTP=$otp for phoneNumber=$phoneNumber => $it")
            if (it) {
                otpStore.remove(phoneNumber)
                otpTimestampStore.remove(phoneNumber)
            }
        }
    }
}

