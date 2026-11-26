package com.kalendrys.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.R
import com.kalendrys.app.domain.TaskChange
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.testTaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * CRITÉRIO da T3 da v1.10.0 (ADR-0002 16.10.4): nenhum caminho produz lista sem tarefa. Cada caminho
 * que cria, apaga ou devolve lista é exercitado, sozinho e em corrida, e depois de cada um o banco é
 * conferido: toda lista tem exatamente UMA tarefa dona e nenhuma tarefa aponta para lista que não
 * existe sem que isso venha de antes (vínculo órfão de dado antigo).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ListAlwaysHasTaskTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var tasks: TaskRepository
    private lateinit var shopping: ShoppingRepository

    @Before
    fun setup() {
        testDb = ViewModelTestDatabase(context)
        tasks = testTaskRepository(testDb.db)
        shopping = ShoppingRepository(testDb.db, clock = SystemAppClock)
    }

    @After
    fun tearDown() = testDb.close()

    /** Toda lista com exatamente uma dona; devolve as listas, para o caso conferir a contagem. */
    private suspend fun assertEveryListHasOneTask(step: String): List<Long> {
        val lists = testDb.db.shoppingListDao().all().map { it.id }
        val owners = testDb.db.taskDao().all().mapNotNull { it.shoppingListId }
        for (listId in lists) {
            assertEquals("$step: lista $listId com ${owners.count { it == listId }} donas", 1, owners.count { it == listId })
        }
        return lists
    }

    @Test
    fun `cada caminho de lista deixa toda lista com uma tarefa`() = runBlocking {
        // Painel com "Adicionar lista": lista com o nome da tarefa, na mesma transação.
        val a = tasks.addTask("Feira", newListName = "Feira")
        assertEquals(1, assertEveryListHasOneTask("painel withList").size)
        assertEquals("Feira", testDb.db.shoppingListDao().byId(a.shoppingListId!!)!!.name)

        // Painel com nome em branco: nenhuma lista.
        tasks.addTask("Sem", newListName = "  ")
        assertEquals(1, assertEveryListHasOneTask("painel em branco").size)

        // Detalhe "Adicionar lista" numa tarefa sem lista.
        val b = tasks.addTask("Padaria")
        tasks.edit(b.id, TaskChange.NewShoppingList("Padaria"))
        assertEquals(2, assertEveryListHasOneTask("detalhe adicionar").size)

        // "Excluir lista": lista e vínculo saem juntos.
        val listB = testDb.db.taskDao().byId(b.id)!!.shoppingListId!!
        shopping.deleteList(listB)
        assertEquals(1, assertEveryListHasOneTask("excluir lista").size)
        assertEquals(null, testDb.db.taskDao().byId(b.id)!!.shoppingListId)

        // Excluir a tarefa leva a lista; Desfazer devolve as duas; Desfazer de novo não muda nada.
        val deleted = tasks.deleteTask(testDb.db.taskDao().byId(a.id)!!)!!
        assertEquals(0, assertEveryListHasOneTask("excluir tarefa").size)
        tasks.restoreTask(deleted)
        tasks.restoreTask(deleted)
        assertEquals(1, assertEveryListHasOneTask("desfazer duplo").size)
    }

    @Test
    fun `em corrida - adicionar e excluir lista e excluir a tarefa ao mesmo tempo nao deixam lista sem tarefa`() = runBlocking {
        repeat(20) { round ->
            val task = tasks.addTask("T$round")
            withContext(Dispatchers.Default) {
                listOf(
                    async { tasks.edit(task.id, TaskChange.NewShoppingList("T$round")) },
                    async { tasks.edit(task.id, TaskChange.NewShoppingList("T$round")) },
                    async { testDb.db.taskDao().byId(task.id)?.shoppingListId?.let { shopping.deleteList(it) } },
                    async { if (round % 2 == 0) tasks.deleteTask(task) },
                ).awaitAll()
            }
            assertEveryListHasOneTask("rodada $round")
        }
    }

    @Test
    fun `excluir lista que nao existe ou ja foi excluida nao cria nada nem falha`() = runBlocking {
        val task = tasks.addTask("Feira", newListName = "Feira")
        val listId = task.shoppingListId!!
        shopping.deleteList(listId)
        shopping.deleteList(listId)
        shopping.deleteList(404)
        assertEquals(0, assertEveryListHasOneTask("exclusão repetida").size)
    }

    @Test
    fun `atalho lists tem o rotulo Tarefas`() {
        assertEquals("Tarefas", context.getString(R.string.shortcut_tasks_short))
        val xml = context.resources.getXml(R.xml.shortcuts)
        var labelForLists: Int? = null
        while (xml.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "shortcut" &&
                xml.getAttributeValue("http://schemas.android.com/apk/res/android", "shortcutId") == "lists"
            ) {
                labelForLists = xml.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "shortcutShortLabel", 0)
            }
        }
        assertEquals(R.string.shortcut_tasks_short, labelForLists)
        assertTrue(context.getString(R.string.shortcut_tasks_long).contains("tarefas"))
    }
}
