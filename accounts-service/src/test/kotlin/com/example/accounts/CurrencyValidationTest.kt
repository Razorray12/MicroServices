package com.example.accounts

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CurrencyValidationTest {
	@Test
	fun `accepts three-letter codes regardless of case and spaces`() {
		assertTrue(isValidCurrencyCode("USD"))
		assertTrue(isValidCurrencyCode("usd"))
		assertTrue(isValidCurrencyCode("  Eur  "))
	}

	@Test
	fun `rejects non-three-letter or non-alpha`() {
		assertFalse(isValidCurrencyCode("US"))
		assertFalse(isValidCurrencyCode("USDT"))
		assertFalse(isValidCurrencyCode("U\$D"))
		assertFalse(isValidCurrencyCode(""))
	}
}


