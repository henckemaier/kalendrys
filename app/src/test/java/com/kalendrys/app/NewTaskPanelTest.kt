package com.kalendrys.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.NewTaskList
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.testTaskRepository
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Painel de nova tarefa do app (v1.8.0, E6): o rascunho com as regras do [NewTaskDraftStore] (fechar
 * guarda, Cancelar e Salvar descartam), salvar no escopo do app e a observação das listas só com
 * rascunho aberto.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NewTaskPanelTest {

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var tasks: TaskRepository
    private lateinit var shopping: ShoppingRepository

    /** Coletores ativos das listas (o Room só é lido enquanto houver algum). */
    private val listObservers = AtomicInteger()
    private lateinit var panel: NewTaskPanel

    private val today = LocalDate.now()

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        tasks = testTaskRepository(testDb.db)
        shopping = ShoppingRepository(testDb.db)
        panel = NewTaskPanel(tasks, shopping, testDb.appScope(), NewTaskDraftStore()) {
            shopping.observeSummaries()
                .onStart { listObservers.incrementAndGet() }
                .onCompletion { listObservers.decrementAndGet() }
        }
    }

    @After
    fun tearDown() = testDb.close()

    /** Espera [condition] em tempo real (o painel trabalha no escopo do app, em Dispatchers.Default). */
    private fun eventually(condition: suspend () -> Boolean) = runBlocking {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (!condition()) delay(10) }
        }
    }

    private fun allTasks() = runBlocking { testDb.db.taskDao().all() }

    @Test
    fun `abrir cria o rascunho na data sugerida`() {
        assertNull(panel.draft.value)
        panel.open(today)
        assertEquals(today, panel.draft.value!!.timing.date)
    }

    @Test
    fun `fechar e reabrir guarda o que foi digitado`() {
        panel.open(today)
        panel.update { it.copy(title = "Comprar pão") }
        // Fechar o painel não chama o objeto: o rascunho só fica.
        panel.open(today.plusDays(3))
        assertEquals("Comprar pão", panel.draft.value!!.title)
    }

    @Test
    fun `cancelar descarta sem gravar`() {
        panel.open(today)
        panel.update { it.copy(title = "Ligar") }

        panel.cancel()

        assertNull(panel.draft.value)
        runBlocking { delay(100) }
        assertTrue(allTasks().isEmpty())
    }

    @Test
    fun `salvar grava e descarta`() {
        panel.open(today)
        panel.update { it.copy(title = "  Pagar luz ", notes = "novembro") }

        val job = panel.save()

        assertNull("descarta na hora", panel.draft.value)
        runBlocking { job.join() }
        val saved = allTasks().single()
        assertEquals("Pagar luz", saved.title)
        assertEquals("novembro", saved.notes)
        assertNull(saved.shoppingListId)
        assertTrue("sem lista não cria lista", runBlocking { testDb.db.shoppingListDao().all() }.isEmpty())
    }

    @Test
    fun `salvar duas vezes seguidas grava uma tarefa so e cria a lista nova uma vez`() {
        panel.open(today)
        panel.update { it.copy(title = "Churrasco", list = NewTaskList.New("Churrasco")) }

        val first = panel.save()
        val second = panel.save()
        runBlocking { first.join(); second.join() }

        assertEquals(1, allTasks().size)
        assertEquals(1, runBlocking { testDb.db.shoppingListDao().all() }.size)
    }

    @Test
    fun `reabrir com outra data muda a data enquanto a pessoa nao escolheu, depois nao`() {
        val x = today.plusDays(1)
        val y = today.plusDays(5)
        panel.open(x)
        panel.open(y)
        assertEquals("sem escolha, vale a sugerida", y, panel.draft.value!!.timing.date)

        val chosen = today.plusDays(10)
        panel.update { it.withTiming(it.timing.copy(date = chosen)) }
        panel.open(x)
        assertEquals("a escolhida pela pessoa fica", chosen, panel.draft.value!!.timing.date)
    }

    @Test
    fun `titulo em branco nao grava e descarta`() {
        panel.open(today)
        panel.update { it.copy(title = "   ") }

        runBlocking { panel.save().join() }

        assertNull(panel.draft.value)
        assertTrue(allTasks().isEmpty())
    }

    @Test
    fun `sem rascunho salvar nao faz nada`() {
        runBlocking { panel.save().join() }
        assertTrue(allTasks().isEmpty())
    }

    @Test
    fun `salvar sobrevive a cancelar o escopo de quem pediu`() {
        panel.open(today)
        panel.update { it.copy(title = "Pagar luz") }
        val screen = CoroutineScope(Job())
        var job: Job? = null

        screen.launch(start = CoroutineStart.UNDISPATCHED) { job = panel.save() }
        screen.cancel()

        runBlocking { job!!.join() }
        assertEquals("Pagar luz", allTasks().single().title)
    }

    @Test
    fun `salvar com lista nova cria a lista junto com a tarefa`() {
        panel.open(today)
        panel.update { it.copy(title = "Churrasco", list = NewTaskList.New("Churrasco de sábado")) }

        runBlocking { panel.save().join() }

        val list = runBlocking { testDb.db.shoppingListDao().all() }.single()
        assertEquals("Churrasco de sábado", list.name)
        assertEquals(list.id, allTasks().single().shoppingListId)
    }

    @Test
    fun `salvar com lista existente valida o id e a excluida vira sem lista`() {
        val kept = runBlocking { shopping.createList("Mercado") }
        panel.open(today)
        panel.update { it.copy(title = "Mercado", list = NewTaskList.Existing(kept)) }
        runBlocking { panel.save().join() }
        assertEquals(kept, allTasks().single().shoppingListId)

        // Sem rascunho aberto não há observação: a lista sumida só é pega pela validação no salvar.
        val gone = NewTaskList.Existing(999)
        panel.open(today)
        panel.update { it.copy(title = "Órfã", timing = TaskTiming(date = null), list = gone) }
        runBlocking { panel.save().join() }
        assertNull(allTasks().single { it.title == "Órfã" }.shoppingListId)
    }

    @Test
    fun `lista excluida com o rascunho aberto some da escolha`() {
        val listId = runBlocking { shopping.createList("Feira") }
        panel.open(today)
        panel.update { it.copy(title = "Feira", list = NewTaskList.Existing(listId)) }
        eventually { listObservers.get() == 1 }
        assertEquals(NewTaskList.Existing(listId), panel.draft.value!!.list)

        runBlocking { shopping.deleteList(listId) }

        eventually { panel.draft.value!!.list == null }
        assertEquals("o resto do rascunho fica", "Feira", panel.draft.value!!.title)
    }

    @Test
    fun `sem rascunho nao observa listas, e para de observar ao descartar`() {
        runBlocking { delay(200) }
        assertEquals("nada lido sem rascunho", 0, listObservers.get())

        panel.open(today)
        eventually { listObservers.get() == 1 }
        panel.update { it.copy(title = "Digitando") }
        panel.open(today)
        runBlocking { delay(100) }
        assertEquals("mexer no rascunho não abre outra observação", 1, listObservers.get())

        panel.cancel()
        eventually { listObservers.get() == 0 }
        panel.open(today)
        panel.update { it.copy(title = "De novo") }
        panel.save()
        eventually { listObservers.get() == 0 }
    }
}
