package com.kalendrys.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kalendrys.app.R
import com.kalendrys.app.ui.components.Dot
import com.kalendrys.app.ui.components.HorizontalSwipeContent
import com.kalendrys.app.ui.components.WeekdayName
import com.kalendrys.app.ui.components.firstDayOfWeek
import com.kalendrys.app.ui.components.weekdayDateLabel
import com.kalendrys.app.ui.components.weekdayName
import com.kalendrys.app.ui.routines.routineTone
import com.kalendrys.app.ui.today.TodayViewModel.UiState
import com.kalendrys.app.ui.today.TodayViewModel.WeekDay
import java.time.LocalDate

/**
 * Faixa da semana da Hoje: sete dias (aba, com o dia selecionado), pontos das rotinas e deslize para
 * trocar de semana, com "Semana anterior"/"Próxima semana" como ações de acessibilidade equivalentes.
 *
 * Nome do dia SEMPRE numa linha (v1.9.0, N-1): três letras ("Dom") se as sete couberem na largura
 * da célula; senão a faixa INTEIRA passa a uma letra ("D"), escolhida por medida e igual para os sete
 * dias. O leitor de tela ouve o nome completo (descrição da célula).
 */
@Composable
internal fun TodayWeekStrip(
    state: UiState,
    onSelect: (LocalDate) -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
) {
    val byDate = remember(state.week) { state.week.associateBy { it.date } }
    val previousLabel = stringResource(R.string.routine_week_previous)
    val nextLabel = stringResource(R.string.routine_week_next)
    HorizontalSwipeContent(
        target = state.week.first().date,
        onPrevious = onPreviousWeek,
        onNext = onNextWeek,
        label = "week",
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(previousLabel) { onPreviousWeek(); true },
                    CustomAccessibilityAction(nextLabel) { onNextWeek(); true },
                )
            },
    ) { weekStart ->
        // Na lista da Hoje a margem lateral já vem do contentPadding.
        BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            val nameStyle = weekdayNameStyle(maxWidth / 7)
            Row(modifier = Modifier.fillMaxWidth()) {
                for (offset in 0L until 7L) {
                    val date = weekStart.plusDays(offset)
                    WeekDayCell(
                        date = date,
                        // Durante a animação a semana que sai não tem mais dados: aparece sem pontos.
                        day = byDate[date],
                        isToday = date == state.today,
                        isSelected = date == state.selectedDate,
                        onClick = { onSelect(date) },
                        nameStyle = nameStyle,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Marca de teste do nome do dia (medida da N-1). */
internal const val WEEKDAY_NAME_TAG = "today-week-name"

/** Estilo do nome do dia na faixa. */
@Composable
internal fun weekdayLabelStyle(): TextStyle = MaterialTheme.typography.labelMedium

/**
 * Três letras se o nome curto MAIS LARGO dos sete couber numa linha em [cellWidth]; senão uma letra
 * para todos (N-1). Medido com a fonte em vigor, então a fonte grande também cai para uma letra.
 */
@Composable
internal fun weekdayNameStyle(cellWidth: Dp): WeekdayName {
    val firstDay = firstDayOfWeek()
    val shortNames = (0L until 7L).map { weekdayName(firstDay.plus(it), WeekdayName.SHORT) }
    val style = weekdayLabelStyle()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widest = remember(shortNames, style, density) {
        shortNames.maxOf { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width }
    }
    val cellPx = with(density) { cellWidth.roundToPx() }
    return if (widest <= cellPx) WeekdayName.SHORT else WeekdayName.NARROW
}

@Composable
private fun WeekDayCell(
    date: LocalDate,
    day: WeekDay?,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    nameStyle: WeekdayName,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val description = if (day != null && day.total > 0) {
        stringResource(R.string.routine_day_a11y, weekdayDateLabel(date), day.done, day.total)
    } else {
        weekdayDateLabel(date)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .selectable(selected = isSelected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = weekdayName(date.dayOfWeek, nameStyle),
            style = weekdayLabelStyle(),
            color = colors.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            // Só a marca de teste: o leitor de tela ouve o nome completo pela célula.
            modifier = Modifier.clearAndSetSemantics { testTag = WEEKDAY_NAME_TAG },
        )
        Spacer(Modifier.height(4.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .clearAndSetSemantics {}
                .size(36.dp)
                .clip(CircleShape)
                .background(if (isSelected) colors.primary else Color.Transparent)
                .then(if (isToday && !isSelected) Modifier.border(1.dp, colors.primary, CircleShape) else Modifier),
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    isSelected -> colors.onPrimary
                    isToday -> colors.primary
                    else -> colors.onSurface
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier
                .clearAndSetSemantics {}
                .height(6.dp),
        ) {
            day?.dots?.forEach { dot ->
                Dot(color = routineTone(dot.color).accent, size = 6.dp, filled = dot.done)
            }
        }
    }
}
