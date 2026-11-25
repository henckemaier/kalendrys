package com.kalendrys.app.data.repository

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.ShoppingList
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.TEST_CREATED_AT
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "Criar nova" lista no painel de nova tarefa (v1.8.0, E6): a lista e a tarefa se gravam numa
 * transação só ([com.kalendrys.app.data.local.dao.TaskDao.insertTaskWithNewList]), e a lista nasce
 * igual à da aba Listas ([ShoppingList.named], regra única).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TaskWithNewListTest {

    private lateinit var db: AppDatabase
    private lateinit var scheduler: FakeReminderScheduler
    private lateinit var repository: TaskRepository
    private lateinit var shopping: ShoppingRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scheduler = FakeReminderScheduler(context)
        repository = TaskRepository(db.taskDao(), db.completionLogDao(), scheduler, db.shoppingItemDao(), clock = SystemAppClock)
        shopping = ShoppingRepository(db, clock = SystemAppClock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `lista nova e tarefa se gravam juntas, com o vinculo`() = runTest {
        val task = repository.addTask("Feira", newListName = "Feira de sábado")

        val list = db.shoppingListDao().all().single()
        assertEquals("Feira de sábado", list.name)
        assertEquals(list.id, task.shoppingListId)
        assertEquals(task, db.taskDao().byId(task.id))
    }

    @Test
    fun `lista nova ignora o id de lista existente`() = runTest {
        val existing = shopping.createList("Antiga")

        val task = repository.addTask("Feira", shoppingListId = existing, newListName = "Nova")

        val created = db.shoppingListDao().all().single { it.name == "Nova" }
        assertEquals(created.id, task.shoppingListId)
    }

    @Test
    fun `tarefa com lista nova e lembrete agenda o alarme fora da transacao, como sempre`() = runTest {
        val task = repository.addTask(
            "Feira",
            timing = TaskTiming(date = LocalDate.now().plusDays(1), time = LocalTime.of(9, 0)),
            newListName = "Feira",
        )

        assertEquals(listOf(task.id), scheduler.scheduled.map { it.task.id })
        assertEquals(task.shoppingListId, scheduler.scheduled.single().task.shoppingListId)
    }

    @Test
    fun `lista criada pelos dois caminhos nasce igual`() = runTest {
        val byListsTab = db.shoppingListDao().byId(shopping.createList("  Feira  "))!!
        val byPanel = db.shoppingListDao().byId(repository.addTask("Tarefa", newListName = "  Feira  ").shoppingListId!!)!!

        assertEquals("Feira", byListsTab.name)
        // Tirando o que é de cada gravação (id e instante), são a mesma lista.
        assertEquals(byListsTab.copy(id = 0, createdAt = 0), byPanel.copy(id = 0, createdAt = 0))
        assertEquals(ShoppingList.named("  Feira  ", createdAt = TEST_CREATED_AT).copy(createdAt = 0), byPanel.copy(id = 0, createdAt = 0))
    }

    @Test
    fun `nome em branco nao cria lista e a tarefa nasce sem lista`() = runTest {
        val task = repository.addTask("Feira", newListName = "   ")

        assertTrue(db.shoppingListDao().all().isEmpty())
        assertNull(task.shoppingListId)
        assertEquals(task, db.taskDao().byId(task.id))
    }

    @Test
    fun `falha ao gravar a tarefa nao deixa lista avulsa`() = runTest {
        val existing = repository.addTask("Já existe")

        try {
            // Força a falha da tarefa DEPOIS da lista: id repetido viola a chave primária.
            db.taskDao().insertTaskWithNewList(ShoppingList.named("Feira", createdAt = TEST_CREATED_AT), Task(id = existing.id, title = "Duplicada", createdAt = TEST_CREATED_AT))
            fail("a tarefa duplicada devia falhar")
        } catch (expected: SQLiteConstraintException) {
            // A transação inteira volta.
        }

        assertTrue("sem lista avulsa", db.shoppingListDao().all().isEmpty())
        assertEquals(listOf(existing), db.taskDao().all())
    }
}
