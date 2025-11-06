package com.example.accounts

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PanGenerationTest {
	@Test
	fun `generates unique 16-digit pan starting with 4000 and retries on collision`() {
		var calls = 0
		val random = { n: Int ->
			calls += 1
			if (calls == 1) "111111111111" else "222222222222"
		}
		val used = mutableSetOf<String>("4000111111111111")
		val exists = { pan: String -> pan in used }
		val pan = generateUniquePan(random, exists)
		assertTrue(pan.startsWith("4000"))
		assertEquals(16, pan.length)
		assertEquals("4000222222222222", pan)
	}
}


