package com.kalendrys.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.UndoOffers
import com.kalendrys.app.domain.TaskChange
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.testRoutineRepository
import com.kalendrys.app.testutil.testTaskRepository
import com.kalendrys.app.testutil.clockOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
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
 * Casos extras da lista de UMA tarefa (v1.10.0, ADR-0002 16.10.3 e 16.10.4), além do
 * [TaskRepositoryTest] e do UndoOffersTest: Desfazer duas vezes a mesma exclusão, excluir e desfazer
 * em ciclo, tarefa com vínculo órfão, e o A1 da T3 (Adicionar lista com toque duplo,
 * concorrente, sobre vínculo órfão e em tarefa já excluída).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TaskListOwnershipExtrasTest {

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var tasks: TaskRepository
    private lateinit var shopping: ShoppingRepository

    @Before
    fun setup() {
        testDb = ViewModelTestDatabase(ApplicationProvider.getApplicationContext<Context>())
        tasks = testTaskRepository(testDb.db)
        shopping = ShoppingRepository(testDb.db, clock = SystemAppClock)
    }

    @After
    fun tearDown() = testDb.close()

    private suspend fun taskWithList(title: String = "Feira"): Pair<Long, Long> {
        val task = tasks.addTask(title, newListName = "Lista de $title")
        val listId = checkNotNull(task.shoppingListId)
        shopping.add(listId, "Arroz", quantity = 1, priceCents = null)
        shopping.add(listId, "Feijão", quantity = 2, priceCents = 500)
        return task.id to listId
    }

    private suspend fun snapshot() = Triple(
        testDb.db.taskDao().all(),
        testDb.db.shoppingListDao().all(),
        testDb.db.shoppingItemDao().all(),
    )

    /**
     * O segundo restauro da MESMA exclusão não duplica nada: a lista não volta duas vezes (defesa do
     * restoreTaskWithList) e a tarefa repetida é recusada pela chave, com a transação inteira desfeita.
     * OBS (Lince, v1.10.0 T1): hoje esse segundo restauro LANÇA (SQLiteConstraintException no insert da
     * tarefa); no app só não acontece porque a mensagem chama o Desfazer uma vez por oferta.
     */
    @Test
    fun `Desfazer duas vezes pelo repositorio - nada duplica e o banco fica como antes`() = runBlocking {
        val (id, _) = taskWithList()
        val before = snapshot()
        val deleted = checkNotNull(tasks.deleteTask(tasks.observeTask(id).first()!!))

        tasks.restoreTask(deleted)
        runCatching { tasks.restoreTask(deleted) }

        assertEquals(before, snapshot())
    }

    @Test
    fun `Desfazer duas vezes a mesma oferta - nada duplica`() = runBlocking {
        // O erro do segundo restauro (OBS acima) fica registrado aqui em vez de escapar da thread.
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val scope = testDb.appScope() + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> errors += e }
        val offers = UndoOffers(tasks, testRoutineRepository(testDb.db), scope, clockOf(now = { System.currentTimeMillis() }))
        val (id, _) = taskWithList()
        val before = snapshot()
        offers.deleteTask(tasks.observeTask(id).first()!!).join()
        val offer = checkNotNull(offers.pending.value)

        offers.undo(offer).join()
        // A oferta já foi limpa pelo primeiro; o segundo undo ainda dispara um restauro (ver a OBS acima).
        offers.undo(offer).join()

        assertNull(offers.pending.value)
        assertEquals(before, snapshot())
    }

    @Test
    fun `excluir e desfazer duas vezes seguidas devolve os mesmos ids nas duas`() = runBlocking {
        val (id, _) = taskWithList()
        val before = snapshot()
        repeat(2) {
            val deleted = checkNotNull(tasks.deleteTask(tasks.observeTask(id).first()!!))
            assertNull(testDb.db.shoppingListDao().byId(deleted.list!!.id))
            tasks.restoreTask(deleted)
            assertEquals("ciclo ${it + 1}", before, snapshot())
        }
    }

    @Test
    fun `tarefa com vinculo orfao exclui e desfaz sem lista e sem erro`() = runBlocking {
        val id = testDb.db.taskDao().insert(com.kalendrys.app.data.local.entity.Task(title = "Órfã", createdAt = 1, shoppingListId = 404))
        val deleted = checkNotNull(tasks.deleteTask(testDb.db.taskDao().byId(id)!!))
        assertNull(deleted.list)
        assertEquals(emptyList<Any>(), deleted.items)

        tasks.restoreTask(deleted)
        assertEquals(id, testDb.db.taskDao().byId(id)!!.id)
        assertEquals(emptyList<Any>(), testDb.db.shoppingListDao().all())
    }

    // A1 da T3 (ADR-0002 16.10.4): "Adicionar lista" cria na hora; não há mais lista existente.

    @Test
    fun `A1 - Adicionar lista duas vezes seguidas cria uma lista so`() = runBlocking {
        val task = tasks.addTask("Feira")

        assertTrue(tasks.edit(task.id, TaskChange.NewShoppingList("Feira")))
        val first = testDb.db.taskDao().byId(task.id)!!.shoppingListId
        assertFalse("o segundo toque não cria", tasks.edit(task.id, TaskChange.NewShoppingList("Feira")))

        assertEquals(first, testDb.db.taskDao().byId(task.id)!!.shoppingListId)
        assertEquals(listOf(first), testDb.db.shoppingListDao().all().map { it.id })
    }

    @Test
    fun `A1 - seis Adicionar lista ao mesmo tempo na mesma tarefa - uma lista, nenhuma sem tarefa, nenhuma falha`() = runBlocking {
        val task = tasks.addTask("Feira")

        val results = withContext(Dispatchers.Default) {
            (1..6).map { async { tasks.edit(task.id, TaskChange.NewShoppingList("Feira")) } }.awaitAll()
        }

        assertEquals(1, results.count { it })
        val lists = testDb.db.shoppingListDao().all()
        assertEquals(1, lists.size)
        assertEquals(lists.single().id, testDb.db.taskDao().byId(task.id)!!.shoppingListId)
    }

    @Test
    fun `A1 - vinculo orfao e substituido pela lista nova`() = runBlocking {
        val id = testDb.db.taskDao().insert(com.kalendrys.app.data.local.entity.Task(title = "Órfã", createdAt = 1, shoppingListId = 404))

        assertTrue(tasks.edit(id, TaskChange.NewShoppingList("Órfã")))

        val listId = testDb.db.taskDao().byId(id)!!.shoppingListId
        assertEquals(listOf(listId), testDb.db.shoppingListDao().all().map { it.id })
    }

    @Test
    fun `A1 - Adicionar lista em tarefa ja excluida nao cria lista sem tarefa`() = runBlocking {
        val task = tasks.addTask("Feira")
        tasks.deleteTask(task)

        assertFalse(tasks.edit(task.id, TaskChange.NewShoppingList("Feira")))
        assertEquals(emptyList<Any>(), testDb.db.shoppingListDao().all())
    }
}
