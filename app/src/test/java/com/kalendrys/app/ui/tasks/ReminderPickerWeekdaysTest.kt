package com.kalendrys.app.ui.tasks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.domain.RecurrenceUnit
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.ui.components.assertWeekdayCellsFit
import com.kalendrys.app.ui.components.weekdayPillWidthsPx
import com.kalendrys.app.ui.theme.KalendrysTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Dias da recorrência personalizada no seletor de lembrete (v1.7.1, E2): 360dp e fonte 2.0 sem corte. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-port")
// Texto medido com fonte de verdade (no modo padrão do Robolectric a largura do texto é 0).
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReminderPickerWeekdaysTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var pills: List<Int>

    @Test
    fun `dias do lembrete personalizado em 360dp e fonte 2_0 cabem sem corte`() {
        val timing = TaskTiming(
            date = LocalDate.now().plusDays(1),
            repeat = ReminderRepeat.CUSTOM,
            intervalUnit = RecurrenceUnit.DAYS,
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                KalendrysTheme {
                    pills = weekdayPillWidthsPx()
                    // Margem do editor de tarefa (16dp de cada lado).
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        TaskTimingField(timing = timing, onTimingChange = {})
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.assertWeekdayCellsFit(pills, compose.density.density)
    }
}
