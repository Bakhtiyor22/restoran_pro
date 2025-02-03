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
import java.util.regex.Pattern

interface FieldName {
    val name: String?
}

fun validatePhoneNumber(phoneNumber: String): Boolean {
    return try {
        phoneNumber.matches(Regex("^\\+998[0-9]{9}$"))
    } catch (e: InvalidPhoneNumber) {
        false
    }
}

@Component
class JwtUtils {
    private val key = Keys.secretKeyFor(SignatureAlgorithm.HS256)

    fun generateToken(user: User): TokenResponse {
        val token = Jwts.builder()
            .setSubject(user.phoneNumber)
            .claim("role", user.role.name)
            .claim("userId", user.id)
            .setIssuedAt(Date())
            .setExpiration(Date(System.currentTimeMillis() + 3600000))
            .signWith(key)
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
class CustomValidator {
    private val AT_LEAST_ONE_UPPERCASE_LETTER_LOWERCASE_LETTER_NUMBER_AND_SPECIAL_CHARACTER =
        "^(?=.*?[A-Z])(?=.*?[a-z])(?=.*?[0-9])(?=.*?[#?!@$%^&*-]).{8,}$"

    fun notNull(fieldName: FieldName, value: Any?) {
        if (value == null) {
            throw ValidationException()
        }
    }

    fun notBlank(fieldName: FieldName, value: String?) {
        if (value == null || value.trim { it <= ' ' }.isEmpty()) {
            throw ValidationException()
        }
    }

    fun validatePasswordStrength(fieldName: FieldName?, value: String?) {
        if (fieldName != null) {
            matchesRegex(fieldName, value, AT_LEAST_ONE_UPPERCASE_LETTER_LOWERCASE_LETTER_NUMBER_AND_SPECIAL_CHARACTER)
        }
    }

    fun matchesRegex(fieldName: FieldName, value: String?, regex: String?) {
        if (value == null || !Pattern.matches(regex, value)) {
            throw ValidationException()
        }
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