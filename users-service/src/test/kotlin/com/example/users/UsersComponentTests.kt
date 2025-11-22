package com.example.users

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class UsersComponentTests {

    @Test
    fun `normalizeEmail trims and lowercases`() {
        // given
        val raw = "   USER.Example@Mail.RU   "

        // when
        val normalized = normalizeEmail(raw)

        // then
        assertEquals("user.example@mail.ru", normalized)
    }

    @Test
    fun `hashPassword and createToken work together`() {
        // given
        val password = "MyStr0ng_Password!"
        val secret = "test-jwt-secret"
        val userId = "f1f4e1cd-3f8a-4d8c-8e74-2fdc3c9fb123"
        val role = "ADMIN"

        // when: хэшируем пароль
        val hash = hashPassword(password)

        // then: правильный пароль проходит проверку
        assertTrue(verifyPassword(password, hash))
        // а неправильный — нет
        assertFalse(verifyPassword("wrong-$password", hash))

        // when: создаём JWT-токен
        val tokenTtlSeconds = 3600L
        val token = createToken(userId, role, secret, tokenTtlSeconds)

        // then: декодируем токен и проверяем полезную нагрузку
        val verifier = JWT.require(Algorithm.HMAC256(secret)).build()
        val decoded = verifier.verify(token)

        assertEquals(userId, decoded.subject)
        assertEquals(role, decoded.getClaim("role").asString())

        val expiresAt = decoded.expiresAt.toInstant()
        val now = Instant.now()
        // Токен должен быть действителен в будущем относительно "сейчас"
        assertTrue(expiresAt.isAfter(now))
    }
}

