package com.example.demo

import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
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
    fun requestOtp(phoneNumber: String): OtpIdResponse
    fun otpLogin(otpLogin: OtpLogin): TokenResponse
    fun login(request: LoginRequest): TokenResponse
}


interface MessageSourceService {
    fun getMessage(key: MessageKey): String
}

@Service
class AuthServiceImpl(
    private val otpService: OTPService,
    private val userRepository: UserRepository,
    private val otpRepository: OtpRepository,
    private val jwtUtils: JwtUtils,
    private val customValidator: CustomValidator,
    private val messageSource: MessageSource,
    private val passwordEncoder: PasswordEncoder

) : AuthService {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun requestOtp(phoneNumber: String): OtpIdResponse {
        val locale = LocaleContextHolder.getLocale()

        customValidator.notBlank(object : FieldName {
            override val name = "phoneNumber"
        }, phoneNumber)

        if (!validatePhoneNumber(phoneNumber)) throw InvalidPhoneNumber()


        val foundUser = userRepository.findByPhoneNumber(phoneNumber)
        val userStatus = when {
            foundUser != null -> UserStatus.EXISTS
            else -> UserStatus.NOT_FOUND
        }

        val otpId = otpService.generateOTP(phoneNumber)
        logger.info("requestOtp => phone=$phoneNumber, userStatus=$userStatus, otpId=$otpId")

        val messageKey = "otp.register"
        val messageText = messageSource.getMessage(messageKey, null, locale)
        return OtpIdResponse(otpId, messageText)
//        return BaseMessage(code = 0, message = "$messageText. smsId=$otpId, userStatus=$userStatus")
    }

    override fun otpLogin(otpLogin: OtpLogin): TokenResponse {
        val locale = LocaleContextHolder.getLocale()

        val otpEntity = otpRepository.findByIdAndDeletedFalse(otpLogin.otpId) ?: throw ResourceNotFoundException()


        val smsRecord = otpRepository.findTopByPhoneNumberOrderByCreatedDateDesc(phoneNumber)
            ?: throw ResourceNotFoundException()

        val validOtp =K otpService.validateOTP(phoneNumber, otpCode, smsRecord.id!!)
        if (!validOtp) {
            val invalidKey = "INVALID_INPUT"
            val invalidMsg = messageSource.getMessage(invalidKey, null, locale)
            throw InvalidInputException().also { logger.warn("OTP mismatch: $invalidMsg") }
        }

        var user = userRepository.findByPhoneNumber(phoneNumber)
        val userStatus = when {
            user != null -> UserStatus.EXISTS
            else -> UserStatus.CREATED
        }
        if (user == null) {
            user = User(
                phoneNumber = phoneNumber,
                password = "",
                role = Roles.CUSTOMER
            )
            userRepository.save(user)
        }

        val tokenResponse = jwtUtils.generateToken(user)
        logger.info("otpLogin => phone=$phoneNumber, userStatus=$userStatus => token generated.")
        return tokenResponse
    }

    override fun login(request: LoginRequest): TokenResponse {
        val locale = LocaleContextHolder.getLocale()

        customValidator.notBlank(object : FieldName {
            override val name = "phoneNumber"
        }, request.phone)
        customValidator.notBlank(object : FieldName {
            override val name = "password"
        }, request.password)
        if (!validatePhoneNumber(request.phone)) throw InvalidPhoneNumber()


        val user = userRepository.findByPhoneNumber(request.phone)
            ?: throw UserNotFoundException(messageSource)

        if (!passwordEncoder.matches(request.password, user.password)) {
            val messageKey = "INVALID_INPUT"
            val errorMsg = messageSource.getMessage(messageKey, null, locale)
            throw InvalidInputException().also { logger.warn(" $errorMsg") }
        }

        val tokenResponse = jwtUtils.generateToken(user)
        logger.info("login => phone=${request.phone} => token generated.")
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

// ...existing code...

@Service
class OTPServiceImpl(
    private val eskizService: EskizService,
    private val messageSource: MessageSource,
    private val otpRepository: OtpRepository,
    private val passwordEncoder: PasswordEncoder
) : OTPService {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val requestCounts = ConcurrentHashMap<String, Int>()
    private val MAX_ATTEMPTS = 3

    override fun generateOTP(phoneNumber: String): Long {
        val attempts = requestCounts.getOrDefault(phoneNumber, 0)
        if (attempts >= MAX_ATTEMPTS) {
            throw InvalidInputException()
        }
        requestCounts[phoneNumber] = attempts + 1

        val otpCode = (100000..999999).random().toString()

        val now = LocalDateTime.now()
        val entity = OtpEntity(
            phoneNumber = phoneNumber,
            otpLogin = passwordEncoder.encode(otpCode),
            sentTime = now,
            expiredAt = now.plusMinutes(1), // example: OTP valid for 5 minutes
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

        val isMatch = (record.otpLogin == otpCode)
        if (isMatch) {
            record.checked = true
            otpRepository.save(record)
        } else {
            logger.warn("OTP mismatch => phone=$phoneNumber, code=$otpCode, stored=${record.otpLogin}")
        }
        return isMatch
    }
}

