package com.kalendrys.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regra do relógio único presa por teste (v1.10.0, ADR-0002 16.3, A4 do Esquadro): fora do
 * [com.kalendrys.app.data.repository.AppClock] (Clock.kt) e das exceções abaixo, o código de produção
 * não lê hora, data nem fuso direto do sistema. Quem precisa de agora, hoje ou fuso recebe o relógio do
 * app por parâmetro (ou, numa tela, pelo `LocalAppClock`). Uma leitura nova solta derruba este teste.
 *
 * Varre o texto de `app/src/main` (Kotlin e Java), sem comentários de linha e de bloco, atrás de:
 * `LocalDate.now`, `LocalDateTime.now`, `LocalTime.now`, `ZonedDateTime.now`, `OffsetDateTime.now`,
 * `Instant.now`, `YearMonth.now`, `System.currentTimeMillis`, `ZoneId.systemDefault`,
 * `TimeZone.getDefault`, `Calendar.getInstance`, `Clock.systemDefaultZone` e `Clock.systemUTC`.
 * O tempo monotônico (`SystemClock.uptimeMillis`, `System.nanoTime`) não é relógio de parede e fica de
 * fora.
 */
class SourceClockReadsTest {

    /** Exceções aceitas, com o motivo (caminho relativo a `app/src/main/java/com/kalendrys/app/`). */
    private val allowed = mapOf(
        "data/repository/Clock.kt" to "a própria peça do relógio",
        // A Migration já publicada roda uma vez, estática, fora de qualquer injeção; não se mexe nela.
        "data/local/AppDatabase.kt" to "Migration já publicada",
    )

    private val forbidden = Regex(
        """\b(?:(?:LocalDate|LocalDateTime|LocalTime|ZonedDateTime|OffsetDateTime|Instant|YearMonth)\s*\.\s*now|""" +
            """System\s*\.\s*currentTimeMillis|ZoneId\s*\.\s*systemDefault|TimeZone\s*\.\s*getDefault|""" +
            """Calendar\s*\.\s*getInstance|Clock\s*\.\s*system(?:DefaultZone|UTC))\b""",
    )

    private val sourceRoot: File = listOf(File("src/main/java/com/kalendrys/app"), File("app/src/main/java/com/kalendrys/app"))
        .first { it.isDirectory }

    private fun withoutComments(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), " ").replace(Regex("""//[^\n]*"""), " ")

    @Test
    fun `nenhuma leitura direta do relogio fora da peca e das excecoes`() {
        val files = sourceRoot.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
        assertTrue("a varredura achou o código de produção", files.size > 50)

        val found = files.flatMap { file ->
            val relative = file.relativeTo(sourceRoot).invariantSeparatorsPath
            if (relative in allowed) return@flatMap emptyList()
            withoutComments(file.readText()).lines().mapIndexedNotNull { index, line ->
                forbidden.find(line)?.let { "$relative:${index + 1}: ${it.value}" }
            }
        }

        assertEquals("leitura direta do relógio (use o AppClock do app):\n" + found.joinToString("\n"), emptyList<String>(), found)
    }

    @Test
    fun `as excecoes ainda existem e ainda leem o relogio`() {
        // Exceção que deixou de ser necessária sai da lista: a regra não fica com buraco esquecido.
        allowed.keys.forEach { relative ->
            val file = File(sourceRoot, relative)
            assertTrue("$relative existe", file.isFile)
            assertTrue("$relative ainda lê o relógio", forbidden.containsMatchIn(withoutComments(file.readText())))
        }
    }

    @Test
    fun `a varredura pega o que deve pegar`() {
        listOf(
            "val d = LocalDate.now()",
            "val t = System.currentTimeMillis()",
            "val z = ZoneId.systemDefault()",
            "val i = Instant . now()",
            "val m = YearMonth.now(zone)",
        ).forEach { assertTrue(it, forbidden.containsMatchIn(it)) }
        listOf(
            "val t = SystemClock.uptimeMillis()",
            "val d = clock.today()",
            "val n = System.nanoTime()",
        ).forEach { assertTrue(it, !forbidden.containsMatchIn(it)) }
        assertEquals("", withoutComments("// LocalDate.now()").trim())
    }
}
