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

class DateTimeUtil {
    fun getCurrentTimeZone(): ZonedDateTime {
        return ZonedDateTime.now(ZoneId.of(System.getenv("TIMEZONE")))
    }

    fun toLocalDateTime(zonedDateTime: ZonedDateTime?): LocalDateTime? {
        return zonedDateTime?.withZoneSameInstant(ZoneId.of(System.getenv("TIMEZONE")))?.toLocalDateTime()
    }
}
