// src/test/kotlin/com/example/demo/DemoApplicationTests.kt
package com.example.demo

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class DemoApplicationTests {

    @Test
    fun contextLoads() {
        // Verifies if Spring context loads successfully
    }
}