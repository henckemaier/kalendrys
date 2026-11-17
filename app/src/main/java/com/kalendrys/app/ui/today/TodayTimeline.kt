@file:OptIn(ExperimentalFoundationApi::class)

package com.kalendrys.app.ui.today

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kalendrys.app.R
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.DayTimeline
import com.kalendrys.app.domain.DayTimeline.Link
import com.kalendrys.app.domain.DurationFormat
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineIcon
import com.kalendrys.app.domain.ShoppingProgress
import com.kalendrys.app.ui.components.rememberCurrentMinute
import com.kalendrys.app.ui.components.rememberTimeColumnWidth
import com.kalendrys.app.ui.routines.imageVector
import com.kalendrys.app.ui.routines.minuteOfDayLabel
import com.kalendrys.app.ui.routines.routineTimeRange
import com.kalendrys.app.ui.routines.routineTone
import com.kalendrys.app.ui.shopping.ShoppingProgressTag
import com.kalendrys.app.ui.today.TodayViewModel.UiState
import java.time.LocalTime

internal val RowPadding = 6.dp
// Largura MÍNIMA da coluna de horas; com fonte grande ela cresce para caber "00:00" numa linha (ver timeColumnWidth).
private val TimeColumnWidth = 44.dp
private val TimeColumnEndGap = 4.dp
internal val TrackWidth = 64.dp
private val PillWidth = 48.dp
// Pílula: CircleShape é o arredondamento total (50%) também em formas não quadradas.
private val PillShape = CircleShape
// O losango (16.dp girado 45°) ocupa ~22.6.dp na diagonal; a trilha abre essa folga para a linha.
internal val DiamondTrackGap = 24.dp

/** A timeline do dia selecionado: tarefas sem horário (recolhíveis) no topo e a trilha de blocos. */
@Composable
internal fun TodayTimeline(
    state: UiState,
    listState: LazyListState,
    onOpenRoutine: (Long) -> Unit,
    onOpenTask: (Long) -> Unit,
    onOpenException: (Long, Long) -> Unit,
    onToggle: (DayTimeline.Block) -> Unit,
    onToggleTask: (Task, Boolean) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val now = rememberCurrentMinute()
    // Muda a cada minuto; cada linha recebe só um Boolean e só recompõe quando o bloco entra ou sai de andamento.
    val nowMinute = remember(now) { LocalTime.now().let { it.hour * 60 + it.minute } }
    var tasksExpanded by rememberSaveable { mutableStateOf(true) }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        // Sem espaçamento entre itens: as linhas de ligação passam de um bloco para o outro.
        contentPadding = contentPadding,
    ) {
        if (state.untimedTasks.isNotEmpty()) {
            item(key = "today_tasks_header", contentType = "tasks_header") {
                DayTasksHeader(
                    title = if (state.isShowingToday) {
                        stringResource(R.string.routines_day_tasks_today)
                    } else {
                        stringResource(R.string.routines_day_tasks)
                    },
                    count = state.untimedTasks.size,
                    expanded = tasksExpanded,
                    onToggle = { tasksExpanded = !tasksExpanded },
                )
            }
            if (tasksExpanded) {
                items(
                    items = state.untimedTasks,
                    key = { "band_${it.task.id}" },
                    contentType = { "band_task" },
                ) { dayTask ->
                    DayTaskRow(
                        dayTask = dayTask,
                        listProgress = state.progressFor(dayTask.task.shoppingListId),
                        canComplete = state.canComplete,
                        onOpen = { onOpenTask(dayTask.task.id) },
                        onToggle = { onToggleTask(dayTask.task, dayTask.done) },
                    )
                }
            }
        }
        if (state.timeline.isEmpty() && state.untimedTasks.isEmpty()) {
            item(key = "empty_day", contentType = "empty_day") {
                Text(
                    text = stringResource(R.string.routines_empty_day),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                )
            }
        }
        items(
            items = state.timeline,
            key = { it.key },
            contentType = {
                when (it) {
                    is DayTimeline.Block -> "block"
                    is DayTimeline.Gap -> "gap"
                    is DayTimeline.TaskItem -> "task"
                }
            },
        ) { item ->
            when (item) {
                is DayTimeline.Block -> BlockRow(
                    block = item,
                    listProgress = state.progressFor(item.routine.shoppingListId),
                    inProgress = state.isShowingToday && nowMinute >= item.startMinute && nowMinute < item.endMinute,
                    canComplete = state.canComplete,
                    // Rotina excluída: o bloco fica nos dias passados, mas não há mais o que editar.
                    onOpen = if (item.routine.archivedAt == null) ({ onOpenRoutine(item.routine.id) }) else null,
                    // Pressionar e segurar numa ocorrência abre a exceção (pular/remarcar só aquele dia).
                    onLongPress = if (item.routine.archivedAt == null) {
                        ({ onOpenException(item.routine.id, state.selectedDate.toEpochDay()) })
                    } else {
                        null
                    },
                    onToggle = { onToggle(item) },
                )
                is DayTimeline.Gap -> GapRow(item)
                is DayTimeline.TaskItem -> TaskRow(
                    item = item,
                    listProgress = state.progressFor(item.task.shoppingListId),
                    canComplete = state.canComplete,
                    onOpen = { onOpenTask(item.task.id) },
                    onToggle = { onToggleTask(item.task, item.done) },
                )
            }
        }
    }
}

/**
 * Semântica de uma linha da timeline (ADR-0002, 14.9; mesmo padrão da C1 da lista de compras): UM nó
 * com o conteúdo, o estado ([state], "Feita"/"Pendente") e concluir/desmarcar como ação de
 * acessibilidade ([toggleLabel]), só quando o dia aceita ([canComplete]). O círculo continua tocável,
 * mas fica fora da leitura ([clearAndSetSemantics] nele), para a ação não aparecer duas vezes.
 */
internal fun Modifier.timelineRowSemantics(
    state: String,
    toggleLabel: String,
    canComplete: Boolean,
    onToggle: () -> Unit,
): Modifier = semantics(mergeDescendants = true) {
    stateDescription = state
    if (canComplete) {
        customActions = listOf(CustomAccessibilityAction(toggleLabel) { onToggle(); true })
    }
}

@Composable
internal fun rowStateLabel(done: Boolean): String =
    stringResource(if (done) R.string.today_state_done else R.string.today_state_pending)

/** A pílula cresce com a duração: 30 min ou menos é um círculo, a partir de ~1h40 para de crescer. */
private fun pillHeight(durationMinutes: Int): Dp = (durationMinutes * 1.6f).coerceIn(48f, 160f).dp

@Composable
private fun BlockRow(
    block: DayTimeline.Block,
    listProgress: ShoppingProgress?,
    inProgress: Boolean,
    canComplete: Boolean,
    onOpen: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
    onToggle: () -> Unit,
) {
    val routine = block.routine
    val tone = routineTone(RoutineColor.fromKey(routine.color))
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val pillHeight = pillHeight(routine.durationMinutes)
    val hasDuration = routine.durationMinutes > 0
    val timeColumnWidth = timeColumnWidth()
    val toggleLabel = stringResource(
        if (block.done) R.string.routine_uncomplete else R.string.routine_complete,
        routine.title,
    )
    val exceptionLabel = stringResource(R.string.today_block_exception)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(MaterialTheme.shapes.medium)
            .timelineRowSemantics(rowStateLabel(block.done), toggleLabel, canComplete, onToggle)
            .then(
                if (onOpen != null) {
                    Modifier.combinedClickable(
                        onClick = onOpen,
                        onLongClick = onLongPress,
                        onLongClickLabel = if (onLongPress != null) exceptionLabel else null,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Column(
            verticalArrangement = if (hasDuration) Arrangement.SpaceBetween else Arrangement.Center,
            modifier = Modifier
                .width(timeColumnWidth)
                .fillMaxHeight()
                .padding(vertical = RowPadding + 2.dp)
                .clearAndSetSemantics {},
        ) {
            TimeLabel(minuteOfDayLabel(block.startMinute))
            if (hasDuration) {
                TimeLabel(minuteOfDayLabel(block.endMinute))
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(TrackWidth)
                .fillMaxHeight()
                .drawBehind { drawLinks(block.linkAbove, block.linkBelow, pillHeight.toPx(), lineColor) },
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(vertical = RowPadding)
                    .size(width = PillWidth, height = pillHeight)
                    .clip(PillShape)
                    .background(tone.fill)
                    .then(
                        if (inProgress) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, PillShape) else Modifier,
                    ),
            ) {
                Icon(
                    imageVector = RoutineIcon.fromKey(routine.icon).imageVector,
                    contentDescription = null,
                    tint = tone.onFill,
                )
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .weight(1f)
                .align(Alignment.CenterVertically)
                .padding(start = 8.dp, top = RowPadding, bottom = RowPadding),
        ) {
            if (inProgress) {
                Text(
                    text = stringResource(R.string.routine_now),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = routineTimeRange(block.startMinute, routine.durationMinutes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // M-1: o horário cede espaço (reticências) para o sino e o 2/3 ficarem inteiros.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (routine.remind) {
                    Icon(
                        imageVector = Icons.Outlined.Notifications,
                        contentDescription = stringResource(R.string.routine_reminds),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(14.dp),
                    )
                }
                if (listProgress != null) {
                    ShoppingProgressTag(progress = listProgress, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Text(
                text = routine.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (block.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (block.done) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onToggle,
            enabled = canComplete,
            // Concluir já é ação do nó da linha (timelineRowSemantics); o círculo é só o alvo de toque.
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .clearAndSetSemantics {},
        ) {
            Icon(
                imageVector = if (block.done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (canComplete) tone.accent else tone.accent.copy(alpha = 0.38f),
            )
        }
    }
}

@Composable
private fun GapRow(gap: DayTimeline.Gap) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val timeColumnWidth = timeColumnWidth()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Spacer(Modifier.width(timeColumnWidth))
        Box(
            modifier = Modifier
                .width(TrackWidth)
                .fillMaxHeight()
                .drawBehind { drawLink(Link.DASHED, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), lineColor) },
        )
        Icon(
            imageVector = Icons.Outlined.LocalCafe,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(16.dp),
        )
        // Sempre numa linha. Se "Tempo livre · 1h 20min" não couber (fonte grande), fica só a duração
        // ao lado do ícone de café; o leitor de tela continua recebendo a frase inteira.
        val duration = DurationFormat.format(gap.minutes)
        val full = stringResource(R.string.routine_free_time, duration)
        val style = MaterialTheme.typography.bodyMedium
        val measurer = rememberTextMeasurer(cacheSize = 1)
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            val maxWidthPx = constraints.maxWidth
            val fits = remember(measurer, full, style, maxWidthPx) {
                measurer.measure(full, style, maxLines = 1, softWrap = false).size.width <= maxWidthPx
            }
            Text(
                text = if (fits) full else duration,
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = if (fits) Modifier else Modifier.semantics { contentDescription = full },
            )
        }
    }
}

/** Hora da coluna da timeline: sempre numa linha ("03:35", nunca "03:" / "35"). */
@Composable
internal fun TimeLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
    )
}

/**
 * Largura da coluna de horas: [TimeColumnWidth] (o layout de sempre na fonte 1.0) ou, se a fonte do
 * sistema não deixar "00:00" caber nela, a largura medida do texto. Medida, não fixa, para todas as
 * linhas da timeline (bloco, intervalo e tarefa) manterem o trilho alinhado em qualquer escala.
 */
@Composable
internal fun timeColumnWidth(): Dp =
    rememberTimeColumnWidth(TimeColumnWidth, MaterialTheme.typography.labelMedium, TimeColumnEndGap)

/** Linhas acima e abaixo da pílula, que fica centralizada no trilho. */
internal fun DrawScope.drawLinks(above: Link, below: Link, pillHeightPx: Float, color: Color) {
    val x = size.width / 2
    val pillTop = (size.height - pillHeightPx) / 2
    drawLink(above, Offset(x, 0f), Offset(x, pillTop), color)
    drawLink(below, Offset(x, pillTop + pillHeightPx), Offset(x, size.height), color)
}

private fun DrawScope.drawLink(link: Link, start: Offset, end: Offset, color: Color) {
    if (link == Link.NONE || end.y <= start.y) return
    val dash = 4.dp.toPx()
    drawLine(
        color = color,
        start = start,
        end = end,
        strokeWidth = 2.dp.toPx(),
        pathEffect = if (link == Link.DASHED) PathEffect.dashPathEffect(floatArrayOf(dash, dash)) else null,
    )
}
