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

    /** Centavos → texto editável em reais com vírgula decimal ("4,99"), para preencher o campo ao editar. */
    fun centsToInput(cents: Long): String {
        val safe = cents.coerceAtLeast(0)
        return "${safe / 100},${(safe % 100).toString().padStart(2, '0')}"
    }
}
