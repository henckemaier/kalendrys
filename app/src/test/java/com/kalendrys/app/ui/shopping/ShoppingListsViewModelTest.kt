package com.kalendrys.app.ui.shopping

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.ShoppingListOwner
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.domain.ShoppingProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Aba Listas (ADR-0001, fatia 3). Room em memória + coroutines-test + Turbine. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ShoppingListsViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ShoppingRepository

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ShoppingRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun ReceiveTurbine<ShoppingListsViewModel.UiState>.awaitUntil(
        predicate: (ShoppingListsViewModel.UiState) -> Boolean,
    ): ShoppingListsViewModel.UiState {
        var state = awaitItem()
        while (!predicate(state)) state = awaitItem()
        return state
    }

    @Test
    fun `sem listas carrega vazio`() = runTest {
        val vm = ShoppingListsViewModel(repository)
        vm.uiState.test {
            assertTrue(awaitUntil { it.isLoaded }.lists.isEmpty())
        }
    }

    @Test
    fun `resumos trazem progresso, total e donos`() = runTest {
        val mercado = repository.createList("Mercado")
        val avulsa = repository.createList("Farmácia")
        repository.add(mercado, "Arroz", quantity = 2, priceCents = 1_000)
        repository.add(mercado, "Feijão", quantity = 1, priceCents = 800)
        repository.setPicked(repository.observeItems(mercado).first().first(), true)
        db.routineDao().insert(Routine(title = "Compras do sábado", startMinute = 540, shoppingListId = mercado))

        val vm = ShoppingListsViewModel(repository)
        vm.uiState.test {
            val state = awaitUntil { s -> s.lists.size == 2 && s.lists.first().owners.isNotEmpty() }
            val first = state.lists.first { it.id == mercado }
            assertEquals(ShoppingProgress(picked = 1, total = 2), first.progress)
            assertEquals(2_800L, first.totalCents)
            assertEquals(listOf("Compras do sábado"), first.owners.map { it.title })
            assertEquals(ShoppingListOwner.Kind.ROUTINE, first.owners.single().kind)

            val standalone = state.lists.first { it.id == avulsa }
            assertTrue(standalone.owners.isEmpty())
            assertEquals(ShoppingProgress.EMPTY, standalone.progress)
        }
    }

    @Test
    fun `criar emite Created com o id da lista nova`() = runTest {
        val vm = ShoppingListsViewModel(repository)
        vm.events.test {
            vm.create("  Feira  ")
            val event = awaitItem() as ShoppingListsViewModel.Event.Created
            assertEquals("Feira", repository.list(event.listId)?.name)
        }
    }

    @Test
    fun `criar com nome em branco nao cria`() = runTest {
        val vm = ShoppingListsViewModel(repository)
        vm.events.test {
            vm.create("   ")
            expectNoEvents()
        }
        assertTrue(repository.observeLists().first().isEmpty())
    }

    @Test
    fun `renomear troca o nome`() = runTest {
        val listId = repository.createList("Mercado")
        val vm = ShoppingListsViewModel(repository)
        vm.uiState.test {
            awaitUntil { it.lists.isNotEmpty() }
            vm.rename(listId, "")
            vm.rename(listId, "Atacado")
            assertEquals("Atacado", awaitUntil { it.lists.single().name != "Mercado" }.lists.single().name)
        }
    }

    @Test
    fun `excluir apaga a lista e os itens e desvincula rotina e tarefa`() = runTest {
        val listId = repository.createList("Mercado")
        repository.add(listId, "Arroz", quantity = 1, priceCents = null)
        val routineId = db.routineDao().insert(Routine(title = "Compras", startMinute = 540, shoppingListId = listId))
        val taskId = db.taskDao().insert(Task(title = "Ir ao mercado", shoppingListId = listId))

        val vm = ShoppingListsViewModel(repository)
        vm.uiState.test {
            awaitUntil { s -> s.lists.singleOrNull()?.owners?.size == 2 }
            vm.delete(listId)
            assertTrue(awaitUntil { it.lists.isEmpty() }.lists.isEmpty())
        }
        assertNull(repository.list(listId))
        assertTrue(repository.observeItems(listId).first().isEmpty())
        assertNull(db.routineDao().byId(routineId)?.shoppingListId)
        assertNull(db.taskDao().byId(taskId)?.shoppingListId)
    }
}
