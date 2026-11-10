package com.kalendrys.app.ui.routineeditor

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.repository.ExerciseRepository
import com.kalendrys.app.data.repository.RoutineCategoryRepository
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.domain.RoutineCategoryKind
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.FakeRoutineNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Semântica do "Descartar alterações?" (T37/A3+B3): as edições de ITEM (compras/exercícios) que
 * persistem na hora passam a ser REVERTIDAS ao descartar — e MANTIDAS ao salvar. Room em memória +
 * coroutines-test + Turbine; o editor captura o snapshot de abertura na 1ª leitura da lista, então
 * cada teste coleta o [RoutineEditorViewModel.uiState] (Turbine) para dirigir e observar o fluxo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoutineEditorViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var routineRepository: RoutineRepository
    private lateinit var categoryRepository: RoutineCategoryRepository
    private lateinit var shoppingRepository: ShoppingRepository
    private lateinit var exerciseRepository: ExerciseRepository

    @Before
    fun setup() {
        // viewModelScope roda no Main; Unconfined executa as operações do editor na hora.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        routineRepository = RoutineRepository(
            db.routineDao(),
            db.routineCompletionDao(),
            db.routineOverrideDao(),
            FakeRoutineAlarms(context),
            FakeRoutineNotifications(context),
        )
        categoryRepository = RoutineCategoryRepository(db)
        shoppingRepository = ShoppingRepository(db.shoppingItemDao())
        exerciseRepository = ExerciseRepository(db.exerciseItemDao())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(routineId: Long) = RoutineEditorViewModel(
        SavedStateHandle(mapOf(ROUTINE_ID_ARG to routineId)),
        routineRepository,
        categoryRepository,
        shoppingRepository,
        exerciseRepository,
    )

    @Test
    fun `descartar reverte reordenacao, add, edicao e remocao de exercicio`() = runTest {
        val categoryId = categoryRepository.create("Academia", RoutineColor.GREEN.name, RoutineCategoryKind.EXERCICIO)
        val routine = routineRepository.add(Routine(title = "Treino", startMinute = 420, categoryId = categoryId))
        exerciseRepository.add(routine.id, "Agachamento", sets = 3, reps = 10, load = "40kg", restSeconds = 60)
        exerciseRepository.add(routine.id, "Supino", sets = 3, reps = 12, load = null, restSeconds = null)

        val vm = viewModel(routine.id)
        vm.uiState.test {
            // Snapshot de abertura.
            var state = awaitItem()
            while (state.exerciseItems.map { it.name } != listOf("Agachamento", "Supino")) state = awaitItem()
            assertFalse(state.hasChanges)

            // Reordena: desce o Agachamento → ordem vira [Supino, Agachamento].
            vm.moveExerciseItem(state.exerciseItems.first { it.name == "Agachamento" }, up = false)
            var moved = awaitItem()
            while (moved.exerciseItems.map { it.name } != listOf("Supino", "Agachamento")) moved = awaitItem()

            // Edita o Agachamento, adiciona o Terra e remove o Supino (referências frescas pós-reorder).
            vm.updateExerciseItem(
                moved.exerciseItems.first { it.name == "Agachamento" },
                name = "Agachamento livre", sets = 4, reps = 8, load = "50kg", restSeconds = 90,
            )
            vm.addExerciseItem("Terra", sets = 1, reps = 5, load = "80kg", restSeconds = 120)
            vm.removeExerciseItem(moved.exerciseItems.first { it.name == "Supino" })
            var changed = awaitItem()
            while (changed.exerciseItems.map { it.name } != listOf("Agachamento livre", "Terra")) changed = awaitItem()
            assertTrue("edições de item deveriam armar o descarte", changed.exerciseItemsChanged)
            assertTrue(changed.hasChanges)

            // Descartar reverte tudo ao snapshot: ordem, nome, campos — e some o Terra.
            vm.discardChanges()
            var restored = awaitItem()
            while (restored.exerciseItems.map { it.name } != listOf("Agachamento", "Supino")) restored = awaitItem()
            assertEquals(listOf(3, 3), restored.exerciseItems.map { it.sets })
            assertEquals("40kg", restored.exerciseItems.first().load)
            assertFalse(restored.exerciseItemsChanged)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `descartar reverte add, edicao e remocao de compras`() = runTest {
        val categoryId = categoryRepository.create("Mercado", RoutineColor.BLUE.name, RoutineCategoryKind.COMPRAS)
        val routine = routineRepository.add(Routine(title = "Compras", startMinute = 600, categoryId = categoryId))
        shoppingRepository.add(routine.id, "Arroz", quantity = 1, priceCents = 500)
        shoppingRepository.add(routine.id, "Feijão", quantity = 2, priceCents = 800)

        val vm = viewModel(routine.id)
        vm.uiState.test {
            var state = awaitItem()
            while (state.shoppingItems.map { it.name } != listOf("Arroz", "Feijão")) state = awaitItem()

            vm.updateShoppingItem(
                state.shoppingItems.first { it.name == "Arroz" },
                name = "Arroz integral", quantity = 3, priceCents = 650,
            )
            vm.addShoppingItem("Macarrão", quantity = 1, priceCents = 300)
            vm.removeShoppingItem(state.shoppingItems.first { it.name == "Feijão" })
            var changed = awaitItem()
            while (changed.shoppingItems.map { it.name } != listOf("Arroz integral", "Macarrão")) changed = awaitItem()
            assertTrue(changed.shoppingItemsChanged)

            vm.discardChanges()
            var restored = awaitItem()
            while (restored.shoppingItems.map { it.name } != listOf("Arroz", "Feijão")) restored = awaitItem()
            assertEquals(listOf(1, 2), restored.shoppingItems.map { it.quantity })
            assertEquals(listOf(500L, 800L), restored.shoppingItems.map { it.priceCents })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `salvar mantem as edicoes de item`() = runTest {
        val categoryId = categoryRepository.create("Academia", RoutineColor.GREEN.name, RoutineCategoryKind.EXERCICIO)
        val routine = routineRepository.add(Routine(title = "Treino", startMinute = 420, categoryId = categoryId))
        exerciseRepository.add(routine.id, "Agachamento", sets = 3, reps = 10, load = "40kg", restSeconds = 60)

        val vm = viewModel(routine.id)
        vm.uiState.test {
            var state = awaitItem()
            while (state.exerciseItems.map { it.name } != listOf("Agachamento")) state = awaitItem()

            vm.updateExerciseItem(
                state.exerciseItems.single(),
                name = "Agachamento livre", sets = 4, reps = 8, load = "50kg", restSeconds = 90,
            )
            vm.addExerciseItem("Terra", sets = 1, reps = 5, load = "80kg", restSeconds = 120)
            var changed = awaitItem()
            while (changed.exerciseItems.map { it.name } != listOf("Agachamento livre", "Terra")) changed = awaitItem()

            vm.save()
            cancelAndIgnoreRemainingEvents()
        }

        // Salvar fecha SEM reverter: as edições de item permanecem no banco.
        val persisted = exerciseRepository.observeItems(routine.id).first()
        assertEquals(listOf("Agachamento livre", "Terra"), persisted.map { it.name })
        assertEquals(4, persisted.first().sets)
        assertEquals("50kg", persisted.first().load)
    }

    @Test
    fun `marcar compra no carrinho nao conta como alteracao a descartar`() = runTest {
        val categoryId = categoryRepository.create("Mercado", RoutineColor.BLUE.name, RoutineCategoryKind.COMPRAS)
        val routine = routineRepository.add(Routine(title = "Compras", startMinute = 600, categoryId = categoryId))
        shoppingRepository.add(routine.id, "Arroz", quantity = 1, priceCents = 500)

        val vm = viewModel(routine.id)
        vm.uiState.test {
            var state = awaitItem()
            while (state.shoppingItems.size != 1) state = awaitItem()

            vm.toggleShoppingPicked(state.shoppingItems.single())
            var picked = awaitItem()
            while (!picked.shoppingItems.single().picked) picked = awaitItem()

            // Marcar no carrinho persiste na hora, mas NÃO arma o descarte (picked é ignorado).
            assertFalse(picked.shoppingItemsChanged)
            assertFalse(picked.hasChanges)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
