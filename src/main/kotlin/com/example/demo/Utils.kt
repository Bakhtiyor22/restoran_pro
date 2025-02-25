package com.example.demo

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.security.Keys
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.*
import javax.crypto.SecretKey

fun validatePhoneNumber(phoneNumber: String): Boolean {
    return try {
        phoneNumber.matches(Regex("^\\+998[0-9]{9}$"))
    } catch (e: InvalidInputException) {
        false
    }
}

@Component
class JwtUtils {

    private val secret: String = System.getenv("JWT_SECRET")
        ?: throw IllegalStateException("JWT_SECRET environment variable is not set")

    private val key: SecretKey by lazy {
        Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret))
    }

    fun generateToken(user: User): TokenResponse {
        val token = Jwts.builder()
            .setSubject(user.phoneNumber)
            .claim("role", user.role.name)
            .claim("userId", user.id)
            .setIssuedAt(Date())
            .setExpiration(Date(System.currentTimeMillis() + 3600000))
            .signWith(key, SignatureAlgorithm.HS256)
            .compact()

        return TokenResponse(token, token, 3600)
    }

    fun validateToken(token: String): Boolean {
        return try {
            Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token)
            true
        } catch (ex: Exception) {
            false
        }
    }

    fun extractUsername(token: String): String =
        Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).body.subject

    fun getAuthentication(token: String, userDetails: UserDetails): UsernamePasswordAuthenticationToken {
        return UsernamePasswordAuthenticationToken(userDetails, null, userDetails.authorities)
    }
}

@Component
class CardValidator {
    fun validateCard(number: String, cardType: CardType): Boolean {
        return number.length == 16 &&
                cardType.pattern.matches(number) &&
                validateLuhn(number)
    }

    private fun validateLuhn(number: String): Boolean {
        val digits = number.map { it.toString().toInt() }
        val sum = digits.reversed()
            .mapIndexed { index, digit ->
                if (index % 2 == 1) {
                    val doubled = digit * 2
                    if (doubled > 9) doubled - 9 else doubled
                } else digit
            }.sum()
        return sum % 10 == 0
    }
}