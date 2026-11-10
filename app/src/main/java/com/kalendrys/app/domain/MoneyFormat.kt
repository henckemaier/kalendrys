package com.kalendrys.app.domain

import java.text.NumberFormat
import java.util.Locale

/**
 * Dinheiro em centavos (Long) — a fonte da verdade do preço — e a ponte com o texto da UI. O preço é
 * digitado em reais e guardado em centavos; o total é exibido na moeda do Brasil (BRL).
 */
object MoneyFormat {

    private val BRL = Locale("pt", "BR")

    /** Centavos → moeda do Brasil, ex.: 499 → "R$ 4,99". */
    fun formatCents(cents: Long): String =
        NumberFormat.getCurrencyInstance(BRL).format(cents / 100.0)

    /**
     * Texto em reais digitado pelo usuário → centavos. Aceita vírgula ou ponto como separador
     * decimal (o último separador vale como decimal; o outro é tratado como milhar) e ignora símbolo
     * de moeda e espaços. Vazio ou inválido → `null` (o preço é opcional). Negativo → `null`.
     */
    fun parseReaisToCents(text: String): Long? {
        // Preço não é negativo: um sinal de menos rejeita a entrada (em vez de ser silenciosamente ignorado).
        if (text.contains('-')) return null
        val filtered = text.filter { it.isDigit() || it == ',' || it == '.' }
        if (filtered.isEmpty()) return null
        val decimalPos = maxOf(filtered.lastIndexOf(','), filtered.lastIndexOf('.'))
        val normalized = if (decimalPos < 0) {
            filtered
        } else {
            val intPart = filtered.substring(0, decimalPos).filter { it.isDigit() }
            val fracPart = filtered.substring(decimalPos + 1).filter { it.isDigit() }
            "${intPart.ifEmpty { "0" }}.$fracPart"
        }
        val reais = normalized.toDoubleOrNull() ?: return null
        if (reais < 0) return null
        return Math.round(reais * 100)
    }

    /**
     * Filtra a digitação de um preço: mantém dígitos e NO MÁXIMO um separador decimal — o ÚLTIMO
     * digitado (vírgula ou ponto), descartando os anteriores (milhar) e qualquer outro caractere.
     * Manter o último casa com [parseReaisToCents] (último separador = decimal): assim "1.000,50"
     * vira "1000,50" (R$ 1000,50), eliminando a ambiguidade de milhar BR. Um separador no fim
     * (digitação em progresso, ex. "5,") continua válido.
     */
    fun filterPriceInput(text: String): String {
        val chars = text.filter { it.isDigit() || it == ',' || it == '.' }
        val lastSeparator = maxOf(chars.lastIndexOf(','), chars.lastIndexOf('.'))
        if (lastSeparator < 0) return chars
        val out = StringBuilder()
        for (i in chars.indices) {
            val c = chars[i]
            // Dígitos entram; dos separadores, só o último sobrevive (os anteriores eram milhar).
            if (c.isDigit() || i == lastSeparator) out.append(c)
        }
        return out.toString()
    }

    /** Centavos → texto editável em reais com vírgula decimal ("4,99"), para preencher o campo ao editar. */
    fun centsToInput(cents: Long): String {
        val safe = cents.coerceAtLeast(0)
        return "${safe / 100},${(safe % 100).toString().padStart(2, '0')}"
    }
}
