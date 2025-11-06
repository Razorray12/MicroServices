package com.example.users

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class TokenCreationTest {
	@Test
	fun `token contains subject role and valid expiration`() {
		val secret = "secret123"
		val now = Instant.now()
		val token = createToken("123", "USER", secret, 60)
		val verifier = JWT.require(Algorithm.HMAC256(secret)).build()
		val decoded = verifier.verify(token)
		assertEquals("123", decoded.subject)
		assertEquals("USER", decoded.getClaim("role").asString())
		assertTrue(decoded.expiresAt.toInstant().isAfter(now))
	}

	@Test
	fun `token verification fails with another secret`() {
		val token = createToken("123", "USER", "s1", 60)
		assertThrows(Exception::class.java) {
			JWT.require(Algorithm.HMAC256("s2")).build().verify(token)
		}
	}
}


