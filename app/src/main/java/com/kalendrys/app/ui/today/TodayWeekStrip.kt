package com.kalendrys.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kalendrys.app.R
import com.kalendrys.app.ui.components.Dot
import com.kalendrys.app.ui.components.HorizontalSwipeContent
import com.kalendrys.app.ui.components.WeekdayName
import com.kalendrys.app.ui.components.weekdayDateLabel
import com.kalendrys.app.ui.components.weekdayName
import com.kalendrys.app.ui.routines.routineTone
import com.kalendrys.app.ui.today.TodayViewModel.UiState
import com.kalendrys.app.ui.today.TodayViewModel.WeekDay
import java.time.LocalDate

/**
 * Faixa da semana da Hoje: sete dias (aba, com o dia selecionado), pontos das rotinas e deslize para
 * trocar de semana, com "Semana anterior"/"Próxima semana" como ações de acessibilidade equivalentes.
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        ) {
            for (offset in 0L until 7L) {
                val date = weekStart.plusDays(offset)
                WeekDayCell(
                    date = date,
                    // Durante a animação a semana que sai não tem mais dados: aparece sem pontos.
                    day = byDate[date],
                    isToday = date == state.today,
                    isSelected = date == state.selectedDate,
                    onClick = { onSelect(date) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun WeekDayCell(
    date: LocalDate,
    day: WeekDay?,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
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
            text = weekdayName(date.dayOfWeek, WeekdayName.SHORT),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics {},
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
