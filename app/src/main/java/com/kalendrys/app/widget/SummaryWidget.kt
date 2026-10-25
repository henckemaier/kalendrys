package com.kalendrys.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.kalendrys.app.AppLinks
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.R
import com.kalendrys.app.domain.TodayOverview

/** "Resumo do dia": quanto falta hoje (tarefas + rotinas) e o que vem a seguir. */
class SummaryWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = (context.applicationContext as KalendrysApp).todayRepository
        val initial = today.current()
        provideContent {
            // Ver TasksWidget: observar dentro da composição mantém a sessão aberta atualizada.
            val overview by remember { today.observe() }.collectAsState(initial)
            GlanceTheme(colors = KalendrysWidgets.colors) {
                SummaryContent(overview)
            }
        }
    }
}

class SummaryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SummaryWidget()
}

@Composable
private fun SummaryContent(overview: TodayOverview) {
    val context = LocalContext.current
    val res = context.resources
    val pending = overview.pendingCount
    val tasks = overview.pending.size
    val routines = overview.pendingRoutines.size
    Scaffold(
        modifier = GlanceModifier.clickable(actionStartActivity(AppLinks.openApp(context))),
        backgroundColor = KalendrysWidgets.background,
    ) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(vertical = 14.dp)) {
            Text(
                text = context.getString(R.string.widget_today),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = pending.toString(),
                    style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 40.sp, fontWeight = FontWeight.Bold),
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    // Em pt-BR o Android trata 0 como singular; "0 pendente" ficaria errado.
                    text = if (pending == 0) {
                        context.getString(R.string.widget_pending_zero)
                    } else {
                        res.getQuantityString(R.plurals.widget_pending, pending)
                    },
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
                    modifier = GlanceModifier.padding(bottom = 8.dp),
                )
            }
            // Só desmembra quando há rotinas; "3 tarefas" sozinho repetiria o número acima.
            if (routines > 0) {
                Text(
                    text = listOfNotNull(
                        tasks.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.widget_tasks_count, it, it) },
                        res.getQuantityString(R.plurals.widget_routines_count, routines, routines),
                    ).joinToString(" · "),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.defaultWeight())
            Text(
                text = context.getString(R.string.widget_next_reminder),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
            )
            Text(
                text = overview.nextUp?.let {
                    "${KalendrysWidgets.reminderMoment(context, it.at, overview.date)} · ${it.title}"
                } ?: context.getString(R.string.widget_no_reminders),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                maxLines = 2,
            )
        }
    }
}
