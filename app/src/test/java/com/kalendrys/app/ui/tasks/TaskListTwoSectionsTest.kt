package com.kalendrys.app.ui.tasks

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.SystemAppClock
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.testTaskRepository
import com.kalendrys.app.testutil.testUndoOffers
import com.kalendrys.app.ui.theme.KalendrysTheme
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A2 da revisão da E4: com a P5, a repetida concluída adiantada está em "Concluídas hoje" E em
 * "Próximas" ao mesmo tempo. A chave do item leva a seção: as duas linhas aparecem e a lista não cai.
 * Tela alta para as duas seções estarem compostas.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-port")
class TaskListTwoSectionsTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val tasks = testTaskRepository(testDb.db)
        runBlocking {
            val weekly = tasks.addTask(
                "Treino semanal",
                timing = TaskTiming(date = LocalDate.now().plusDays(2), repeat = ReminderRepeat.WEEKLY),
            )
            tasks.logCompletion(weekly.id) // concluída adiantada: registro de hoje
        }
        val viewModel = testDb.track(
            TaskViewModel(tasks, ShoppingRepository(testDb.db, clock = SystemAppClock), testUndoOffers(testDb, tasks = tasks), clock = SystemAppClock),
        )
        compose.setContent {
            KalendrysTheme { TaskListScreen(onOpenTask = {}, onOpenSettings = {}, viewModel = viewModel) }
        }
    }

    @After
    fun tearDown() = testDb.close()

    @Test
    fun `a mesma tarefa em Concluidas hoje e em Proximas - duas linhas, a lista de pe`() {
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasText("Treino semanal", substring = true)).fetchSemanticsNodes().size >= 2
        }
        assertEquals(2, compose.onAllNodes(hasText("Treino semanal", substring = true)).fetchSemanticsNodes().size)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
