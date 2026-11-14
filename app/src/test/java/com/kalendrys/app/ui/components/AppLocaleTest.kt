package com.kalendrys.app.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.domain.AppLocale
import com.kalendrys.app.domain.MoneyFormat
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * O app é só pt-BR (v1.5.1): com o aparelho em inglês, datas, meses e dias da semana continuam em
 * português, e a semana começa no domingo.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppLocaleTest {

    private lateinit var previous: Locale

    @Before
    fun setUp() {
        previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() = Locale.setDefault(previous)

    @Test
    fun `rotulos de data saem em portugues com o aparelho em ingles`() {
        val date = LocalDate.of(2026, 10, 24) // sábado
        assertEquals("Sábado, 24 de outubro", weekdayDateLabel(date))
        assertEquals("24 de outubro de 2026", longDateLabel(date))
        assertEquals("Março de 2026", monthYearLabel(YearMonth.of(2026, 3)))
    }

    @Test
    fun `semana comeca no domingo`() {
        assertEquals(DayOfWeek.SUNDAY, firstDayOfWeek())
        assertEquals(DayOfWeek.SUNDAY, AppLocale.FIRST_DAY_OF_WEEK)
    }

    @Test
    fun `tabela de dias da semana em portugues a partir do domingo`() {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val week = (0L until 7L).map { firstDayOfWeek().plus(it) }
        assertEquals(listOf("Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb"), week.map { resources.weekdayName(it, WeekdayName.SHORT) })
        assertEquals(listOf("D", "S", "T", "Q", "Q", "S", "S"), week.map { resources.weekdayName(it, WeekdayName.NARROW) })
        assertEquals(
            listOf("domingo", "segunda-feira", "terça-feira", "quarta-feira", "quinta-feira", "sexta-feira", "sábado"),
            week.map { resources.weekdayName(it, WeekdayName.FULL) },
        )
    }

    @Test
    fun `dinheiro usa o mesmo idioma do app`() {
        assertEquals("R$ 4,99", MoneyFormat.formatCents(499).replace(' ', ' '))
    }

    /**
     * Varredura simples dos fontes: na apresentação (ui/, widget/, reminder/, summary/) não pode
     * sobrar `Locale.getDefault()` nem `getDisplayName(`; o idioma vem sempre do [AppLocale].
     */
    @Test
    fun `nao sobra idioma do aparelho nas camadas de apresentacao`() {
        val root = File("src/main/java/com/kalendrys/app")
        assertTrue("fontes não encontrados em ${root.absolutePath}", root.isDirectory)
        val offenders = listOf("ui", "widget", "reminder", "summary")
            .flatMap { dir -> File(root, dir).walkTopDown().filter { it.extension == "kt" }.toList() }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val code = line.trim()
                    val isComment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
                    val bad = !isComment && (code.contains("Locale.getDefault(") || code.contains(".getDisplayName("))
                    if (bad) "${file.relativeTo(root)}:${index + 1}: $code" else null
                }
            }
        assertTrue("Use AppLocale em vez do idioma do aparelho:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
