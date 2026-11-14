package com.kalendrys.app.ui.routineeditor

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.ReceiveTurbine
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.kalendrys.app.testutil.ViewModelTestDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Semântica do "Descartar alterações?" (T37/A3+B3 e ADR-0001 P7): as edições de EXERCÍCIO que
 * persistem na hora são REVERTIDAS ao descartar e MANTIDAS ao salvar; o vínculo da lista de compras
 * é do rascunho (volta ao descartar), mas os itens da lista nunca são revertidos. Room em memória +
 * coroutines-test + Turbine; o editor captura o snapshot de abertura na 1ª leitura da lista, então
 * cada teste coleta o [RoutineEditorViewModel.uiState] (Turbine) para dirigir e observar o fluxo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoutineEditorViewModelTest {

    private lateinit var testDb: ViewModelTestDatabase
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
        testDb = ViewModelTestDatabase(context)
        db = testDb.db
        routineRepository = RoutineRepository(
            db.routineDao(),
            db.routineCompletionDao(),
            db.routineOverrideDao(),
            FakeRoutineAlarms(context),
            FakeRoutineNotifications(context),
            db.shoppingItemDao(),
        )
        categoryRepository = RoutineCategoryRepository(db)
        shoppingRepository = ShoppingRepository(db)
        exerciseRepository = ExerciseRepository(db.exerciseItemDao())
    }

    @After
    fun tearDown() {
        testDb.close() // cancela os ViewModels e drena o Room antes de fechar e do resetMain
        Dispatchers.resetMain()
    }

    private fun viewModel(routineId: Long) = testDb.track(
        RoutineEditorViewModel(
            SavedStateHandle(mapOf(ROUTINE_ID_ARG to routineId)),
            routineRepository,
            categoryRepository,
            shoppingRepository,
            exerciseRepository,
        ),
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
            // Espera o fim da operação (Event.Closed) antes de sair: sem isso a corrotina do
            // viewModelScope ainda volta ao Main durante o resetMain do tearDown (flaky).
            vm.events.first()
            var restored = awaitItem()
            while (restored.exerciseItems.map { it.name } != listOf("Agachamento", "Supino")) restored = awaitItem()
            assertEquals(listOf(3, 3), restored.exerciseItems.map { it.sets })
            assertEquals("40kg", restored.exerciseItems.first().load)
            assertFalse(restored.exerciseItemsChanged)
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
            // Espera o fim da operação (Event.Closed) antes de sair: sem isso a corrotina do
            // viewModelScope ainda volta ao Main durante o resetMain do tearDown (flaky).
            vm.events.first()
            cancelAndIgnoreRemainingEvents()
        }

        // Salvar fecha SEM reverter: as edições de item permanecem no banco.
        val persisted = exerciseRepository.observeItems(routine.id).first()
        assertEquals(listOf("Agachamento livre", "Terra"), persisted.map { it.name })
        assertEquals(4, persisted.first().sets)
        assertEquals("50kg", persisted.first().load)
    }

    // --- Lista de compras (ADR-0001, fatia 4): o vínculo é do rascunho; os itens são da lista. ---

    private suspend fun ReceiveTurbine<RoutineEditorViewModel.UiState>.awaitState(
        predicate: (RoutineEditorViewModel.UiState) -> Boolean,
    ): RoutineEditorViewModel.UiState {
        var state = awaitItem()
        while (!predicate(state)) state = awaitItem()
        return state
    }

    private suspend fun stored(id: Long): Routine = routineRepository.observeRoutine(id).filterNotNull().first()

    @Test
    fun `rotina de qualquer categoria mostra a lista anexada com resumo`() = runTest {
        // Sem categoria nenhuma (nada de COMPRAS): a lista aparece mesmo assim (P6).
        val listId = shoppingRepository.createList("Feira")
        shoppingRepository.add(listId, "Arroz", quantity = 2, priceCents = 500)
        val routine = routineRepository.add(Routine(title = "Sábado", startMinute = 600, shoppingListId = listId))

        viewModel(routine.id).uiState.test {
            val state = awaitState { it.shoppingList?.progress?.total == 1 }
            assertEquals("Feira", state.shoppingList?.name)
            assertEquals(1_000L, state.shoppingList?.totalCents)
            assertFalse(state.hasChanges)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `usar lista existente entra no rascunho e salvar grava o vinculo`() = runTest {
        val listId = shoppingRepository.createList("Mercado")
        val routine = routineRepository.add(Routine(title = "Compras", startMinute = 600))

        val vm = viewModel(routine.id)
        vm.uiState.test {
            val opened = awaitState { it.draft != null && it.availableLists.any { l -> l.id == listId } }
            assertNull(opened.shoppingList)

            vm.attachShoppingList(listId)
            val attached = awaitState { it.shoppingList?.id == listId }
            assertEquals(listId, attached.draft?.shoppingListId)
            assertTrue("anexar a lista é alteração do rascunho", attached.hasChanges)
            // Só ao salvar o vínculo vai para o banco.
            assertNull(stored(routine.id).shoppingListId)

            vm.save()
            // Espera o fim da operação (Event.Closed) antes de sair: sem isso a corrotina do
            // viewModelScope ainda volta ao Main durante o resetMain do tearDown (flaky).
            vm.events.first()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listId, stored(routine.id).shoppingListId)
    }

    @Test
    fun `descartar reverte o vinculo e nao toca nos itens`() = runTest {
        val original = shoppingRepository.createList("Original")
        shoppingRepository.add(original, "Arroz", quantity = 1, priceCents = null)
        val other = shoppingRepository.createList("Outra")
        val routine = routineRepository.add(Routine(title = "Compras", startMinute = 600, shoppingListId = original))

        val vm = viewModel(routine.id)
        vm.uiState.test {
            awaitState { it.shoppingList?.id == original }
            // Item adicionado na lista durante a sessão (persiste na hora, como na tela da lista).
            shoppingRepository.add(original, "Feijão", quantity = 1, priceCents = null)
            awaitState { it.shoppingList?.progress?.total == 2 }
            // Sem mexer no vínculo, itens não armam o descarte (P7).
            assertFalse(vm.uiState.value.hasChanges)

            vm.detachShoppingList()
            vm.attachShoppingList(other)
            assertTrue(awaitState { it.shoppingList?.id == other }.hasChanges)

            vm.discardChanges()
            // Espera o fim da operação (Event.Closed) antes de sair: sem isso a corrotina do
            // viewModelScope ainda volta ao Main durante o resetMain do tearDown (flaky).
            vm.events.first()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(original, stored(routine.id).shoppingListId)
        assertEquals(listOf("Arroz", "Feijão"), shoppingRepository.observeItems(original).first().map { it.name })
    }

    @Test
    fun `desvincular e salvar tira o vinculo mas a lista continua`() = runTest {
        val listId = shoppingRepository.createList("Feira")
        shoppingRepository.add(listId, "Arroz", quantity = 1, priceCents = null)
        val routine = routineRepository.add(Routine(title = "Feira", startMinute = 600, shoppingListId = listId))

        val vm = viewModel(routine.id)
        vm.uiState.test {
            awaitState { it.shoppingList != null }
            vm.detachShoppingList()
            val detached = awaitState { it.shoppingList == null }
            assertTrue(detached.hasChanges)
            vm.save()
            // Espera o fim da operação (Event.Closed) antes de sair: sem isso a corrotina do
            // viewModelScope ainda volta ao Main durante o resetMain do tearDown (flaky).
            vm.events.first()
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(stored(routine.id).shoppingListId)
        val summary = shoppingRepository.observeSummary(listId).first()
        assertEquals(1, summary?.progress?.total)
        assertTrue("sem dono, vira avulsa", summary?.owners.orEmpty().isEmpty())
    }

    @Test
    fun `rotina nova aceita lista criada na hora`() = runTest {
        val vm = viewModel(NEW_ROUTINE_ID)
        vm.uiState.test {
            awaitState { it.draft != null }
            vm.updateTitle("Feira de sábado")
            vm.createShoppingList("Feira de sábado")
            val state = awaitState { it.shoppingList != null }
            assertEquals("Feira de sábado", state.shoppingList?.name)
            assertTrue(state.hasChanges)
            vm.save()
            // Espera o fim da operação (Event.Closed) antes de sair: sem isso a corrotina do
            // viewModelScope ainda volta ao Main durante o resetMain do tearDown (flaky).
            vm.events.first()
            cancelAndIgnoreRemainingEvents()
        }
        val created = db.routineDao().all().single()
        val listId = shoppingRepository.observeLists().first().single().id
        assertEquals(listId, created.shoppingListId)
    }

    @Test
    fun `lista criada e descartada continua como avulsa`() = runTest {
        val vm = viewModel(NEW_ROUTINE_ID)
        vm.uiState.test {
            awaitState { it.draft != null }
            vm.createShoppingList("Rascunho")
            assertTrue(awaitState { it.shoppingList != null }.hasChanges)
            vm.discardChanges()
            // Espera o fim da operação (Event.Closed) antes de sair: sem isso a corrotina do
            // viewModelScope ainda volta ao Main durante o resetMain do tearDown (flaky).
            vm.events.first()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(db.routineDao().all().isEmpty())
        val summaries = shoppingRepository.observeSummaries().first()
        assertEquals(listOf("Rascunho"), summaries.map { it.name })
        assertTrue(summaries.single().owners.isEmpty())
    }

    @Test
    fun `aplicar modelo mantem a lista anexada`() = runTest {
        val listId = shoppingRepository.createList("Mercado")
        val vm = viewModel(NEW_ROUTINE_ID)
        vm.uiState.test {
            awaitState { it.draft != null }
            vm.attachShoppingList(listId)
            awaitState { it.shoppingList != null }
            vm.applyDraft(RoutineTemplate.READING.toDraft("Leitura"))
            val state = awaitState { it.draft?.title == "Leitura" }
            assertEquals(listId, state.draft?.shoppingListId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `duplicar nao leva o vinculo`() = runTest {
        val listId = shoppingRepository.createList("Feira")
        val routine = routineRepository.add(Routine(title = "Feira", startMinute = 600, shoppingListId = listId))

        val vm = viewModel(routine.id)
        vm.uiState.test {
            awaitState { it.routine != null && it.draft != null }
            vm.duplicate("Feira (cópia)")
            // Espera o fim da operação (Event.Closed) antes de sair: sem isso a corrotina do
            // viewModelScope ainda volta ao Main durante o resetMain do tearDown (flaky).
            vm.events.first()
            cancelAndIgnoreRemainingEvents()
        }
        val all = db.routineDao().all()
        assertEquals(listId, all.single { it.id == routine.id }.shoppingListId)
        assertNull(all.single { it.id != routine.id }.shoppingListId)
    }

    @Test
    fun `vinculo orfao equivale a sem lista`() = runTest {
        val routine = routineRepository.add(Routine(title = "Feira", startMinute = 600, shoppingListId = 999))

        viewModel(routine.id).uiState.test {
            val state = awaitState { it.draft != null }
            assertNull(state.shoppingList)
            assertFalse(state.hasChanges)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
