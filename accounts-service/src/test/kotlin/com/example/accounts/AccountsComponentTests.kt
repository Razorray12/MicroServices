package com.example.accounts

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AccountsComponentTests {

    @Test
    fun `isValidCurrencyCode validates 3-letter alphabetic codes`() {
        // valid cases
        assertTrue(isValidCurrencyCode("USD"))
        assertTrue(isValidCurrencyCode("eur"))
        assertTrue(isValidCurrencyCode(" rub "))

        // invalid: не 3 символа
        assertFalse(isValidCurrencyCode("US"))
        assertFalse(isValidCurrencyCode("EURO"))
        // invalid: содержит цифры
        assertFalse(isValidCurrencyCode("U5D"))
        // invalid: содержит спецсимволы
        assertFalse(isValidCurrencyCode("U$D"))
        // invalid: пустая строка / пробелы
        assertFalse(isValidCurrencyCode("   "))
    }

    @Test
    fun `generateUniquePan returns 16 digits starting with 4000`() {
        // given: генератор цифр, который всегда возвращает одну и ту же последовательность
        val randomDigits: (Int) -> String = { n ->
            // просто повторяем "123456" и обрезаем до нужной длины
            "123456123456".take(n)
        }

        // existsPan всегда говорит, что PAN ещё не существует
        val existsPan: (String) -> Boolean = { false }

        // when
        val pan = generateUniquePan(randomDigits, existsPan)

        // then
        assertEquals(16, pan.length)
        assertTrue(pan.startsWith("4000"))
        assertTrue(pan.all { it.isDigit() })
    }

    @Test
    fun `generateUniquePan retries while PAN already exists`() {
        // given: имитируем коллизию первого сгенерированного PAN
        val firstDigits = "111111111111"
        val secondDigits = "222222222222"

        var callCount = 0
        val randomDigits: (Int) -> String = { n ->
            callCount++
            when (callCount) {
                1 -> firstDigits.take(n)    // первый PAN → коллизия
                else -> secondDigits.take(n) // второй PAN → свободен
            }
        }

        val usedPans = mutableSetOf("4000$firstDigits") // первый уже "занят"

        val existsPan: (String) -> Boolean = { candidate ->
            candidate in usedPans
        }

        // when
        val pan = generateUniquePan(randomDigits, existsPan)

        // then: функция должна была сгенерировать второй PAN
        assertEquals("4000$secondDigits", pan)
        // и как минимум дважды дернуть генератор
        assertTrue(callCount >= 2)
    }
}

