package com.example.demo

import io.jsonwebtoken.JwtParserBuilder
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.security.Keys
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Component
import java.util.*
import javax.crypto.SecretKey

fun validatePhoneNumber(phoneNumber: String): Boolean {
    val normalizedNumber = if (phoneNumber.startsWith("+")) {
        phoneNumber
    } else {
        "+$phoneNumber"
    }

    return try {
        normalizedNumber.matches(Regex("^\\+998[0-9]{9}$"))
    } catch (e: Exception) {
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

    private val accessTokenExpiry = 15 * 60 * 1000L

    private val refreshTokenExpiry = 7 * 24 * 60 * 60 * 1000L

    fun generateToken(user: User, locale: String? = null): TokenResponse {
        val now = Date()

        val accessToken = Jwts.builder()
            .setSubject(user.phoneNumber)
            .claim("role", user.role.name)
            .claim("userId", user.id)
            .claim("locale", locale ?: LocaleContextHolder.getLocale().language)
            .claim("tokenType", "ACCESS")
            .setIssuedAt(now)
            .setExpiration(Date(now.time + accessTokenExpiry))
            .signWith(key, SignatureAlgorithm.HS256)
            .compact()

        val refreshToken = Jwts.builder()
            .setSubject(user.phoneNumber)
            .claim("locale", locale ?: LocaleContextHolder.getLocale().language)
            .claim("tokenType", "REFRESH")
            .setIssuedAt(now)
            .setExpiration(Date(now.time + refreshTokenExpiry))
            .signWith(key, SignatureAlgorithm.HS256)
            .compact()

        return TokenResponse(accessToken, refreshToken, accessTokenExpiry / 1000)
    }

    fun extractLocale(token: String): String {
        val claims = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).body
        return claims.get("locale", String::class.java) ?: "uz"
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

    fun getJwtParser(): JwtParserBuilder {
        return Jwts.parserBuilder().setSigningKey(key)
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

fun getCurrentUser(userRepository: UserRepository): User {
    val authentication = SecurityContextHolder.getContext().authentication
    val username = authentication.name
    return userRepository.findByPhoneNumber(username)
        ?: throw ResourceNotFoundException(username)
}