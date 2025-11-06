package com.example.users

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PasswordHashTest {
	@Test
	fun `hash and verify positive and negative`() {
		val hash = hashPassword("pass")
		assertNotEquals("pass", hash)
		assertTrue(verifyPassword("pass", hash))
		assertFalse(verifyPassword("wrong", hash))
	}
}


