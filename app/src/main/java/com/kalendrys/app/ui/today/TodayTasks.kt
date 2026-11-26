package com.kalendrys.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kalendrys.app.R
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.DayTimeline
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.ShoppingProgress
import com.kalendrys.app.ui.components.SectionHeader
import com.kalendrys.app.ui.routines.minuteOfDayLabel
import com.kalendrys.app.ui.routines.routineTone
import com.kalendrys.app.ui.shopping.ShoppingProgressTag
import com.kalendrys.app.ui.theme.Spacing
import com.kalendrys.app.ui.today.TodayViewModel.DayTask
import com.kalendrys.app.ui.today.TodayViewModel.UiState

/** Seções recolhíveis da Hoje; aberta/fechada é estado de tela (sobrevive a girar). */
internal class SectionsState(
    val noDateExpanded: Boolean,
    val doneExpanded: Boolean,
    val onToggleNoDate: () -> Unit,
    val onToggleDone: () -> Unit,
)

/**
 * As tarefas fora da trilha, na ordem do 14.4 (depois da trilha): Atrasadas, Hoje (ou "Tarefas do
 * dia" em outro dia), Sem data (recolhida) e Concluídas (recolhida). Seção vazia não aparece.
 */
internal fun LazyListScope.todaySections(
    state: UiState,
    sections: SectionsState,
    onOpenTask: (Long) -> Unit,
    onToggleTask: (Task, Boolean) -> Unit,
) {
    taskSection("overdue", R.string.today_section_overdue, state.overdueTasks, state, onOpenTask, onToggleTask)
    taskSection(
        "day",
        if (state.isShowingToday) R.string.today_section_today else R.string.today_section_day,
        state.dayTasks,
        state,
        onOpenTask,
        onToggleTask,
    )
    taskSection(
        "no_date",
        R.string.today_section_no_date,
        state.noDateTasks,
        state,
        onOpenTask,
        onToggleTask,
        expanded = sections.noDateExpanded,
        onToggleExpanded = sections.onToggleNoDate,
    )
    taskSection(
        "done",
        R.string.today_section_done,
        state.completedTasks,
        state,
        onOpenTask,
        onToggleTask,
        expanded = sections.doneExpanded,
        onToggleExpanded = sections.onToggleDone,
    )
}

/** Uma seção: cabeçalho (título e contagem) e as linhas; com [onToggleExpanded], recolhível. */
private fun LazyListScope.taskSection(
    key: String,
    titleRes: Int,
    tasks: List<DayTask>,
    state: UiState,
    onOpenTask: (Long) -> Unit,
    onToggleTask: (Task, Boolean) -> Unit,
    expanded: Boolean = true,
    onToggleExpanded: (() -> Unit)? = null,
) {
    if (tasks.isEmpty()) return
    item(key = "section_$key", contentType = "section_header") {
        TaskSectionHeader(
            title = stringResource(titleRes),
            count = tasks.size,
            expanded = expanded,
            onToggleExpanded = onToggleExpanded,
        )
    }
    if (!expanded) return
    items(items = tasks, key = { "${key}_${it.task.id}" }, contentType = { "section_task" }) { dayTask ->
        DayTaskRow(
            dayTask = dayTask,
            listProgress = state.progressFor(dayTask.task.shoppingListId),
            canComplete = state.canComplete,
            onOpen = { onOpenTask(dayTask.task.id) },
            onToggle = { onToggleTask(dayTask.task, dayTask.done) },
        )
    }
}

/**
 * Cabeçalho de seção (marcado como título). Recolhível ([onToggleExpanded]): o estado
 * aberta/recolhida e a ação de abrir/recolher ficam no mesmo nó, no padrão do "Marcados (n)" das
 * listas (12.4).
 */
@Composable
private fun TaskSectionHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggleExpanded: (() -> Unit)?,
) {
    if (onToggleExpanded == null) {
        SectionHeader(title = title, count = count, contentPadding = SectionHeaderPadding)
        return
    }
    val stateLabel = stringResource(if (expanded) R.string.today_section_expanded else R.string.today_section_collapsed)
    val actionLabel = stringResource(if (expanded) R.string.today_section_collapse else R.string.today_section_expand, title)
    SectionHeader(
        title = title,
        count = count,
        contentPadding = SectionHeaderPadding,
        onClick = onToggleExpanded,
        onClickLabel = actionLabel,
        trailing = {
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics { stateDescription = stateLabel },
    )
}

private val SectionHeaderPadding = PaddingValues(start = Spacing.s, end = Spacing.s, top = Spacing.m, bottom = Spacing.xs)

/**
 * Uma tarefa pontual na trilha. Destaque OPOSTO ao do bloco de rotina (pílula sólida + círculo):
 * cartão de borda tracejada + marcador losango, para ler como um evento único que não se repete.
 */
@Composable
internal fun TaskRow(
    item: DayTimeline.TaskItem,
    listProgress: ShoppingProgress?,
    canComplete: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.secondary
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val task = item.task
    val timeColumnWidth = timeColumnWidth()
    val toggleLabel = taskToggleLabel(item.done, task.title)
    val time = minuteOfDayLabel(item.startMinute)
    // Leitura (v1.10.0, 16.10.8 item 6): tipo, hora, título; o estado vem do stateDescription e o
    // progresso da lista, se houver, do próprio selo. O título visível fica fora para não ler duas vezes.
    // Dentro de um bloco (item 7): "..., dentro de <rotina>".
    val container = item.container
    val description = if (container == null) {
        stringResource(R.string.today_task_row_description, time, task.title)
    } else {
        stringResource(R.string.today_task_row_description_inside, time, task.title, container.title)
    }
    // A faixa do bloco que contém a tarefa passa por trás do losango, sem recuo a mais na linha.
    val band = container?.let { routineTone(RoutineColor.fromKey(it.color)).fill }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(MaterialTheme.shapes.medium)
            .timelineRowSemantics(
                rowStateLabel(item.done),
                if (canComplete) listOf(rowAction(toggleLabel, onToggle)) else emptyList(),
            )
            .semantics { contentDescription = description }
            .clickable(onClick = onOpen),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(timeColumnWidth)
                .padding(vertical = RowPadding + 2.dp)
                .clearAndSetSemantics {},
        ) {
            TimeLabel(time)
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(TrackWidth)
                .fillMaxHeight()
                // Mesma trilha dos blocos: a linha (cheia/tracejada) continua pela tarefa, sem duplicar —
                // o losango só abre a folga no meio. Vazia (Link.NONE) quando a tarefa fica solta.
                .drawBehind {
                    if (band != null) {
                        val w = PillWidth.toPx()
                        drawRect(band, topLeft = Offset((size.width - w) / 2f, 0f), size = Size(w, size.height))
                    } else {
                        drawLinks(item.linkAbove, item.linkBelow, DiamondTrackGap.toPx(), lineColor)
                    }
                },
        ) {
            Diamond(filled = item.done, color = accent)
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = RowPadding)
                .dashedBorder(accent.copy(alpha = 0.7f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (item.done) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics {},
            )
            if (listProgress != null) {
                ShoppingProgressTag(progress = listProgress)
            }
        }
        TaskCheck(done = item.done, accent = accent, canComplete = canComplete, onToggle = onToggle)
    }
}

/** Uma tarefa de seção: losango, título e o círculo de concluir/desmarcar. */
@Composable
internal fun DayTaskRow(
    dayTask: DayTask,
    listProgress: ShoppingProgress?,
    canComplete: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.secondary
    val task = dayTask.task
    val toggleLabel = taskToggleLabel(dayTask.done, task.title)
    // Sem horário: tipo e título (o estado vem do stateDescription).
    val description = stringResource(R.string.today_task_row_description_untimed, task.title)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .timelineRowSemantics(
                rowStateLabel(dayTask.done),
                if (canComplete) listOf(rowAction(toggleLabel, onToggle)) else emptyList(),
            )
            .semantics { contentDescription = description }
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Diamond(filled = dayTask.done, color = accent)
        Text(
            text = task.title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (dayTask.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (dayTask.done) TextDecoration.LineThrough else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
                .clearAndSetSemantics {},
        )
        if (listProgress != null) {
            ShoppingProgressTag(progress = listProgress, modifier = Modifier.padding(start = 8.dp))
        }
        TaskCheck(done = dayTask.done, accent = accent, canComplete = canComplete, onToggle = onToggle)
    }
}

@Composable
private fun taskToggleLabel(done: Boolean, title: String): String =
    stringResource(if (done) R.string.task_uncomplete else R.string.task_complete, title)

/** Círculo de concluir: só o alvo de toque; a ação acessível é do nó da linha ([timelineRowSemantics]). */
@Composable
private fun TaskCheck(
    done: Boolean,
    accent: Color,
    canComplete: Boolean,
    onToggle: () -> Unit,
) {
    IconButton(
        onClick = onToggle,
        enabled = canComplete,
        modifier = Modifier.clearAndSetSemantics {},
    ) {
        Icon(
            imageVector = if (done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = null,
            tint = if (canComplete) accent else accent.copy(alpha = 0.38f),
        )
    }
}

/** Marcador em losango (quadrado girado 45°): cheio quando concluído, só contorno quando pendente. */
@Composable
private fun Diamond(filled: Boolean, color: Color) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .rotate(45f)
            .then(
                if (filled) {
                    Modifier.background(color, MaterialTheme.shapes.extraSmall)
                } else {
                    Modifier.border(2.dp, color, MaterialTheme.shapes.extraSmall)
                },
            ),
    )
}

/** Borda tracejada arredondada, o oposto visual da pílula sólida da rotina. */
private fun Modifier.dashedBorder(color: Color): Modifier = drawBehind {
    val radius = 12.dp.toPx()
    val dash = 4.dp.toPx()
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
    )
}
