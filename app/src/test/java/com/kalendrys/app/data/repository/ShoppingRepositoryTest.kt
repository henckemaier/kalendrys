package com.kalendrys.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.ShoppingProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ShoppingRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ShoppingRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = ShoppingRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun items(listId: Long) = repository.observeItems(listId).first()

    @Test
    fun `adicionar vai para o fim e edicao preserva os demais campos`() = runTest {
        val listId = repository.createList("  Mercado  ")
        assertEquals("Mercado", repository.list(listId)!!.name)
        repository.add(listId, "Arroz", quantity = 0, priceCents = 500)
        repository.add(listId, "Feijão", quantity = 2, priceCents = null)
        val arroz = items(listId).first()
        assertEquals(listOf(0, 1), items(listId).map { it.position })
        assertEquals(1, arroz.quantity) // mínimo 1

        repository.setPicked(arroz, true)
        repository.update(items(listId).first(), " Arroz integral ", quantity = 3, priceCents = 650)
        val edited = items(listId).first()
        assertEquals(arroz.id, edited.id)
        assertEquals("Arroz integral", edited.name)
        assertEquals(0, edited.position)
        assertTrue(edited.picked)
        assertEquals(arroz.createdAt, edited.createdAt)
    }

    @Test
    fun `reinserir apos remover mantem id e posicao`() = runTest {
        val listId = repository.createList("L")
        repository.add(listId, "A", 1, null)
        repository.add(listId, "B", 1, null)
        val removed = items(listId).first()
        repository.remove(removed)
        assertEquals(listOf("B"), items(listId).map { it.name })
        repository.reinsert(removed)
        assertEquals(listOf(removed.id, removed.id + 1), items(listId).map { it.id })
        assertEquals(listOf("A", "B"), items(listId).map { it.name })
    }

    @Test
    fun `resumo traz progresso, total e donos`() = runTest {
        val listId = repository.createList("Mercado")
        val avulsa = repository.createList("Avulsa")
        repository.add(listId, "Arroz", quantity = 2, priceCents = 500)
        repository.add(listId, "Sal", quantity = 1, priceCents = null)
        repository.setPicked(items(listId).first(), true)
        db.routineDao().insert(Routine(id = 1, title = "Feira", startMinute = 600, shoppingListId = listId))
        db.routineDao().insert(Routine(id = 2, title = "Arquivada", startMinute = 600, shoppingListId = listId, archivedAt = 1))
        db.taskDao().insert(Task(id = 3, title = "Comprar", shoppingListId = listId))

        val summaries = repository.observeSummaries().first()
        val main = summaries.first { it.id == listId }
        assertEquals(ShoppingProgress(picked = 1, total = 2), main.progress)
        assertEquals(1000L, main.totalCents)
        assertEquals(
            listOf(
                ShoppingListOwner(ShoppingListOwner.Kind.ROUTINE, 1, "Feira"),
                ShoppingListOwner(ShoppingListOwner.Kind.TASK, 3, "Comprar"),
            ),
            main.owners,
        )
        val empty = summaries.first { it.id == avulsa }
        assertEquals(ShoppingProgress.EMPTY, empty.progress)
        assertTrue(empty.owners.isEmpty())
        assertNull(repository.observeSummary(999).first())
    }

    @Test
    fun `excluir lista apaga itens e desvincula tarefas e rotinas`() = runTest {
        val listId = repository.createList("Mercado")
        val other = repository.createList("Outra")
        repository.add(listId, "Arroz", 1, null)
        repository.add(other, "Pão", 1, null)
        db.routineDao().insert(Routine(id = 1, title = "R", startMinute = 600, shoppingListId = listId, archivedAt = 1))
        db.taskDao().insert(Task(id = 2, title = "T", shoppingListId = listId))

        repository.deleteList(listId)

        assertNull(repository.list(listId))
        assertTrue(items(listId).isEmpty())
        assertEquals(1, items(other).size)
        assertNull(db.routineDao().byId(1)!!.shoppingListId)
        assertNull(db.taskDao().byId(2)!!.shoppingListId)
    }

    @Test
    fun `desmarcar todos zera as marcacoes`() = runTest {
        val listId = repository.createList("L")
        repository.add(listId, "A", 1, null)
        repository.add(listId, "B", 1, null)
        items(listId).forEach { repository.setPicked(it, true) }
        repository.clearPicked(listId)
        assertTrue(items(listId).none { it.picked })
    }

    @Test
    fun `reinicio reversivel faz uniao com marcacoes novas`() = runTest {
        val listId = repository.createList("L")
        repository.add(listId, "A", 1, null)
        repository.add(listId, "B", 1, null)
        repository.setPicked(items(listId).first(), true)

        repository.resetPicked(listId, "k1")
        assertTrue(items(listId).none { it.picked })
        assertEquals("k1", repository.list(listId)!!.lastResetKey)

        // Marca B depois do reinício; o desfazer devolve A e mantém B.
        repository.setPicked(items(listId)[1], true)
        repository.restoreReset("k1")
        assertEquals(listOf(true, true), items(listId).map { it.picked })
        assertTrue(items(listId).none { it.pickedBeforeReset })
        assertNull(repository.list(listId)!!.lastResetKey)
    }

    @Test
    fun `reinicio substituido torna o desfazer antigo no-op`() = runTest {
        val listId = repository.createList("L")
        repository.add(listId, "A", 1, null)
        repository.setPicked(items(listId).first(), true)
        repository.resetPicked(listId, "k1")
        repository.resetPicked(listId, "k2")

        repository.restoreReset("k1")
        assertTrue(items(listId).none { it.picked })
        assertEquals("k2", repository.list(listId)!!.lastResetKey)
    }
}
