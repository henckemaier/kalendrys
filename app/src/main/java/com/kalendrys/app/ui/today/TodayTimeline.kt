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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.alpha
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
import com.kalendrys.app.ui.components.rememberTimeColumnWidth
import com.kalendrys.app.ui.routines.imageVector
import com.kalendrys.app.ui.routines.minuteOfDayLabel
import com.kalendrys.app.ui.routines.routineTimeRange
import com.kalendrys.app.ui.routines.routineTone
import com.kalendrys.app.ui.shopping.ShoppingProgressTag
import com.kalendrys.app.ui.today.TodayViewModel.UiState

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
/** Bloco pulado (P5): esmaecido, mas o controle de desfazer fica com a cor cheia. */
private const val SkippedAlpha = 0.45f

/** O que a trilha faz com cada item; a tela liga ao ViewModel e à navegação. */
internal class TrailActions(
    val onOpenRoutine: (Long) -> Unit,
    val onOpenTask: (Long) -> Unit,
    /** "Remarcar este dia": a tela de exceção da ocorrência (rotina, dia). */
    val onReschedule: (Long, Long) -> Unit,
    val onToggle: (DayTimeline.Block) -> Unit,
    val onToggleTask: (Task, Boolean) -> Unit,
    /** "Pular este dia" (menu do bloco): o mesmo `skipOccurrence` da tela de exceção. */
    val onSkip: (DayTimeline.Block) -> Unit,
    /** "Desfazer pular" do próprio bloco: só tira o pular. */
    val onUnskip: (DayTimeline.Block) -> Unit,
)

/**
 * A trilha do dia selecionado, como itens da lista única da Hoje (a faixa da semana e as seções vêm
 * antes e depois, na mesma lista). Sem espaçamento entre itens: as linhas de ligação passam de um
 * bloco para o outro.
 */
internal fun LazyListScope.todayTrail(state: UiState, nowMinute: Int, actions: TrailActions) {
    if (state.timeline.isEmpty()) {
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
            is DayTimeline.Block -> {
                // Rotina excluída: o bloco fica nos dias passados, mas não há mais o que editar nem pular.
                val editable = item.routine.archivedAt == null
                BlockRow(
                    block = item,
                    listProgress = state.progressFor(item.routine.shoppingListId),
                    inProgress = !item.skipped && state.isShowingToday &&
                        nowMinute >= item.startMinute && nowMinute < item.endMinute,
                    canComplete = state.canComplete,
                    onOpen = if (editable) ({ actions.onOpenRoutine(item.routine.id) }) else null,
                    onSkip = if (editable && !item.skipped) ({ actions.onSkip(item) }) else null,
                    onReschedule = if (editable) {
                        // A exceção mora no dia da ocorrência (o de origem, se veio remarcado de outro dia).
                        ({ actions.onReschedule(item.routine.id, item.occurrenceDate.toEpochDay()) })
                    } else {
                        null
                    },
                    onToggle = { actions.onToggle(item) },
                    onUnskip = { actions.onUnskip(item) },
                )
            }
            is DayTimeline.Gap -> GapRow(item)
            is DayTimeline.TaskItem -> TaskRow(
                item = item,
                listProgress = state.progressFor(item.task.shoppingListId),
                canComplete = state.canComplete,
                onOpen = { actions.onOpenTask(item.task.id) },
                onToggle = { actions.onToggleTask(item.task, item.done) },
            )
        }
    }
}

/**
 * Semântica de uma linha (ADR-0002, 14.9; mesmo padrão da C1 da lista de compras): UM nó com o
 * conteúdo, o estado ([state]: "Feita", "Pendente", "Pulado") e as ações de acessibilidade da linha
 * ([actions]: concluir/desmarcar, pular, desfazer pular). Os controles visíveis (círculo, desfazer)
 * continuam tocáveis, mas ficam fora da leitura ([clearAndSetSemantics] neles), para a ação não
 * aparecer duas vezes.
 */
internal fun Modifier.timelineRowSemantics(state: String, actions: List<CustomAccessibilityAction>): Modifier =
    semantics(mergeDescendants = true) {
        stateDescription = state
        if (actions.isNotEmpty()) customActions = actions
    }

/** Ação de acessibilidade de uma linha. */
internal fun rowAction(label: String, onAction: () -> Unit) = CustomAccessibilityAction(label) { onAction(); true }

@Composable
internal fun rowStateLabel(done: Boolean): String =
    stringResource(if (done) R.string.today_state_done else R.string.today_state_pending)

/** A pílula cresce com a duração: 30 min ou menos é um círculo, a partir de ~1h40 para de crescer. */
private fun pillHeight(durationMinutes: Int): Dp = (durationMinutes * 1.6f).coerceIn(48f, 160f).dp

/**
 * Bloco de rotina. Tocar abre a rotina; segurar abre um menu curto ancorado no bloco com "Pular este
 * dia" e "Remarcar este dia" (no leitor de tela, o segurar tem rótulo e "Pular este dia" também é
 * ação direta da linha). Pulado (P5): esmaecido, sem concluir, com o desfazer VISÍVEL no lugar do
 * círculo (alvo de 48dp, não depende de segurar) e "Desfazer pular" como ação da linha.
 */
@Composable
private fun BlockRow(
    block: DayTimeline.Block,
    listProgress: ShoppingProgress?,
    inProgress: Boolean,
    canComplete: Boolean,
    onOpen: (() -> Unit)?,
    onSkip: (() -> Unit)?,
    onReschedule: (() -> Unit)?,
    onToggle: () -> Unit,
    onUnskip: () -> Unit,
) {
    val routine = block.routine
    val skipped = block.skipped
    val tone = routineTone(RoutineColor.fromKey(routine.color))
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val pillHeight = pillHeight(routine.durationMinutes)
    val hasDuration = routine.durationMinutes > 0
    val timeColumnWidth = timeColumnWidth()
    var menuOpen by rememberSaveable(block.key) { mutableStateOf(false) }
    val hasMenu = onSkip != null || onReschedule != null

    val toggleLabel = stringResource(
        if (block.done) R.string.routine_uncomplete else R.string.routine_complete,
        routine.title,
    )
    val skipLabel = stringResource(R.string.today_skip_day)
    val rescheduleLabel = stringResource(R.string.today_reschedule_day)
    val undoSkipLabel = stringResource(R.string.today_undo_skip)
    val menuLabel = stringResource(R.string.today_block_exception)
    val stateLabel = if (skipped) stringResource(R.string.today_state_skipped) else rowStateLabel(block.done)
    val rowActions = buildList {
        if (skipped) {
            add(rowAction(undoSkipLabel, onUnskip))
        } else {
            if (canComplete) add(rowAction(toggleLabel, onToggle))
            if (onSkip != null) add(rowAction(skipLabel, onSkip))
        }
    }
    val contentAlpha = if (skipped) Modifier.alpha(SkippedAlpha) else Modifier

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clip(MaterialTheme.shapes.medium)
                .timelineRowSemantics(stateLabel, rowActions)
                .then(
                    if (onOpen != null) {
                        Modifier.combinedClickable(
                            onClick = onOpen,
                            onLongClick = if (hasMenu) ({ menuOpen = true }) else null,
                            onLongClickLabel = if (hasMenu) menuLabel else null,
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
                    .then(contentAlpha)
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
                        .then(contentAlpha)
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
                    .padding(start = 8.dp, top = RowPadding, bottom = RowPadding)
                    .then(contentAlpha),
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
                    if (routine.remind && !skipped) {
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
                    color = if (block.done || skipped) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textDecoration = if (block.done) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (skipped) {
                // Desfazer pular visível (48dp), com a cor cheia; a ação acessível é a da linha.
                IconButton(
                    onClick = onUnskip,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .clearAndSetSemantics {},
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Undo,
                        contentDescription = null,
                        tint = tone.accent,
                    )
                }
            } else {
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
        // Menu do segurar, ancorado no bloco. Fecha no voltar (preditivo, inclusive) sem navegar.
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (onSkip != null) {
                DropdownMenuItem(
                    text = { Text(skipLabel) },
                    onClick = {
                        menuOpen = false
                        onSkip()
                    },
                )
            }
            if (onReschedule != null) {
                DropdownMenuItem(
                    text = { Text(rescheduleLabel) },
                    onClick = {
                        menuOpen = false
                        onReschedule()
                    },
                )
            }
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
