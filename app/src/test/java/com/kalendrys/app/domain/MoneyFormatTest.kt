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

    @Test
    fun `filtro mantem digitos e um separador`() {
        assertEquals("4,99", MoneyFormat.filterPriceInput("4,99"))
        assertEquals("1234", MoneyFormat.filterPriceInput("1234"))
    }

    @Test
    fun `filtro descarta letras e simbolos`() {
        assertEquals("12", MoneyFormat.filterPriceInput("R$ 12 reais"))
        assertEquals("4,99", MoneyFormat.filterPriceInput("abc4,99x"))
    }

    @Test
    fun `filtro mantem so o ultimo separador descartando milhar`() {
        // Último separador = decimal (consistente com o parse); os anteriores (milhar) caem.
        assertEquals("1000,50", MoneyFormat.filterPriceInput("1.000,50"))
        assertEquals("49,9", MoneyFormat.filterPriceInput("4,9,9"))
        // Separador no fim (digitação em progresso) continua válido.
        assertEquals("5,", MoneyFormat.filterPriceInput("5,"))
    }

    @Test
    fun `preco BR com milhar parseia certo apos o filtro`() {
        // "1.000,50" -> filtro "1000,50" -> R$ 1000,50 = 100050 centavos (antes lia R$ 1,00).
        assertEquals(100050L, MoneyFormat.parseReaisToCents(MoneyFormat.filterPriceInput("1.000,50")))
    }

    @Test
    fun `texto filtrado com digito sempre parseia`() {
        // Pós-filtro, qualquer texto com dígito tem preço (nunca vira null por engano).
        assertEquals(500L, MoneyFormat.parseReaisToCents(MoneyFormat.filterPriceInput("5,")))
        assertEquals(50L, MoneyFormat.parseReaisToCents(MoneyFormat.filterPriceInput(",5")))
    }
}
