package com.kalendrys.app.ui.diagnostics

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineOverride
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.data.settings.SettingsRepository
import com.kalendrys.app.domain.AppAlarmKind
import com.kalendrys.app.domain.NextAppAlarm
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.RoutineOverrideType
import com.kalendrys.app.reminder.ReminderScheduler
import com.kalendrys.app.reminder.RoutineAlarmReceiver
import com.kalendrys.app.reminder.RoutineAlarms
import com.kalendrys.app.summary.DailyReceiver
import com.kalendrys.app.summary.DailyScheduler
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.FakeRoutineNotifications
import com.kalendrys.app.testutil.InMemoryRoutineCatchUpMark
import com.kalendrys.app.testutil.ViewModelTestDatabase
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Diagnóstico de alarmes (v1.7.1, E3): a linha mostra o próximo aviso DO KALENDRYS, calculado dos
 * dados. Os dois caminhos são comparados de verdade: com os mesmos dados, o instante que o
 * agendador REAL entrega ao AlarmManager (lido do Robolectric) é o que o diagnóstico calcula, para
 * lembrete de tarefa, rotina com aviso e resumo. O despertador de outro app (Relógio) não aparece.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AlarmDiagnosticsViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.systemDefault()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var settings: SettingsRepository

    // 16/11/2026 08:00 no fuso do aparelho: relógio fixo para rotina e resumo.
    private val now = LocalDateTime.of(2026, 11, 16, 8, 0).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setup() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        testDb = ViewModelTestDatabase(context)
        settings = SettingsRepository(
            PreferenceDataStoreFactory.create { File(context.cacheDir, "diagnostics-${System.nanoTime()}.preferences_pb") },
        )
    }

    @After
    fun tearDown() = testDb.close()

    private fun taskRepository(scheduler: ReminderScheduler = FakeReminderScheduler(context)) = testDb.db.let { db ->
        TaskRepository(db.taskDao(), db.completionLogDao(), scheduler, db.shoppingItemDao())
    }

    private fun routineRepository(alarms: RoutineAlarms = FakeRoutineAlarms(context)) = testDb.db.let { db ->
        RoutineRepository(
            db.routineDao(),
            db.routineCompletionDao(),
            db.routineOverrideDao(),
            alarms,
            FakeRoutineNotifications(context),
            db.shoppingItemDao(),
            InMemoryRoutineCatchUpMark(),
            clock = { now },
        )
    }

    private fun nextAlarm(
        tasks: TaskRepository = taskRepository(),
        routines: RoutineRepository = routineRepository(),
        clock: () -> Long = { now },
    ): NextAppAlarm? {
        val viewModel = testDb.track(AlarmDiagnosticsViewModel(tasks, routines, settings, zone = { zone }, clock = clock))
        viewModel.refresh()
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (viewModel.nextAlarm.value == AlarmDiagnosticsViewModel.NextAlarmState.Loading) {
            check(System.currentTimeMillis() < deadline) { "diagnóstico não carregou" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        return (viewModel.nextAlarm.value as AlarmDiagnosticsViewModel.NextAlarmState.Loaded).next
    }

    /** Instante do alarme agendado com o receiver/ação dados, lido do AlarmManager do Robolectric. */
    private fun scheduledAt(receiver: Class<*>, action: String?): Long {
        val alarms = shadowOf(alarmManager).scheduledAlarms.filter {
            val intent = shadowOf(it.operation).savedIntent
            intent.component?.className == receiver.name && (action == null || intent.action == action)
        }
        assertEquals("um alarme de ${receiver.simpleName} $action", 1, alarms.size)
        return alarms.single().triggerAtTime
    }

    private fun disableSummary() = runBlocking { settings.setSummaryEnabled(false) }

    @Test
    fun `sem nada agendado o diagnostico diz nenhum`() {
        disableSummary()
        assertEquals(null, nextAlarm())
    }

    @Test
    fun `despertador de outro app nao aparece como aviso do Kalendrys`() {
        disableSummary()
        // Um despertador "do Relógio" amanhã às 06:00: o AlarmManager.nextAlarmClock o mostraria.
        val clockAlarm = LocalDateTime.of(2026, 11, 17, 6, 0).atZone(zone).toInstant().toEpochMilli()
        val other = PendingIntent.getBroadcast(context, 99, Intent("relogio.ALARME"), PendingIntent.FLAG_IMMUTABLE)
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(clockAlarm, other), other)
        assertNotNull(alarmManager.nextAlarmClock)

        assertEquals(null, nextAlarm())
    }

    @Test
    fun `lembrete de tarefa e o mesmo instante que o agendador de lembretes agenda`() {
        disableSummary()
        // Longe do relógio real: o ReminderScheduler agenda a partir de System.currentTimeMillis.
        val due = LocalDate.now(zone).plusDays(2)
        val reminderAt = due.atTime(LocalTime.of(10, 0)).atZone(zone).toInstant().toEpochMilli()
        runBlocking { testDb.db.taskDao().insert(Task(title = "Pagar a conta", dueDate = due.toEpochDay(), reminderAt = reminderAt)) }
        val realNow = System.currentTimeMillis()
        val tasks = taskRepository(ReminderScheduler(context))
        runBlocking { tasks.rescheduleReminders(realNow) }
        val scheduled = scheduledAt(com.kalendrys.app.reminder.ReminderReceiver::class.java, action = null)

        assertEquals(reminderAt, scheduled)
        assertEquals(NextAppAlarm(scheduled, AppAlarmKind.TASK), nextAlarm(tasks = tasks, clock = { realNow }))
    }

    @Test
    fun `rotina com aviso e o mesmo instante que o agendador de rotinas agenda`() {
        disableSummary()
        val routines = routineRepository(RoutineAlarms(context))
        runBlocking { routines.add(Routine(title = "Academia", startMinute = 10 * 60, remind = true, createdAt = 0)) }
        val scheduled = scheduledAt(RoutineAlarmReceiver::class.java, RoutineAlarmReceiver.ACTION_START)

        assertEquals(LocalDateTime.of(2026, 11, 16, 10, 0).atZone(zone).toInstant().toEpochMilli(), scheduled)
        assertEquals(NextAppAlarm(scheduled, AppAlarmKind.ROUTINE), nextAlarm(routines = routines))
    }

    @Test
    fun `rotina sem aviso nao conta, o proximo aviso e o da que avisa`() {
        disableSummary()
        val routines = routineRepository()
        runBlocking {
            routines.add(Routine(title = "Silenciosa", startMinute = 9 * 60, remind = false, createdAt = 0))
            routines.add(Routine(title = "Com aviso", startMinute = 11 * 60, remind = true, createdAt = 0))
        }
        val expected = LocalDateTime.of(2026, 11, 16, 11, 0).atZone(zone).toInstant().toEpochMilli()

        assertEquals(NextAppAlarm(expected, AppAlarmKind.ROUTINE), nextAlarm(routines = routines))
    }

    @Test
    fun `resumo diario e o mesmo instante que o agendador diario agenda`() {
        runBlocking {
            settings.setSummaryEnabled(true)
            settings.setSummaryTime(LocalTime.of(21, 30))
            DailyScheduler(context).scheduleAll(settings.settings.first(), LocalDateTime.of(2026, 11, 16, 8, 0))
        }
        val scheduled = scheduledAt(DailyReceiver::class.java, DailyReceiver.ACTION_SUMMARY)

        assertEquals(NextAppAlarm(scheduled, AppAlarmKind.SUMMARY), nextAlarm())
    }

    @Test
    fun `com tarefa, rotina e resumo mostra o mais proximo`() {
        runBlocking { settings.setSummaryTime(LocalTime.of(21, 30)) }
        val routines = routineRepository()
        runBlocking { routines.add(Routine(title = "Almoço", startMinute = 12 * 60, remind = true, createdAt = 0)) }
        val due = LocalDate.of(2026, 11, 16)
        val reminderAt = due.atTime(LocalTime.of(9, 15)).atZone(zone).toInstant().toEpochMilli()
        runBlocking { testDb.db.taskDao().insert(Task(title = "Ligar", dueDate = due.toEpochDay(), reminderAt = reminderAt)) }

        assertEquals(NextAppAlarm(reminderAt, AppAlarmKind.TASK), nextAlarm(routines = routines))
    }

    // Casos de borda (Lince, v1.7.1 E3): o diagnóstico usa os mesmos filtros dos agendadores.

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `tarefa sem repeticao ja concluida ou com lembrete que ja passou nao conta`() {
        disableSummary()
        val today = LocalDate.of(2026, 11, 16).toEpochDay()
        runBlocking {
            val dao = testDb.db.taskDao()
            // Lembrete daqui a 2h, mas concluída hoje cedo: encerrada, o agendador não a reagenda.
            val done = dao.insert(Task(title = "Concluída", dueDate = today, reminderAt = at(16, 10)))
            testDb.db.completionLogDao().insert(CompletionLog(taskId = done, completedAt = at(16, 7)))
            // Lembrete único de 1h atrás: já tocou.
            dao.insert(Task(title = "Passada", dueDate = today, reminderAt = at(16, 7)))
        }

        assertEquals(null, nextAlarm())
    }

    @Test
    fun `tarefa diaria com ancora no passado mostra a proxima ocorrencia, estritamente depois de agora`() {
        disableSummary()
        // Âncora 10/11 às 08:00 e agora é 16/11 às 08:00 em ponto: a de hoje já é "agora", vale a de amanhã.
        runBlocking {
            testDb.db.taskDao().insert(
                Task(
                    title = "Remédio",
                    dueDate = LocalDate.of(2026, 11, 10).toEpochDay(),
                    reminderAt = at(10, 8),
                    repeat = ReminderRepeat.DAILY,
                ),
            )
        }

        assertEquals(NextAppAlarm(at(17, 8), AppAlarmKind.TASK), nextAlarm())
    }

    @Test
    fun `rotina pausada nao conta e o dia pulado passa o aviso para a proxima ocorrencia`() {
        disableSummary()
        val routines = routineRepository()
        runBlocking {
            routines.add(Routine(title = "Pausada", startMinute = 9 * 60, remind = true, createdAt = 0, pausedAt = 1))
            val treino = routines.add(Routine(title = "Treino", startMinute = 10 * 60, remind = true, createdAt = 0))
            testDb.db.routineOverrideDao().insert(
                RoutineOverride(
                    routineId = treino.id,
                    epochDay = LocalDate.of(2026, 11, 16).toEpochDay(),
                    type = RoutineOverrideType.SKIP,
                ),
            )
        }

        assertEquals(NextAppAlarm(at(17, 10), AppAlarmKind.ROUTINE), nextAlarm(routines = routines))
    }

    @Test
    fun `rotina que comeca exatamente agora fica para o dia seguinte`() {
        disableSummary()
        val routines = routineRepository()
        runBlocking { routines.add(Routine(title = "Café", startMinute = 8 * 60, remind = true, createdAt = 0)) }

        assertEquals(NextAppAlarm(at(17, 8), AppAlarmKind.ROUTINE), nextAlarm(routines = routines))
    }

    // D-71-1 (v1.7.1): repetida já concluída no dia não é entregue (o catch-up a descarta), então o
    // diagnóstico mostra a ocorrência seguinte, ainda que o agendador tenha marcado a do dia.

    /** Diária às 10:00 com âncora em 10/11; devolve o id. */
    private fun insertDaily(title: String = "Remédio"): Long = runBlocking {
        testDb.db.taskDao().insert(
            Task(title = title, dueDate = LocalDate.of(2026, 11, 10).toEpochDay(), reminderAt = at(10, 10), repeat = ReminderRepeat.DAILY),
        )
    }

    @Test
    fun `diaria concluida hoje antes do horario mostra a de amanha`() {
        disableSummary()
        val id = insertDaily()
        runBlocking { testDb.db.completionLogDao().insert(CompletionLog(taskId = id, completedAt = at(16, 7, 30))) }

        assertEquals(NextAppAlarm(at(17, 10), AppAlarmKind.TASK), nextAlarm())
    }

    @Test
    fun `diaria concluida ontem mostra a de hoje`() {
        disableSummary()
        val id = insertDaily()
        runBlocking { testDb.db.completionLogDao().insert(CompletionLog(taskId = id, completedAt = at(15, 10, 5))) }

        assertEquals(NextAppAlarm(at(16, 10), AppAlarmKind.TASK), nextAlarm())
    }

    @Test
    fun `desfeita a conclusao de hoje a diaria volta a mostrar a de hoje`() {
        disableSummary()
        val id = insertDaily()
        val tasks = taskRepository()
        val logId = runBlocking { tasks.logCompletion(id, completedAt = at(16, 7, 30)) }
        assertEquals(NextAppAlarm(at(17, 10), AppAlarmKind.TASK), nextAlarm(tasks = tasks))

        runBlocking { tasks.undoCompletion(logId) }

        assertEquals(NextAppAlarm(at(16, 10), AppAlarmKind.TASK), nextAlarm(tasks = tasks))
    }

    @Test
    fun `tarefa unica nao muda - mostra o proprio lembrete`() {
        disableSummary()
        // Única para amanhã, concluída (adiantada) hoje sem reescrever a data: não está encerrada e
        // o dia concluído não a afeta (o predicado só vale para as repetidas).
        val id = runBlocking {
            testDb.db.taskDao().insert(Task(title = "Única", dueDate = LocalDate.of(2026, 11, 17).toEpochDay(), reminderAt = at(17, 9)))
        }
        runBlocking { testDb.db.completionLogDao().insert(CompletionLog(taskId = id, completedAt = at(16, 7))) }

        assertEquals(NextAppAlarm(at(17, 9), AppAlarmKind.TASK), nextAlarm())
    }

    @Test
    fun `diaria concluida no dia do alarme marcado diverge do sistema de proposito`() {
        disableSummary()
        // Relógio real (o ReminderScheduler agenda a partir de System.currentTimeMillis): âncora
        // depois de amanhã, para nunca cruzar a meia-noite durante o teste.
        val first = LocalDate.now(zone).plusDays(2)
        val firstAt = first.atTime(LocalTime.of(10, 0)).atZone(zone).toInstant().toEpochMilli()
        val id = runBlocking {
            testDb.db.taskDao().insert(Task(title = "Remédio", dueDate = first.toEpochDay(), reminderAt = firstAt, repeat = ReminderRepeat.DAILY))
        }
        // Conclusão gravada no próprio dia da primeira ocorrência (antes do horário).
        val doneAt = first.atTime(LocalTime.of(7, 0)).atZone(zone).toInstant().toEpochMilli()
        runBlocking { testDb.db.completionLogDao().insert(CompletionLog(taskId = id, completedAt = doneAt)) }
        val realNow = System.currentTimeMillis()
        val tasks = taskRepository(ReminderScheduler(context))
        runBlocking { tasks.rescheduleReminders(realNow) }

        // O agendador (que não muda) marca a ocorrência concluída; a entrega a descartaria.
        assertEquals(firstAt, scheduledAt(com.kalendrys.app.reminder.ReminderReceiver::class.java, action = null))
        val following = first.plusDays(1).atTime(LocalTime.of(10, 0)).atZone(zone).toInstant().toEpochMilli()
        assertEquals(NextAppAlarm(following, AppAlarmKind.TASK), nextAlarm(tasks = tasks, clock = { realNow }))
    }

    @Test
    fun `rotina com aviso concluida hoje antes do inicio mostra a de amanha e desfeita volta`() {
        disableSummary()
        val routines = routineRepository()
        val treino = runBlocking { routines.add(Routine(title = "Treino", startMinute = 10 * 60, remind = true, createdAt = 0)) }
        val today = LocalDate.of(2026, 11, 16)
        runBlocking { routines.complete(treino.id, today, today = today) }

        assertEquals(NextAppAlarm(at(17, 10), AppAlarmKind.ROUTINE), nextAlarm(routines = routines))

        runBlocking { routines.uncomplete(treino.id, today) }

        assertEquals(NextAppAlarm(at(16, 10), AppAlarmKind.ROUTINE), nextAlarm(routines = routines))
    }

    @Test
    fun `inicio com duas rotinas que avisam e so uma concluida continua valendo`() {
        disableSummary()
        val routines = routineRepository()
        val today = LocalDate.of(2026, 11, 16)
        runBlocking {
            val treino = routines.add(Routine(title = "Treino", startMinute = 10 * 60, remind = true, createdAt = 0))
            routines.add(Routine(title = "Leitura", startMinute = 10 * 60, remind = true, createdAt = 0))
            routines.complete(treino.id, today, today = today)
        }

        assertEquals(NextAppAlarm(at(16, 10), AppAlarmKind.ROUTINE), nextAlarm(routines = routines))
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
