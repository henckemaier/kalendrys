package com.kalendrys.app.ui.today

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.kalendrys.app.R
import com.kalendrys.app.domain.TodayOverview
import com.kalendrys.app.ui.components.SectionHeader
import com.kalendrys.app.ui.components.WeekdayName
import com.kalendrys.app.ui.components.weekdayName
import com.kalendrys.app.ui.theme.Spacing
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val SummaryHeaderPadding = PaddingValues(start = Spacing.s, end = Spacing.s, top = Spacing.s, bottom = Spacing.xs)

/**
 * Progresso do dia (só na data de hoje): os números do [overview], o mesmo retrato do widget Resumo,
 * sem recalcular na tela. Pendentes = [TodayOverview.pendingCount] (o número grande do widget), com o
 * mesmo desmembramento em tarefas e rotinas.
 */
@Composable
internal fun TodayProgress(overview: TodayOverview, done: Int, pending: Int) {
    val total = done + pending
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.today_progress_title), contentPadding = SummaryHeaderPadding)
        // Um nó só para o leitor de tela: feitos, barra e pendentes.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = Spacing.s, vertical = Spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.today_progress_done, done, total),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(Spacing.xs))
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else done.toFloat() / total },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.xs))
            val tasks = overview.pending.size
            val routines = overview.pendingRoutines.size
            Text(
                text = if (pending == 0) {
                    stringResource(R.string.today_progress_none_pending)
                } else {
                    listOfNotNull(
                        pluralStringResource(R.plurals.today_progress_pending, pending, pending),
                        // Como o widget: só desmembra quando há rotinas pendentes.
                        if (routines > 0) {
                            listOfNotNull(
                                tasks.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.widget_tasks_count, it, it) },
                                pluralStringResource(R.plurals.widget_routines_count, routines, routines),
                            ).joinToString(" · ")
                        } else {
                            null
                        },
                    ).joinToString(" · ")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Próximo item (só na data de hoje): o [TodayOverview.nextUp] do mesmo retrato do widget. */
@Composable
internal fun TodayNextUp(overview: TodayOverview) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.today_next_title), contentPadding = SummaryHeaderPadding)
        val next = overview.nextUp
        Text(
            text = if (next == null) {
                stringResource(R.string.today_next_none)
            } else {
                "${nextMoment(next.at, overview)} · ${next.title}"
            },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xs),
        )
    }
}

/** "14:00" hoje, "Amanhã, 08:00", ou "Qua, 09:00" depois (como o widget Resumo). */
@Composable
private fun nextMoment(at: LocalDateTime, overview: TodayOverview): String {
    val time = at.format(TIME)
    return when (at.toLocalDate()) {
        overview.date -> time
        overview.date.plusDays(1) -> stringResource(R.string.today_next_tomorrow_at, time)
        else -> "${weekdayName(at.dayOfWeek, WeekdayName.SHORT)}, $time"
    }
}
