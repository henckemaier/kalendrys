package com.kalendrys.app.ui.routineeditor

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.UndoOffers
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.repository.ExerciseRepository
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.SavedExerciseRepository
import com.kalendrys.app.data.repository.SystemAppClock
import com.kalendrys.app.domain.ExerciseFields
import com.kalendrys.app.domain.RoutineChange
import com.kalendrys.app.domain.RoutineKind
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.TEST_CREATED_AT
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.testRoutineRepository
import com.kalendrys.app.testutil.testUndoOffers
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Casos extras da T2 da v1.10.0 (tipo da rotina, exercícios na criação, Duplicar; ADR-0002 16.10.5),
 * além do [RoutineEditorViewModelTest]: Exercício → Nenhum → Reverter, Duplicar de rotina Nenhum com
 * exercícios guardados, modelos trocando o tipo com exercícios no rascunho, a transação da criação com
 * exercícios, e as categorias fora das telas.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoutineKindExtrasTest {

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var db: AppDatabase
    private lateinit var alarms: FakeRoutineAlarms
    private lateinit var routines: RoutineRepository
    private lateinit var exercises: ExerciseRepository
    private lateinit var saved: SavedExerciseRepository
    private lateinit var undoOffers: UndoOffers
    private val viewModels = mutableListOf<RoutineEditorViewModel>()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        db = testDb.db
        alarms = FakeRoutineAlarms(context)
        routines = testRoutineRepository(db, alarms)
        exercises = ExerciseRepository(db.exerciseItemDao(), clock = SystemAppClock)
        saved = SavedExerciseRepository(db.savedExerciseDao(), clock = SystemAppClock)
        undoOffers = testUndoOffers(testDb, routines)
    }

    @After
    fun tearDown() {
        runBlocking { viewModels.forEach { withTimeoutOrNull(TIMEOUT_MS) { it.awaitPendingWrites() } } }
        testDb.close()
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(routineId: Long = NEW_ROUTINE_ID): RoutineEditorViewModel {
        val vm = testDb.track(
            RoutineEditorViewModel(
                SavedStateHandle(mapOf(ROUTINE_ID_ARG to routineId)),
                routines,
                exercises,
                saved,
                undoOffers,
                testDb.appScope(),
                clock = SystemAppClock,
            ),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        viewModels += vm
        return vm
    }

    private suspend fun eventually(condition: suspend () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(TIMEOUT_MS) { while (!condition()) delay(10) }
    }

    private suspend fun RoutineEditorViewModel.awaitUi(
        predicate: (RoutineEditorViewModel.UiState) -> Boolean,
    ): RoutineEditorViewModel.UiState = withContext(Dispatchers.Default) { withTimeout(TIMEOUT_MS) { uiState.first(predicate) } }

    private suspend fun RoutineEditorViewModel.awaitClosed() =
        withContext(Dispatchers.Default) { withTimeout(TIMEOUT_MS) { events.first() } }

    private suspend fun exerciseRoutine(kind: RoutineKind = RoutineKind.EXERCISE): Routine {
        val routine = routines.add(
            Routine(title = "Força", startMinute = 600, kind = kind.name, createdAt = TEST_CREATED_AT),
            exercises = listOf(ExerciseFields("Supino", 3, 10, "20kg", 60), ExerciseFields("Remada", 4, 8)),
        )
        return routine
    }

    @Test
    fun `edicao - Exercicio para Nenhum e Reverter volta o tipo e mostra os mesmos exercicios`() = runTest {
        val routine = exerciseRoutine()
        val originals = db.exerciseItemDao().byRoutine(routine.id)
        val vm = viewModel(routine.id)
        vm.awaitUi { it.exerciseItems.size == 2 }
        alarms.reset()

        vm.updateKind(RoutineKind.NONE)
        vm.awaitUi { !it.isExercise && it.exerciseItems.isEmpty() && it.canRevert }
        eventually { db.routineDao().byId(routine.id)!!.kind == RoutineKind.NONE.name }

        vm.revert()
        val back = vm.awaitUi { it.isExercise && it.exerciseItems.size == 2 && !it.canRevert }
        eventually { db.routineDao().byId(routine.id)!!.kind == RoutineKind.EXERCISE.name }

        assertEquals(originals, back.exerciseItems)
        assertEquals(originals, db.exerciseItemDao().byRoutine(routine.id))
        assertTrue("tipo não mexe em alarme, nem no Reverter", alarms.calls.isEmpty())
    }

    @Test
    fun `edicao - rotina Nenhum com exercicios guardados duplica Nenhum e com os exercicios`() = runTest {
        val routine = exerciseRoutine(kind = RoutineKind.NONE)
        val vm = viewModel(routine.id)
        val state = vm.awaitUi { it.fields != null && it.allExercises.size == 2 }
        assertTrue("escondidos com o tipo Nenhum", state.exerciseItems.isEmpty())

        vm.duplicate("Força (cópia)")
        vm.awaitClosed()

        val copy = db.routineDao().all().single { it.id != routine.id }
        assertEquals(RoutineKind.NONE.name, copy.kind)
        assertEquals(listOf("Supino", "Remada"), db.exerciseItemDao().byRoutine(copy.id).map { it.name })
        assertEquals(null, copy.shoppingListId)
    }

    @Test
    fun `criacao - modelos trocam o tipo e os exercicios do rascunho ficam, gravados com o tipo final`() = runTest {
        val vm = viewModel()
        vm.awaitUi { it.fields != null }
        vm.updateKind(RoutineKind.EXERCISE)
        vm.addExerciseItem("Flexão", 3, 15, null, null)
        vm.awaitUi { it.exerciseItems.size == 1 }

        vm.applyDraft(RoutineTemplate.READING.toDraft("Leitura"))
        val hidden = vm.awaitUi { !it.isExercise }
        assertTrue(hidden.exerciseItems.isEmpty())
        assertEquals(1, hidden.allExercises.size)

        vm.applyDraft(RoutineTemplate.WORKOUT.toDraft("Treino"))
        val shown = vm.awaitUi { it.isExercise && it.exerciseItems.size == 1 }
        assertEquals("Treino", shown.fields!!.title)

        vm.create()
        vm.awaitClosed()
        val created = db.routineDao().all().single()
        assertEquals(RoutineKind.EXERCISE.name, created.kind)
        assertEquals(listOf("Flexão"), db.exerciseItemDao().byRoutine(created.id).map { it.name })
    }

    @Test
    fun `criacao - so com exercicios sem nome nao grava exercicio nenhum`() = runTest {
        val routine = routines.add(
            Routine(title = "Vazia", startMinute = 600, kind = RoutineKind.EXERCISE.name, createdAt = TEST_CREATED_AT),
            exercises = listOf(ExerciseFields("  ", 3, 10), ExerciseFields("", 1, 1)),
        )
        assertTrue(db.exerciseItemDao().byRoutine(routine.id).isEmpty())
    }

    @Test
    fun `repositorio - rotina com exercicios que falha ao gravar nao deixa exercicio solto`() = runTest {
        val existing = routines.add(Routine(title = "Já existe", startMinute = 600, createdAt = TEST_CREATED_AT))
        try {
            // Mesmo id de uma rotina gravada: a inserção da rotina falha dentro da transação.
            routines.add(
                Routine(id = existing.id, title = "Colide", startMinute = 600, kind = RoutineKind.EXERCISE.name, createdAt = TEST_CREATED_AT),
                exercises = listOf(ExerciseFields("Supino", 3, 10)),
            )
            fail("a inserção com id repetido deveria falhar")
        } catch (expected: SQLiteConstraintException) {
            // ok
        }
        assertTrue("nenhum exercício gravado sem rotina", db.exerciseItemDao().all().isEmpty())
        assertEquals(listOf("Já existe"), db.routineDao().all().map { it.title })
    }

    @Test
    fun `repositorio - trocar o tipo de rotina que nao existe nao grava nem reagenda`() = runTest {
        alarms.reset()
        assertFalse(routines.edit(404, RoutineChange.Kind(RoutineKind.EXERCISE)))
        assertTrue(alarms.calls.isEmpty())
    }

    @Test
    fun `categorias fora das telas - nenhum codigo de UI usa categoria de rotina`() {
        val root = listOf(File("src/main/java/com/kalendrys/app/ui"), File("app/src/main/java/com/kalendrys/app/ui")).first { it.isDirectory }
        val uses = Regex("""\bRoutineCategor\w*|\broutineCategor\w*|\bcategoryId\b""")
        val found = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            val code = file.readText().replace(Regex("""/\*[\s\S]*?\*/"""), " ").replace(Regex("""//[^\n]*"""), " ")
            uses.findAll(code).map { "${file.relativeTo(root).invariantSeparatorsPath}: ${it.value}" }
        }.toList()
        assertEquals(emptyList<String>(), found)
        assertFalse("a pasta de telas de categorias saiu", File(root, "categories").exists())
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
