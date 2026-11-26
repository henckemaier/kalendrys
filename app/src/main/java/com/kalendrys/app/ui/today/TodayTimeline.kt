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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kalendrys.app.R
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.DayTimeline
import com.kalendrys.app.domain.DayTimeline.Link
import com.kalendrys.app.domain.DurationFormat
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineIcon
import com.kalendrys.app.ui.components.rememberTimeColumnWidth
import com.kalendrys.app.ui.routines.imageVector
import com.kalendrys.app.ui.routines.minuteOfDayLabel
import com.kalendrys.app.ui.routines.routineTimeRange
import com.kalendrys.app.ui.routines.routineTone
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

/**
 * Altura MÍNIMA da pílula pela duração (v1.8.0, E4, 14.5, A1 do Esquadro): 30 min ou menos é um
 * círculo (48dp), cresce com a duração e para em ~1h40 (160dp), para o bloco longo continuar mais
 * alto que o curto. É só o mínimo: o bloco toma a altura do MAIOR entre conteúdo (horários, faixa,
 * título inteiro) e pílula, então com fonte grande ou título longo ele passa disso sem cortar nem
 * sobrepor, e a pílula acompanha a altura do bloco.
 */
private fun pillMinHeight(durationMinutes: Int): Dp = (durationMinutes * 1.6f).coerceIn(48f, 160f).dp

/**
 * Bloco de rotina. Tocar abre a rotina; segurar abre um menu curto ancorado no bloco com "Pular este
 * dia" e "Remarcar este dia" (no leitor de tela, o segurar tem rótulo e "Pular este dia" também é
 * ação direta da linha). Pulado (P5): esmaecido, sem concluir, com o desfazer VISÍVEL no lugar do
 * círculo (alvo de 48dp, não depende de segurar) e "Desfazer pular" como ação da linha.
 */
@Composable
private fun BlockRow(
    block: DayTimeline.Block,
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
                    // A pílula ocupa a altura do bloco menos o respiro: as ligações vão só no respiro.
                    .drawBehind {
                        drawLinks(block.linkAbove, block.linkBelow, size.height - 2 * RowPadding.toPx(), lineColor)
                    },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(vertical = RowPadding)
                        .width(PillWidth)
                        .heightIn(min = pillMinHeight(routine.durationMinutes))
                        .fillMaxHeight()
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
                val timeRange = routineTimeRange(block.startMinute, routine.durationMinutes)
                // N-8 (v1.9.0): a frase inteira do horário mora nesta linha, que SEMPRE tem tamanho (o horário
                // pode não aparecer, no terceiro degrau); o leitor de tela a lê uma vez, no nó mesclado do bloco.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // Largura inteira: a linha tem tamanho mesmo sem sino, sem lista e sem horário desenhado.
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { text = AnnotatedString(timeRange) },
                ) {
                    // M-1: o horário cede espaço para o sino e o 2/3 ficarem inteiros. O-7: sempre numa linha:
                    // a faixa inteira se couber, senão só a duração ("2h"), senão nada (N-2).
                    OneLineTimeRange(
                        full = timeRange,
                        short = if (routine.durationMinutes > 0) {
                            DurationFormat.format(routine.durationMinutes)
                        } else {
                            minuteOfDayLabel(block.startMinute)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    // Título inteiro (E4): o bloco cresce com ele; sem limite de linhas nem reticências.
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

/**
 * Faixa de horário do bloco SEMPRE numa linha (O-7 da v1.8.0): [full] ("07:00–09:00 (2h)") se couber
 * na largura que sobra, senão [short] (só a duração, "2h"; num bloco sem duração os dois são a hora).
 * Escolha por medida, no molde da linha do tempo livre, mas com um `Layout` simples em vez de
 * `BoxWithConstraints`: o bloco mede a altura pelas intrínsecas (`IntrinsicSize.Min`) e um
 * `SubcomposeLayout` não as responde. O leitor de tela lê sempre a frase inteira, que a LINHA do
 * horário declara (N-8: no terceiro degrau este nó não tem largura); os dois textos desenhados ficam
 * fora da leitura.
 *
 * Terceiro degrau (v1.9.0, N-2; antes NB-1 com reticências): se nem a forma curta couber (duração
 * longa, "23h 59min", em 320dp com fonte 2.0, sino e lista juntos), não aparece nada; a caixa nunca
 * passa da largura que sobra, e o leitor de tela continua com a frase inteira (da linha).
 */
/** Marca de teste do horário do bloco (medida da N-2). */
internal const val TIME_RANGE_TAG = "timeline-time-range"

@Composable
private fun OneLineTimeRange(full: String, short: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    Layout(
        content = {
            Text(text = full, style = style, color = color, maxLines = 1, softWrap = false)
            Text(text = short, style = style, color = color, maxLines = 1, softWrap = false)
        },
        // Sem semântica própria (N-8): a frase vem da linha que o contém; aqui só a marca de teste.
        modifier = modifier.clearAndSetSemantics { testTag = TIME_RANGE_TAG },
    ) { measurables, constraints ->
        // Três degraus (v1.9.0, N-2), cada forma medida na largura natural, numa linha só: a faixa
        // inteira se couber; senão só a curta (a duração); senão NADA, sem reticências. Início e fim
        // seguem na coluna de horas, e o nó (e a linha mesclada) leva sempre a frase inteira.
        val natural = constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity)
        val wide = measurables[0].measure(natural)
        val fits = { width: Int -> !constraints.hasBoundedWidth || width <= constraints.maxWidth }
        val chosen = if (fits(wide.width)) wide else measurables[1].measure(natural).takeIf { fits(it.width) }
        // Sem forma que caiba: largura mínima, a altura de uma linha (o alinhamento da linha não muda).
        val width = (chosen?.width ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (chosen?.height ?: wide.height).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) { chosen?.placeRelative(0, 0) }
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
