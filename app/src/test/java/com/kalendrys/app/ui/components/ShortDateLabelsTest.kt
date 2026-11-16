package com.kalendrys.app.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.widget.KalendrysWidgets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * L5 (ADR-0002, seção 3, decisão 6): os rótulos abreviados dos widgets e do seletor de lembrete saem
 * das tabelas pt-BR do strings.xml, com o mesmo texto qualquer que seja o idioma do aparelho. Os
 * textos esperados são literais: não dependem de formatador nem de dados de idioma da versão do
 * Android (o "EEE"/"MMM" de antes dava "qua."/"qua" e "nov."/"nov" conforme a versão).
 */
@RunWith(AndroidJUnit4::class)
// Três versões do Android (8.0, 11 e 14) e quatro idiomas de aparelho: o mesmo texto em todas.
@Config(sdk = [26, 30, 34])
class ShortDateLabelsTest {

    private lateinit var previous: Locale
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val res get() = context.resources

    // Quarta-feira.
    private val wednesday = LocalDate.of(2026, Month.NOVEMBER, 18)

    @Before
    fun setUp() {
        previous = Locale.getDefault()
    }

    @After
    fun tearDown() = Locale.setDefault(previous)

    private fun forEachDeviceLocale(block: () -> Unit) {
        for (locale in listOf(Locale.US, Locale.GERMANY, Locale.JAPAN, Locale.forLanguageTag("pt-BR"))) {
            Locale.setDefault(locale)
            block()
        }
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `widget de tarefas mostra dia e mes abreviados da tabela`() = forEachDeviceLocale {
        assertEquals("Qua, 18 nov", res.weekdayDayMonthLabel(wednesday))
        assertEquals("Dom, 1 mar", res.weekdayDayMonthLabel(LocalDate.of(2026, Month.MARCH, 1)))
        assertEquals("Sáb, 5 set", res.weekdayDayMonthLabel(LocalDate.of(2026, Month.SEPTEMBER, 5)))
    }

    @Test
    @Config(qualifiers = "de-rDE")
    fun `seletor de lembrete mostra dia da semana e data numerica`() = forEachDeviceLocale {
        assertEquals("Qua, 18/11", res.weekdayNumericDateLabel(wednesday))
        assertEquals("Ter, 03/02", res.weekdayNumericDateLabel(LocalDate.of(2026, Month.FEBRUARY, 3)))
    }

    @Test
    @Config(qualifiers = "ja-rJP")
    fun `widget de resumo mostra o dia da semana do lembrete depois de amanha`() = forEachDeviceLocale {
        val today = LocalDate.of(2026, Month.NOVEMBER, 16)
        assertEquals("Qua, 09:05", res.weekdayTimeLabel(wednesday, LocalTime.of(9, 5)))
        assertEquals(
            "Qua, 09:05",
            KalendrysWidgets.reminderMoment(context, LocalDateTime.of(wednesday, LocalTime.of(9, 5)), today),
        )
        assertEquals("Amanhã, 07:30", KalendrysWidgets.reminderMoment(context, LocalDateTime.of(2026, 11, 17, 7, 30), today))
        assertEquals("18:00", KalendrysWidgets.reminderMoment(context, LocalDateTime.of(today, LocalTime.of(18, 0)), today))
    }

    @Test
    fun `tabela de meses tem os doze em pt-BR`() {
        assertEquals(
            listOf("jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez"),
            Month.entries.map { res.monthShortName(it) },
        )
    }
}
