package com.example.users

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NormalizeEmailTest {
	@Test
	fun `trims whitespace and lowercases email`() {
		assertEquals("user@example.com", normalizeEmail("  User@Example.com  "))
	}

	@Test
	fun `handles already normalized`() {
		assertEquals("a@b.c", normalizeEmail("a@b.c"))
	}
}


