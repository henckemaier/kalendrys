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
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.kalendrys.app.AppLinks
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.R
import com.kalendrys.app.domain.AppLocale
import com.kalendrys.app.domain.CalendarCalculator
import com.kalendrys.app.domain.TodayOverview
import com.kalendrys.app.ui.components.WeekdayName
import com.kalendrys.app.ui.components.firstDayOfWeek
import com.kalendrys.app.ui.components.weekdayName
import com.kalendrys.app.ui.routines.RoutinePalette
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** "Calendário": o mês com o dia de hoje marcado e, ao lado, as tarefas e rotinas de hoje. */
class CalendarWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = (context.applicationContext as KalendrysApp).todayRepository
        val initial = today.current()
        provideContent {
            // Ver TasksWidget: observar dentro da composição mantém a sessão aberta atualizada.
            val overview by remember { today.observe() }.collectAsState(initial)
            GlanceTheme(colors = KalendrysWidgets.colors) {
                CalendarContent(overview)
            }
        }
    }
}

class CalendarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CalendarWidget()
}

/** Um item do dia na lista ao lado do mês. */
private data class DayItem(val key: Long, val title: String, val subtitle: String, val color: ColorProvider, val time: LocalTime?)

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("d 'de' MMMM", AppLocale.LOCALE)

@Composable
private fun CalendarContent(overview: TodayOverview) {
    val context = LocalContext.current
    val items = dayItems(context, overview)
    Scaffold(
        modifier = GlanceModifier.clickable(actionStartActivity(AppLinks.openCalendar(context))),
        backgroundColor = KalendrysWidgets.background,
        horizontalPadding = 16.dp,
    ) {
        Row(modifier = GlanceModifier.fillMaxSize().padding(vertical = 12.dp)) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = overview.date.format(TITLE),
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(6.dp))
                MonthGrid(overview.date)
            }
            Spacer(GlanceModifier.width(16.dp))
            Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                if (items.isEmpty()) {
                    Text(
                        text = context.getString(R.string.widget_calendar_empty),
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                        modifier = GlanceModifier.padding(top = 4.dp),
                    )
                } else {
                    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                        items(items, itemId = { it.key }) { item -> DayItemRow(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(today: LocalDate) {
    val resources = LocalContext.current.resources
    val firstDay = firstDayOfWeek()
    val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    val cellStyle = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 11.sp, textAlign = TextAlign.Center)
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            for (offset in 0L until 7L) {
                val day = firstDay.plus(offset)
                Text(
                    text = resources.weekdayName(day, WeekdayName.NARROW),
                    style = cellStyle.copy(
                        color = if (day in weekend) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                    ),
                    modifier = GlanceModifier.defaultWeight(),
                )
            }
        }
        CalendarCalculator.monthGrid(YearMonth.from(today), firstDay).chunked(7).forEach { week ->
            Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp)) {
                week.forEach { date ->
                    Box(modifier = GlanceModifier.defaultWeight(), contentAlignment = Alignment.Center) {
                        when {
                            date == null -> Text(text = "", style = cellStyle)
                            date == today -> Box(
                                modifier = GlanceModifier.size(20.dp).background(ImageProvider(R.drawable.widget_today_circle)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = date.dayOfMonth.toString(),
                                    style = cellStyle.copy(
                                        color = ColorProvider(KalendrysWidgets.onToday),
                                        fontWeight = FontWeight.Bold,
                                    ),
                                )
                            }
                            else -> Text(text = date.dayOfMonth.toString(), style = cellStyle)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayItemRow(item: DayItem) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_circle),
            contentDescription = null,
            colorFilter = ColorFilter.tint(item.color),
            modifier = GlanceModifier.size(12.dp).padding(top = 3.dp),
        )
        Spacer(GlanceModifier.width(8.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = item.title,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
                maxLines = 2,
            )
            Text(
                text = item.subtitle,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                maxLines = 1,
            )
        }
    }
}

/** Tarefas com data (de hoje ou atrasadas) e blocos de rotina ainda pendentes; os sem horário primeiro. */
private fun dayItems(context: Context, overview: TodayOverview): List<DayItem> {
    val tasks = overview.pending.filter { it.dated }.map { task ->
        DayItem(
            key = task.taskId,
            title = task.title,
            subtitle = when {
                task.overdue -> context.getString(R.string.widget_overdue)
                task.reminderToday != null -> task.reminderToday.format(TIME)
                else -> context.getString(R.string.widget_all_day)
            },
            color = if (task.overdue) ColorProvider(KalendrysWidgets.overdue) else ColorProvider(KalendrysWidgets.accent),
            time = task.reminderToday,
        )
    }
    val routines = overview.pendingRoutines.map { block ->
        DayItem(
            // Ids negativos: os das rotinas e os das tarefas começam ambos em 1.
            key = -block.routineId,
            title = block.title,
            subtitle = listOfNotNull(block.start, block.end).joinToString("–") { it.format(TIME) },
            color = ColorProvider(RoutinePalette.dark(block.color).accent),
            time = block.start,
        )
    }
    return (tasks + routines).sortedWith(compareBy(nullsFirst()) { it.time })
}
