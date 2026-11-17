package com.kalendrys.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kalendrys.app.R
import com.kalendrys.app.domain.DayTimeline
import com.kalendrys.app.domain.ShoppingProgress
import com.kalendrys.app.ui.routines.minuteOfDayLabel
import com.kalendrys.app.ui.shopping.ShoppingProgressTag
import com.kalendrys.app.ui.today.TodayViewModel.DayTask

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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(MaterialTheme.shapes.medium)
            .timelineRowSemantics(rowStateLabel(item.done), taskToggleLabel(item.done, task.title), canComplete, onToggle)
            .clickable(onClick = onOpen),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(timeColumnWidth)
                .padding(vertical = RowPadding + 2.dp)
                .clearAndSetSemantics {},
        ) {
            TimeLabel(minuteOfDayLabel(item.startMinute))
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(TrackWidth)
                .fillMaxHeight()
                // Mesma trilha dos blocos: a linha (cheia/tracejada) continua pela tarefa, sem duplicar —
                // o losango só abre a folga no meio. Vazia (Link.NONE) quando a tarefa fica solta.
                .drawBehind { drawLinks(item.linkAbove, item.linkBelow, DiamondTrackGap.toPx(), lineColor) },
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
            )
            if (listProgress != null) {
                ShoppingProgressTag(progress = listProgress)
            }
        }
        TaskCheck(done = item.done, accent = accent, canComplete = canComplete, onToggle = onToggle)
    }
}

/** Cabeçalho recolhível das tarefas sem horário: marcado como título para o leitor de tela. */
@Composable
internal fun DayTasksHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .semantics { heading() }
            .clickable(onClick = onToggle)
            .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Text(
            text = "$title · $count",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.routines_day_tasks_collapse else R.string.routines_day_tasks_expand,
            ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .timelineRowSemantics(rowStateLabel(dayTask.done), taskToggleLabel(dayTask.done, task.title), canComplete, onToggle)
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
                .padding(start = 12.dp),
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
