package com.kalendrys.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Complemento do [SourceClockReadsTest] (v1.10.0, ADR-0002 16.3; N1 da revisão do Esquadro), sem mexer
 * nele: leituras do relógio de parede que a varredura dele não procura, e um buraco da limpeza de
 * comentários dela.
 *
 * 1. Leituras sem nome de "now"/"current" que leem o relógio por dentro:
 *    - `Date()` sem argumento (`java.util.Date`, lê `System.currentTimeMillis()` no construtor);
 *    - `GregorianCalendar()` sem argumento (relógio e fuso do sistema);
 *    - `DateUtils.isToday(...)` e `DateUtils.getRelativeTimeSpanString(millis)` (comparam com o agora
 *      do sistema);
 *    - `Clock.System.now()` do kotlinx-datetime (não é dependência hoje; fica preso para quando for).
 *    `Date(millis)` e `GregorianCalendar(ano, mês, dia)` não leem o relógio e passam.
 * 2. A limpeza de comentários do [SourceClockReadsTest] não conhece strings: um texto com `/` seguido
 *    de `*` (um tipo MIME como "image/" + "*") abriria um "comentário" que esconde o código até o
 *    próximo fechamento. Hoje nenhum arquivo de produção tem isso; este teste prende a premissa.
 *
 * As exceções são as mesmas do [SourceClockReadsTest].
 */
class SourceClockReadsGapsTest {

    private val allowed = setOf("data/repository/Clock.kt", "data/local/AppDatabase.kt")

    private val hiddenReads = Regex(
        """(?<![\w.])(?:java\s*\.\s*util\s*\.\s*)?Date\s*\(\s*\)|""" +
            """(?<![\w.])(?:java\s*\.\s*util\s*\.\s*)?GregorianCalendar\s*\(\s*\)|""" +
            """\bDateUtils\s*\.\s*(?:isToday|getRelativeTimeSpanString)\b|""" +
            """\bClock\s*\.\s*System\s*\.\s*now\b""",
    )

    /** Uma string de uma linha com abertura ou fechamento de comentário de bloco dentro. */
    private val stringWithBlockCommentMark = Regex(""""(?:\\.|[^"\\\n])*(?:/\*|\*/)(?:\\.|[^"\\\n])*"""")

    private val sourceRoot: File = listOf(File("src/main/java/com/kalendrys/app"), File("app/src/main/java/com/kalendrys/app"))
        .first { it.isDirectory }

    private fun sources(): List<Pair<String, String>> =
        sourceRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath to it.readText() }
            .toList()

    /** A mesma limpeza do [SourceClockReadsTest]. */
    private fun withoutComments(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), " ").replace(Regex("""//[^\n]*"""), " ")

    @Test
    fun `nenhuma leitura escondida do relogio fora da peca e das excecoes`() {
        val files = sources()
        assertTrue("a varredura achou o código de produção", files.size > 50)

        val found = files.filter { (relative, _) -> relative !in allowed }.flatMap { (relative, text) ->
            withoutComments(text).lines().mapIndexedNotNull { index, line ->
                hiddenReads.find(line)?.let { "$relative:${index + 1}: ${it.value}" }
            }
        }

        assertEquals("leitura do relógio sem 'now' no nome (use o AppClock):\n" + found.joinToString("\n"), emptyList<String>(), found)
    }

    @Test
    fun `a varredura complementar pega o que deve e deixa o que nao le o relogio`() {
        listOf(
            "val d = Date()",
            "val d = java.util.Date( )",
            "val c = GregorianCalendar()",
            "if (DateUtils.isToday(millis)) x()",
            "val s = DateUtils.getRelativeTimeSpanString(millis)",
            "val i = Clock.System.now()",
        ).forEach { assertTrue(it, hiddenReads.containsMatchIn(it)) }
        listOf(
            "val d = Date(millis)",
            "val d = java.util.Date(task.createdAt)",
            "val c = GregorianCalendar(2030, 0, 7)",
            "val f = formatDate()",
            "val u = toDate()",
            "val n = clock.nowMillis()",
        ).forEach { assertFalse(it, hiddenReads.containsMatchIn(it)) }
    }

    @Test
    fun `nenhuma string de producao abre ou fecha comentario de bloco - a limpeza nao esconde codigo`() {
        val found = sources().flatMap { (relative, text) ->
            text.lines().mapIndexedNotNull { index, line ->
                stringWithBlockCommentMark.find(line)?.let { "$relative:${index + 1}: ${it.value}" }
            }
        }

        assertEquals(
            "string com marca de comentário de bloco: a limpeza do SourceClockReadsTest esconderia o código seguinte:\n" +
                found.joinToString("\n"),
            emptyList<String>(),
            found,
        )
    }

    @Test
    fun `demonstracao do buraco - uma string com abertura de comentario esconde a leitura`() {
        val source = "val mime = \"image/" + "*\"\nval t = System.currentTimeMillis()\n/* fim */\n"
        assertTrue(stringWithBlockCommentMark.containsMatchIn(source))
        assertFalse(
            "a limpeza sem strings apaga a leitura junto com o falso comentário",
            withoutComments(source).contains("System.currentTimeMillis"),
        )
    }
}
