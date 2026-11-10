package com.kalendrys.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyFormatTest {

    @Test
    fun `reais com virgula vira centavos`() {
        assertEquals(499L, MoneyFormat.parseReaisToCents("4,99"))
    }

    @Test
    fun `reais com ponto vira centavos`() {
        assertEquals(499L, MoneyFormat.parseReaisToCents("4.99"))
    }

    @Test
    fun `inteiro sem separador vira reais cheios`() {
        assertEquals(1000L, MoneyFormat.parseReaisToCents("10"))
    }

    @Test
    fun `ignora simbolo de moeda e espacos`() {
        assertEquals(1250L, MoneyFormat.parseReaisToCents("R$ 12,50"))
    }

    @Test
    fun `o ultimo separador e o decimal e o outro e milhar`() {
        // 1.234,56 (padrão BR) e 1,234.56 (padrão US) resultam no mesmo valor.
        assertEquals(123456L, MoneyFormat.parseReaisToCents("1.234,56"))
        assertEquals(123456L, MoneyFormat.parseReaisToCents("1,234.56"))
    }

    @Test
    fun `vazio ou invalido vira null`() {
        assertNull(MoneyFormat.parseReaisToCents(""))
        assertNull(MoneyFormat.parseReaisToCents("   "))
        assertNull(MoneyFormat.parseReaisToCents("abc"))
    }

    @Test
    fun `negativo vira null`() {
        assertNull(MoneyFormat.parseReaisToCents("-5,00"))
    }

    @Test
    fun `centavos para texto editavel usa virgula e dois digitos`() {
        assertEquals("4,99", MoneyFormat.centsToInput(499))
        assertEquals("10,00", MoneyFormat.centsToInput(1000))
        assertEquals("0,05", MoneyFormat.centsToInput(5))
    }

    @Test
    fun `round-trip texto para centavos para texto`() {
        val cents = MoneyFormat.parseReaisToCents("7,30")
        assertEquals(730L, cents)
        assertEquals("7,30", MoneyFormat.centsToInput(cents!!))
    }
}
