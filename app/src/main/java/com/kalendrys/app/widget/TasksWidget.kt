package com.kalendrys.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.components.TitleBar
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.kalendrys.app.AppLinks
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.R
import com.kalendrys.app.domain.AppLocale
import com.kalendrys.app.domain.TodayOverview
import com.kalendrys.app.ui.routines.RoutinePalette
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** "Hoje": tarefas pendentes e blocos de rotina do dia, com conclusão direto da tela inicial. */
class TasksWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = (context.applicationContext as KalendrysApp).todayRepository
        val initial = today.current()
        provideContent {
            // Observado dentro da composição: com a sessão do widget aberta (após um toque),
            // um update() só recompõe — dados lidos fora daqui ficariam congelados.
            val overview by remember { today.observe() }.collectAsState(initial)
            GlanceTheme(colors = KalendrysWidgets.colors) {
                TasksContent(overview)
            }
        }
    }
}

class TasksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TasksWidget()
}

/** Toque no círculo de uma tarefa no widget. */
class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val taskId = parameters[TaskIdKey] ?: return
        (context.applicationContext as KalendrysApp).repository.logCompletion(taskId)
        KalendrysWidgets.updateAll(context)
    }
}

/** Toque no círculo de um bloco de rotina no widget; marcar de novo não desfaz. */
class CompleteRoutineAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val routineId = parameters[RoutineIdKey] ?: return
        val epochDay = parameters[EpochDayKey] ?: return
        (context.applicationContext as KalendrysApp).routineRepository.complete(routineId, LocalDate.ofEpochDay(epochDay))
        KalendrysWidgets.updateAll(context)
    }
}

private val TaskIdKey = ActionParameters.Key<Long>("taskId")
private val RoutineIdKey = ActionParameters.Key<Long>("routineId")
private val EpochDayKey = ActionParameters.Key<Long>("epochDay")
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val TITLE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM", AppLocale.LOCALE)

@Composable
private fun TasksContent(overview: TodayOverview) {
    val context = LocalContext.current
    val routines = overview.pendingRoutines
    Scaffold(
        backgroundColor = KalendrysWidgets.background,
        titleBar = {
            TitleBar(
                startIcon = ImageProvider(R.drawable.ic_widget_check_circle),
                iconColor = GlanceTheme.colors.primary,
                title = context.getString(R.string.widget_tasks_title, overview.date.format(TITLE_DATE)),
                actions = {
                    // Sem fundo: um círculo preenchido encostava no canto arredondado do widget.
                    CircleIconButton(
                        imageProvider = ImageProvider(R.drawable.ic_widget_add),
                        contentDescription = context.getString(R.string.new_task),
                        onClick = actionStartActivity(AppLinks.newTask(context)),
                        backgroundColor = null,
                        contentColor = GlanceTheme.colors.primary,
                    )
                },
            )
        },
    ) {
        if (overview.pending.isEmpty() && routines.isEmpty()) {
            Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = context.getString(R.string.widget_empty),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                )
            }
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                item { Text(text = subtitle(context, overview), style = secondaryText(), modifier = GlanceModifier.padding(bottom = 4.dp)) }
                items(overview.pending, itemId = { it.taskId }) { task -> TaskRow(task) }
                if (routines.isNotEmpty()) {
                    item {
                        Text(
                            text = context.getString(R.string.widget_routines_section),
                            style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                            modifier = GlanceModifier.padding(top = 6.dp, bottom = 2.dp),
                        )
                    }
                    // Ids negativos: os das rotinas e os das tarefas começam ambos em 1.
                    items(routines, itemId = { -it.routineId }) { block -> RoutineRow(block, overview.date) }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: TodayOverview.PendingTask) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(actionStartActivity(AppLinks.openTask(context, task.taskId))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_circle),
            contentDescription = context.getString(R.string.widget_complete, task.title),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
            modifier = GlanceModifier
                .size(36.dp)
                .padding(7.dp)
                .clickable(actionRunCallback<CompleteTaskAction>(actionParametersOf(TaskIdKey to task.taskId))),
        )
        Spacer(GlanceModifier.width(6.dp))
        Text(
            text = task.title,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        task.reminderToday?.let { time ->
            Spacer(GlanceModifier.width(8.dp))
            Image(
                provider = ImageProvider(if (task.recurring) R.drawable.ic_widget_repeat else R.drawable.ic_widget_bell),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                modifier = GlanceModifier.size(14.dp),
            )
            Spacer(GlanceModifier.width(3.dp))
            Text(
                text = time.format(TIME),
                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            )
        }
    }
}

@Composable
private fun RoutineRow(block: TodayOverview.RoutineBlock, date: LocalDate) {
    val context = LocalContext.current
    // O Glance não desenha os ícones vetoriais da rotina: a cor dela vai no círculo de conclusão.
    val accent = ColorProvider(RoutinePalette.dark(block.color).accent)
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(actionStartActivity(AppLinks.openRoutines(context, date))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_circle),
            contentDescription = context.getString(R.string.routine_complete, block.title),
            colorFilter = ColorFilter.tint(accent),
            modifier = GlanceModifier
                .size(36.dp)
                .padding(7.dp)
                .clickable(
                    actionRunCallback<CompleteRoutineAction>(
                        actionParametersOf(RoutineIdKey to block.routineId, EpochDayKey to date.toEpochDay()),
                    ),
                ),
        )
        Spacer(GlanceModifier.width(6.dp))
        Text(
            text = block.title,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        Spacer(GlanceModifier.width(8.dp))
        Text(
            text = block.start.format(TIME),
            style = TextStyle(color = accent, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        )
    }
}

private fun subtitle(context: Context, overview: TodayOverview): String {
    val res = context.resources
    val tasks = overview.pending.size
    val routines = overview.pendingRoutines.size
    return listOfNotNull(
        tasks.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.widget_tasks_count, it, it) },
        routines.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.widget_routines_count, it, it) },
        overview.remindersToday.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.summary_reminders, it, it) },
    ).joinToString(" · ")
}

@Composable
private fun secondaryText() = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp)
