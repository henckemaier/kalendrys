package com.kalendrys.app.ui.navigation

import android.content.Context
import android.os.Looper
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.UndoOffers
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.repository.ExerciseRepository
import com.kalendrys.app.data.repository.RoutineCategoryRepository
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.SystemAppClock
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.data.repository.TodayRepository
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.testRoutineRepository
import com.kalendrys.app.ui.calendar.CalendarScreen
import com.kalendrys.app.ui.calendar.CalendarViewModel
import com.kalendrys.app.ui.components.LocalAppSnackbarHostState
import com.kalendrys.app.ui.routineeditor.ROUTINE_ID_ARG
import com.kalendrys.app.ui.routineeditor.RoutineEditorScreen
import com.kalendrys.app.ui.routineeditor.RoutineEditorViewModel
import com.kalendrys.app.ui.routines.AllRoutinesScreen
import com.kalendrys.app.ui.routines.AllRoutinesViewModel
import com.kalendrys.app.ui.theme.KalendrysTheme
import com.kalendrys.app.ui.today.TodayScreen
import com.kalendrys.app.ui.today.TodayViewModel
import java.time.DayOfWeek
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * P8 (v1.8.0): "Excluir rotina" no editor aberto a partir da Hoje, de Todas as rotinas e do
 * Calendário. O editor exclui, oferece pelo objeto do app e fecha; a mensagem "Rotina excluída"
 * aparece na tela de origem (como no KalendrysNavHost: um coletor só, fora das telas) e Desfazer
 * devolve a rotina e o alarme.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-port")
class RoutineDeleteFromEditorTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var alarms: FakeRoutineAlarms
    private lateinit var routines: RoutineRepository
    private lateinit var tasks: TaskRepository
    private lateinit var offers: UndoOffers
    // Escopo do "app" para o restauro, drenado pelo testDb.close() antes de fechar o banco.
    private lateinit var appScope: CoroutineScope
    private var routineId = 0L

    @Before
    fun setup() {
        testDb = ViewModelTestDatabase(context)
        appScope = testDb.appScope()
        val db = testDb.db
        alarms = FakeRoutineAlarms(context)
        routines = testRoutineRepository(db, alarms)
        tasks = TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao(), clock = SystemAppClock)
        offers = UndoOffers(tasks, routines, appScope, clock = SystemAppClock)
        routineId = runBlocking { routines.add(Routine(title = "Treino", startMinute = 23 * 60)).id }
    }

    @After
    fun tearDown() {
        testDb.close()
    }

    private fun editorViewModel() = testDb.track(
        RoutineEditorViewModel(
            SavedStateHandle(mapOf(ROUTINE_ID_ARG to routineId)),
            routines,
            RoutineCategoryRepository(testDb.db),
            ShoppingRepository(testDb.db),
            ExerciseRepository(testDb.db.exerciseItemDao()),
            offers,
            testDb.appScope(),
            clock = SystemAppClock,
        ),
    )

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MS) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(20)
        condition()
    }

    private fun archivedAt(): Long? = runBlocking { testDb.db.routineDao().byId(routineId)?.archivedAt }

    /**
     * Abre o editor por cima de [origin]; ao fechar, volta para a origem. Exclui pelo menu, espera a
     * mensagem na origem, toca em Desfazer e confere a rotina e o alarme de volta.
     */
    private fun deleteFromEditorOver(origin: @Composable () -> Unit) {
        val editor = editorViewModel()
        var onEditor by mutableStateOf(true)
        compose.setContent {
            KalendrysTheme {
                val app = remember { SnackbarHostState() }
                CompositionLocalProvider(LocalAppSnackbarHostState provides app) {
                    UndoOfferEffect(offers, app)
                    if (onEditor) RoutineEditorScreen(onBack = { onEditor = false }, viewModel = editor) else origin()
                }
            }
        }
        waitFor { compose.onAllNodes(hasText("Treino")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithContentDescription("Mais opções").performClick()
        compose.onNode(hasText("Excluir rotina") and hasClickAction()).performClick()

        waitFor { !onEditor && compose.onAllNodes(hasText("Rotina excluída")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("excluída", archivedAt() != null)

        alarms.reset()
        compose.onNode(hasText("Desfazer") and hasClickAction()).performClick()
        waitFor { archivedAt() == null && alarms.calls.isNotEmpty() }
        assertNull(offers.pending.value)
        // A única rotina (às 23:00, avisa) volta: o aviso é agendado de novo.
        assertEquals("o alarme volta a contar a rotina", 23, alarms.calls.last().remindAt?.hour)
    }

    @Test
    fun `excluir pelo editor aberto a partir da Hoje mostra a mensagem na Hoje e Desfazer devolve`() {
        val today = testDb.track(
            run {
                val shopping = ShoppingRepository(testDb.db)
                TodayViewModel(
                    routines,
                    tasks,
                    DayOfWeek.MONDAY,
                    shopping,
                    TodayRepository(tasks, routines, shopping, clock = SystemAppClock),
                    offers,
                    clock = SystemAppClock,
                )
            },
        )
        deleteFromEditorOver {
            TodayScreen(
                onOpenRoutine = {},
                onNewRoutine = {},
                onOpenAllRoutines = {},
                onOpenSettings = {},
                onOpenTask = {},
                onOpenException = { _, _ -> },
                viewModel = today,
            )
        }
    }

    @Test
    fun `excluir pelo editor aberto a partir de Todas as rotinas mostra a mensagem la e Desfazer devolve`() {
        val all = testDb.track(AllRoutinesViewModel(routines, RoutineCategoryRepository(testDb.db), offers))
        deleteFromEditorOver { AllRoutinesScreen(onBack = {}, onOpenRoutine = {}, onNewRoutine = {}, viewModel = all) }
    }

    @Test
    fun `excluir pelo editor aberto a partir do Calendario mostra a mensagem la e Desfazer devolve`() {
        val calendar = testDb.track(CalendarViewModel(tasks, routines, ShoppingRepository(testDb.db), clock = SystemAppClock))
        deleteFromEditorOver { CalendarScreen(onOpenTask = {}, onOpenRoutine = {}, onOpenSettings = {}, viewModel = calendar) }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
