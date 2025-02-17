package com.example.demo

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.CommandLineRunner
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.data.domain.AuditorAware
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor
import java.util.*


@Configuration
class AppInit(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder
) {

    @Bean
    fun init() = CommandLineRunner {
        val managerRole = Roles.MANAGER
        val devRole = Roles.DEV

        if (userRepository.findByRole(managerRole) == null) {
            val manager = User(
                username =  "MANAGER",
                phoneNumber = "+998900000001",
                password = passwordEncoder.encode("manager123"),
                role = managerRole
            )
            userRepository.save(manager)
        }

        if (userRepository.findByRole(devRole) == null) {
            val dev = User(
                username = "DEV",
                phoneNumber = "+998900000002",
                password = passwordEncoder.encode("dev123"),
                role = devRole
            )
            userRepository.save(dev)
        }
    }
}

@Configuration
@EnableJpaAuditing
class AuditingConfig {
    @Bean
    fun auditorProvider(): AuditorAware<String> {
        return AuditorAwareImpl()
    }
}

class AuditorAwareImpl : AuditorAware<String> {
    override fun getCurrentAuditor(): Optional<String> {
        return Optional.of("system")
    }
}

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
class SecurityConfig(
    private val customUserDetailsService: CustomUserDetailsService,
    private val jwtAuthFilter: JwtAuthFilter
) {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun authenticationProvider(): DaoAuthenticationProvider {
        val provider = DaoAuthenticationProvider()
        provider.setUserDetailsService(customUserDetailsService)
        provider.setPasswordEncoder(passwordEncoder())
        return provider
    }

    @Bean
    fun authenticationManager(http: HttpSecurity): AuthenticationManager {
        val authBuilder = http.getSharedObject(AuthenticationManagerBuilder::class.java)
        authBuilder.userDetailsService(customUserDetailsService)
        return authBuilder.build()
    }

    @Bean
    fun filterChain(http: HttpSecurity, authManager: AuthenticationManager): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers("/api/v1/auth/**").permitAll()
                auth.anyRequest().authenticated()
            }
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }
}

@Component
class JwtAuthFilter(
    private val jwtUtils: JwtUtils,
    private val userService: CustomUserDetailsService
) : OncePerRequestFilter() {


    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val authHeader = request.getHeader("Authorization")
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            val token = authHeader.substring(7)
            if (jwtUtils.validateToken(token)) {
                val username = jwtUtils.extractUsername(token)
                val userDetails = userService.loadUserByUsername(username)
                val authToken = jwtUtils.getAuthentication(token, userDetails)
                SecurityContextHolder.getContext().authentication = authToken
                filterChain.doFilter(request, response)
                return
            }
        }
        filterChain.doFilter(request, response)
    }
}


@Configuration
class AppConfig : WebMvcConfigurer {

    @Bean
    fun messageSource(): MessageSource {
        val source = ResourceBundleMessageSource()
        source.setBasenames("error")
        source.setDefaultEncoding("UTF-8")
        return source
    }

    @Bean
    fun localeResolver(): AcceptHeaderLocaleResolver {
        val resolver = AcceptHeaderLocaleResolver()
        resolver.setDefaultLocale(Locale.forLanguageTag("uz"))
        return resolver
    }

    @Bean
    fun localeChangeInterceptor(): LocaleChangeInterceptor {
        val interceptor = LocaleChangeInterceptor()
        interceptor.paramName = "lang"
        return interceptor
    }

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(localeChangeInterceptor())
    }
}