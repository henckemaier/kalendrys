package com.kalendrys.app.ui.routineeditor

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.UndoOffers
import com.kalendrys.app.UndoTarget
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.repository.SystemAppClock
import com.kalendrys.app.domain.RoutineCategoryKind
import com.kalendrys.app.reminder.RoutineAlarms
import com.kalendrys.app.testutil.FakeRoutineNotifications
import com.kalendrys.app.testutil.InMemoryRoutineCatchUpMark
import com.kalendrys.app.testutil.TEST_CREATED_AT
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.kalendrys.app.data.repository.ExerciseRepository
import com.kalendrys.app.data.repository.RoutineCategoryRepository
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.domain.RoutineChange
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineDays
import com.kalendrys.app.domain.RoutineIcon
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.testRoutineRepository
import com.kalendrys.app.testutil.testUndoOffers
import java.time.DayOfWeek
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
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
 * Editor de rotina com salvar na hora (v1.9.0, ADR-0002 15.3 e 15.5). Criação: nada é gravado nem
 * agendado antes do "Criar rotina", que grava a rotina e a lista nova juntas e fecha. Edição: cada
 * campo vira uma mudança sobre a linha mais recente; pausar por fora não é desfeito; título vazio não
 * grava; sair grava o texto pendente; Reverter só mexe no que foi mexido na sessão.
 *
 * As gravações da edição rodam no escopo "do app" do teste, em outra thread: [eventually] espera o
 * banco chegar ao estado, com prazo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoutineEditorViewModelTest {

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var db: AppDatabase
    private lateinit var alarms: FakeRoutineAlarms
    private lateinit var routines: RoutineRepository
    private lateinit var shopping: ShoppingRepository
    private lateinit var exercises: ExerciseRepository
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
        shopping = ShoppingRepository(db, clock = SystemAppClock)
        exercises = ExerciseRepository(db.exerciseItemDao(), clock = SystemAppClock)
        undoOffers = testUndoOffers(testDb, routines)
    }

    @After
    fun tearDown() {
        // N7 da revisão da E3: a fila roda no escopo do app e o edit é NonCancellable. Espera as
        // gravações terminarem ANTES de fechar, em vez de cancelar o escopo por cima delas.
        runBlocking { viewModels.forEach { withTimeoutOrNull(TIMEOUT_MS) { it.awaitPendingWrites() } } }
        testDb.close()
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(
        routineId: Long = NEW_ROUTINE_ID,
        repository: RoutineRepository = routines,
    ): RoutineEditorViewModel {
        val vm = testDb.track(
            RoutineEditorViewModel(
                SavedStateHandle(mapOf(ROUTINE_ID_ARG to routineId)),
                repository,
                RoutineCategoryRepository(db),
                shopping,
                exercises,
                undoOffers,
                testDb.appScope(),
                clock = SystemAppClock,
            ),
        )
        // O estado é WhileSubscribed: mantém alguém assinando, como a tela.
        backgroundScope.launch { vm.uiState.collect {} }
        viewModels += vm
        return vm
    }

    /** Espera (no relógio de verdade, fora do tempo virtual) até [condition] valer. */
    private suspend fun eventually(condition: suspend () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(TIMEOUT_MS) { while (!condition()) delay(10) }
    }

    private suspend fun row(id: Long) = db.routineDao().byId(id)!!

    private suspend fun RoutineEditorViewModel.awaitLoaded() = eventually { uiState.value.fields != null }

    /**
     * Espera o estado da tela chegar a [predicate] e o devolve. O uiState é uma combinação de fluxos (alguns
     * do Room, em outra thread): ler o .value logo depois de uma ação pode ver o anterior.
     */
    private suspend fun RoutineEditorViewModel.awaitUi(
        predicate: (RoutineEditorViewModel.UiState) -> Boolean,
    ): RoutineEditorViewModel.UiState = withContext(Dispatchers.Default) { withTimeout(TIMEOUT_MS) { uiState.first(predicate) } }

    private suspend fun RoutineEditorViewModel.awaitClosed() =
        withContext(Dispatchers.Default) { withTimeout(TIMEOUT_MS) { events.first() } }

    private suspend fun saved(days: Int = RoutineDays.ALL) =
        routines.add(Routine(title = "Treino", startMinute = 600, durationMinutes = 30, daysOfWeek = days, createdAt = TEST_CREATED_AT))

    // --- Criação ------------------------------------------------------------------------------------

    @Test
    fun `criacao - nada gravado nem agendado antes do Criar rotina`() = runTest {
        val vm = viewModel()
        vm.awaitLoaded()
        alarms.reset()

        vm.updateTitle("Corrida")
        vm.updateStart(LocalTime.of(7, 0))
        vm.toggleDay(DayOfWeek.SUNDAY)
        vm.updateColor(RoutineColor.entries.last())
        vm.createShoppingList("Feira")

        assertTrue(db.routineDao().all().isEmpty())
        assertTrue(db.shoppingListDao().all().isEmpty())
        assertTrue(alarms.calls.isEmpty())
        val state = vm.awaitUi { it.pendingListName == "Feira" }
        assertTrue(state.hasDraftChanges)
    }

    @Test
    fun `criacao - Criar rotina grava rotina e lista nova juntas, agenda e fecha`() = runTest {
        val vm = viewModel()
        vm.awaitLoaded()
        vm.updateTitle("Feira")
        vm.createShoppingList(" Hortifruti ")

        vm.create()
        vm.awaitClosed()

        val routine = db.routineDao().all().single()
        val list = db.shoppingListDao().all().single()
        assertEquals("Feira", routine.title)
        assertEquals("Hortifruti", list.name)
        assertEquals(list.id, routine.shoppingListId)
        assertTrue(alarms.calls.isNotEmpty())
    }

    @Test
    fun `criacao - sem titulo nao cria`() = runTest {
        val vm = viewModel()
        vm.awaitLoaded()

        assertFalse(vm.uiState.value.canCreate)
        vm.create()

        assertTrue(db.routineDao().all().isEmpty())
    }

    @Test
    fun `criacao - o ultimo dia nao desmarca`() = runTest {
        val vm = viewModel()
        vm.awaitLoaded()
        vm.setDays(0b1) // só segunda
        vm.awaitUi { it.fields?.daysOfWeek == 0b1 }

        vm.toggleDay(DayOfWeek.MONDAY)
        vm.updateColor(RoutineColor.entries.last()) // marcador: o estado depois do toque

        assertEquals(0b1, vm.awaitUi { it.fields?.color == RoutineColor.entries.last() }.fields!!.daysOfWeek)
    }

    @Test
    fun `criacao - fim antes do inicio e inalcancavel e inicio depois do fim limpa o fim`() = runTest {
        val vm = viewModel()
        vm.awaitLoaded()
        vm.updateStartDate(100)

        vm.updateEndDate(50)
        assertNull(vm.uiState.value.fields!!.endEpochDay)

        vm.updateEndDate(150)
        vm.updateStartDate(200)
        val after = vm.awaitUi { it.fields?.startEpochDay == 200L }.fields!!
        assertNull(after.endEpochDay)
    }

    @Test
    fun `criacao - descartar fecha sem gravar nem criar lista`() = runTest {
        val vm = viewModel()
        vm.awaitLoaded()
        vm.updateTitle("Rascunho")
        vm.createShoppingList("Feira")

        vm.discardDraft()
        vm.awaitClosed()

        assertTrue(db.routineDao().all().isEmpty())
        assertTrue(db.shoppingListDao().all().isEmpty())
    }

    @Test
    fun `criacao - modelo mantem a lista ja escolhida`() = runTest {
        val listId = shopping.createList("Mercado")
        val vm = viewModel()
        vm.awaitLoaded()
        vm.attachShoppingList(listId)

        vm.applyDraft(RoutineDraft(title = "Treino", startMinute = 1080))

        assertEquals(listId, vm.awaitUi { it.fields?.shoppingListId == listId }.fields!!.shoppingListId)
    }

    // --- Edição -------------------------------------------------------------------------------------

    @Test
    fun `edicao - campos discretos gravam na hora`() = runTest {
        val routine = saved()
        val vm = viewModel(routine.id)
        vm.awaitLoaded()

        vm.updateColor(RoutineColor.entries.last())
        vm.updateIcon(RoutineIcon.entries.last())
        vm.updateStart(LocalTime.of(18, 15))
        vm.updateDuration(45)
        vm.updateRemind(false)
        vm.updateWeekInterval(3)

        eventually {
            val r = row(routine.id)
            r.color == RoutineColor.entries.last().name && r.icon == RoutineIcon.entries.last().name &&
                r.startMinute == 18 * 60 + 15 && r.durationMinutes == 45 && !r.remind && r.weekInterval == 3
        }
        assertFalse(vm.uiState.value.hasDraftChanges)
    }

    @Test
    fun `edicao - dois toques seguidos nos dias nao se anulam`() = runTest {
        val routine = saved(days = 0b1) // segunda
        val vm = viewModel(routine.id)
        vm.awaitLoaded()

        vm.toggleDay(DayOfWeek.TUESDAY)
        vm.toggleDay(DayOfWeek.WEDNESDAY)

        eventually { row(routine.id).daysOfWeek == 0b111 }
    }

    @Test
    fun `edicao - o ultimo dia nao desmarca`() = runTest {
        val routine = saved(days = 0b1)
        val vm = viewModel(routine.id)
        vm.awaitLoaded()

        vm.toggleDay(DayOfWeek.MONDAY)
        vm.updateColor(RoutineColor.entries.last()) // marcador: a fila passou do toque

        eventually { row(routine.id).color == RoutineColor.entries.last().name }
        assertEquals(0b1, row(routine.id).daysOfWeek)
    }

    @Test
    fun `edicao - pausar por fora com o editor aberto nao e desfeito pela edicao nem pelo Reverter`() = runTest {
        val routine = saved()
        val vm = viewModel(routine.id)
        vm.awaitLoaded()

        routines.pause(routine, now = 5_000L)
        vm.updateColor(RoutineColor.entries.last())
        eventually { row(routine.id).color == RoutineColor.entries.last().name }
        assertEquals(5_000L, row(routine.id).pausedAt)

        vm.revert()
        eventually { row(routine.id).color == routine.color }
        assertEquals(5_000L, row(routine.id).pausedAt)
    }

    @Test
    fun `edicao - titulo vazio nao grava e mostra o erro`() = runTest {
        val routine = saved()
        val vm = viewModel(routine.id)
        vm.awaitLoaded()

        vm.updateTitle("")
        vm.flushPending()
        vm.updateColor(RoutineColor.entries.last()) // marcador da fila
        eventually { row(routine.id).color == RoutineColor.entries.last().name }

        assertEquals("Treino", row(routine.id).title)
        assertTrue(vm.awaitUi { it.titleError }.titleError)
    }

    @Test
    fun `edicao - sair no meio da digitacao grava o texto`() = runTest {
        val routine = saved()
        val vm = viewModel(routine.id)
        vm.awaitLoaded()

        vm.updateTitle("Corrida")
        vm.updateNotes("leve")
        // Fechar a tela: o onCleared (finish) grava o pendente e fecha a fila.
        vm.finish()

        eventually { row(routine.id).title == "Corrida" && row(routine.id).notes == "leve" }
    }

    @Test
    fun `edicao - o texto do banco nao sobrescreve a digitacao pendente`() = runTest {
        val routine = saved()
        val vm = viewModel(routine.id)
        vm.awaitLoaded()

        vm.updateNotes("digitando")
        routines.edit(routine.id, RoutineChange.Notes("de fora"))

        // O banco reemitiu "de fora", mas o campo segue com a digitação pendente...
        assertEquals("digitando", vm.notesInput)
        // ...e, gravada, ela vence (é a última mudança da pessoa).
        vm.flushPending()
        eventually { row(routine.id).notes == "digitando" }
        assertEquals("digitando", vm.notesInput)
    }

    @Test
    fun `edicao - Reverter volta so os campos mexidos na sessao`() = runTest {
        val routine = saved()
        val vm = viewModel(routine.id)
        vm.awaitLoaded()

        vm.updateColor(RoutineColor.entries.last())
        vm.updateTitle("Corrida")
        vm.flushPending()
        eventually { row(routine.id).title == "Corrida" }
        // Mudança de FORA num campo que o editor não mexeu.
        routines.edit(routine.id, RoutineChange.Start(900))
        assertTrue(vm.awaitUi { it.canRevert }.canRevert)

        vm.revert()

        eventually { row(routine.id).color == routine.color && row(routine.id).title == "Treino" }
        assertEquals(900, row(routine.id).startMinute)
        assertFalse(vm.awaitUi { !it.canRevert }.canRevert)
        // O campo chega ao valor revertido; uma emissão atrasada do valor anterior pode passar antes
        // (o banco reemite em ordem, então o último é o revertido).
        eventually { vm.uiState.value.fields?.title == "Treino" }
    }

    @Test
    fun `edicao - lista existente, nova e desvincular`() = runTest {
        val routine = saved()
        val listId = shopping.createList("Mercado")
        val vm = viewModel(routine.id)
        vm.awaitLoaded()

        vm.attachShoppingList(listId)
        eventually { row(routine.id).shoppingListId == listId }

        vm.createShoppingList("Farmácia")
        eventually { db.shoppingListDao().all().size == 2 && row(routine.id).shoppingListId != listId }

        vm.detachShoppingList()
        eventually { row(routine.id).shoppingListId == null }
        assertEquals(2, db.shoppingListDao().all().size)
    }

    @Test
    fun `edicao - vinculo orfao equivale a sem lista`() = runTest {
        val routine = routines.add(Routine(title = "Feira", startMinute = 480, shoppingListId = 999, createdAt = TEST_CREATED_AT))
        val vm = viewModel(routine.id)
        vm.awaitLoaded()

        assertNull(vm.uiState.value.shoppingList)
    }

    @Test
    fun `edicao - excluir grava o pendente, arquiva, oferece Desfazer e fecha`() = runTest {
        val routine = saved()
        val vm = viewModel(routine.id)
        vm.awaitLoaded()
        eventually { vm.uiState.value.routine != null }

        vm.updateTitle("Treino novo")
        vm.delete()
        vm.awaitClosed()

        eventually { row(routine.id).archivedAt != null && row(routine.id).title == "Treino novo" }
        // A oferta sai logo depois de arquivar, no escopo do app: espera por ela também.
        eventually { undoOffers.pending.value != null }
        assertEquals(UndoTarget.DeletedRoutine(routine.id), undoOffers.pending.value?.target)
    }

    @Test
    fun `edicao - duplicar copia a linha gravada, sem lista nem pausa, e fecha`() = runTest {
        val listId = shopping.createList("Mercado")
        val routine = routines.add(Routine(title = "Treino", startMinute = 600, shoppingListId = listId, createdAt = TEST_CREATED_AT))
        routines.pause(routine, now = 1L)
        val vm = viewModel(routine.id)
        vm.awaitLoaded()

        vm.updateColor(RoutineColor.entries.last())
        vm.duplicate("Treino (cópia)")
        vm.awaitClosed()

        val copy = db.routineDao().all().single { it.id != routine.id }
        assertEquals("Treino (cópia)", copy.title)
        assertEquals(RoutineColor.entries.last().name, copy.color)
        assertNull(copy.shoppingListId)
        assertNull(copy.pausedAt)
    }

    @Test
    fun `edicao - pausar e retomar pelo menu`() = runTest {
        val routine = saved()
        val vm = viewModel(routine.id)
        vm.awaitLoaded()
        eventually { vm.uiState.value.routine != null }

        vm.togglePause()
        eventually { row(routine.id).pausedAt != null && vm.uiState.value.routine?.pausedAt != null }
        vm.togglePause()
        eventually { row(routine.id).pausedAt == null }
    }

    @Test
    fun `edicao - dois toques rapidos em pausar pausam e retomam`() = runTest {
        // N2 da revisão da E3: decide pela linha lida na hora, não pelo estado da tela.
        val routine = saved()
        val vm = viewModel(routine.id)
        vm.awaitLoaded()

        vm.togglePause()
        vm.togglePause()
        vm.awaitPendingWrites()

        assertNull(row(routine.id).pausedAt)
    }

    @Test
    fun `edicao - Reverter depois de criar lista nova deixa a lista avulsa`() = runTest {
        // N4 da revisão da E3: o vínculo volta ao do retrato; a lista criada continua na aba Listas.
        val routine = saved()
        val vm = viewModel(routine.id)
        vm.awaitLoaded()

        vm.createShoppingList("Feira")
        eventually { row(routine.id).shoppingListId != null }

        vm.revert()
        vm.awaitPendingWrites()

        assertNull(row(routine.id).shoppingListId)
        assertEquals(listOf("Feira"), db.shoppingListDao().all().map { it.name })
    }

    // --- NB-E3-B do Lince ---------------------------------------------------------------------------

    /** Repositório de rotinas cujo agendamento roda [onSchedule] (falhar ou segurar a criação). */
    private fun repositoryScheduling(onSchedule: () -> Unit): RoutineRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alarms = object : RoutineAlarms(context) {
            override fun schedule(remindAtMillis: Long?, refreshAtMillis: Long?) = onSchedule()
        }
        return RoutineRepository(
            db.routineDao(),
            db.routineCompletionDao(),
            db.routineOverrideDao(),
            alarms,
            FakeRoutineNotifications(context),
            db.shoppingItemDao(),
            InMemoryRoutineCatchUpMark(),
            clock = SystemAppClock,
        )
    }

    @Test
    fun `criacao - se Criar rotina falha o botao volta a valer`() = runTest {
        val vm = viewModel(repository = repositoryScheduling { error("agendamento falhou") })
        vm.awaitLoaded()
        vm.updateTitle("Corrida")

        vm.create()

        assertTrue(vm.awaitUi { !it.creating }.canCreate)
    }

    @Test
    fun `criacao - durante o criar nao ha descarte nem segundo criar, e o estado sobrevive a tela`() = runTest {
        // O ViewModel sobrevive a girar a tela: o "criando" vem dele, então a tela recriada o vê.
        val release = CountDownLatch(1)
        val vm = viewModel(repository = repositoryScheduling { release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS) })
        vm.awaitLoaded()
        vm.updateTitle("Corrida")

        vm.create()
        eventually { vm.uiState.value.creating }
        vm.create() // segundo toque
        assertTrue(vm.uiState.value.creating)
        assertTrue("a tela recriada desliga o descarte enquanto cria", vm.uiState.value.hasDraftChanges && vm.uiState.value.creating)

        release.countDown()
        vm.awaitClosed()
        assertEquals(1, db.routineDao().all().size)
    }

    @Test
    fun `edicao - duplicar com o titulo ainda na espera grava o titulo antes de copiar`() = runTest {
        val routine = saved()
        val vm = viewModel(routine.id)
        vm.awaitLoaded()

        vm.updateTitle("Treino novo") // sem flush manual: ainda nos 500 ms
        vm.duplicate("Treino novo (cópia)")
        vm.awaitClosed()

        assertEquals("Treino novo", row(routine.id).title)
        assertEquals(listOf("Treino novo (cópia)"), db.routineDao().all().filter { it.id != routine.id }.map { it.title })
    }

    @Test
    fun `edicao - toque duplo em excluir nao repete`() = runTest {
        val routine = saved()
        val vm = viewModel(routine.id)
        vm.awaitLoaded()

        vm.delete()
        vm.delete()
        vm.awaitClosed()
        vm.awaitPendingWrites()

        assertEquals(1, db.routineDao().all().size)
        // O arquivamento sai pela oferta do app, no escopo dela: espera por ele.
        eventually { row(routine.id).archivedAt != null }
    }

    @Test
    fun `edicao - exercicios na ordem do toque e Reverter devolve a lista da abertura`() = runTest {
        val categoryId = RoutineCategoryRepository(db).create("Força", RoutineColor.DEFAULT.name, RoutineCategoryKind.EXERCICIO)
        val routine = routines.add(Routine(title = "Força", startMinute = 600, categoryId = categoryId, createdAt = TEST_CREATED_AT))
        exercises.add(routine.id, "A", sets = 1, reps = 1, load = null, restSeconds = null)
        val vm = viewModel(routine.id)
        vm.awaitLoaded()
        eventually { vm.uiState.value.exerciseBaseline?.size == 1 }

        vm.addExerciseItem("B", 1, 1, null, null)
        vm.addExerciseItem("C", 1, 1, null, null)
        vm.addExerciseItem("X", 1, 1, null, null)
        vm.awaitPendingWrites()
        val staleX = db.exerciseItemDao().byRoutine(routine.id).single { it.name == "X" }
        vm.moveExerciseItem(staleX, up = true)
        vm.moveExerciseItem(staleX, up = true)
        vm.awaitPendingWrites()

        val items = db.exerciseItemDao().byRoutine(routine.id)
        assertEquals(listOf("A", "X", "B", "C"), items.map { it.name })
        assertEquals(4, items.map { it.position }.distinct().size)

        eventually { vm.uiState.value.exerciseItemsChanged }
        vm.revert()
        vm.awaitPendingWrites()

        assertEquals(listOf("A"), db.exerciseItemDao().byRoutine(routine.id).map { it.name })
    }

    // --- E3-A da Coruja: rajada de teclas no título ----------------------------------------------

    private val frase = "Mercado do bairro com a lista completa da semana"

    @Test
    fun `edicao - rajada no titulo sem esperar o banco - o campo tem cada tecla em ordem e grava o texto inteiro`() = runTest {
        val routine = saved()
        val vm = viewModel(routine.id)
        vm.awaitLoaded()

        vm.updateTitle("")
        frase.forEachIndexed { i, _ ->
            val digitado = frase.substring(0, i + 1)
            vm.updateTitle(digitado)
            // Síncrono: o campo lê o estado de composição, não o uiState.
            assertEquals("tecla ${i + 1}", digitado, vm.titleInput)
        }

        vm.flushPending()
        vm.awaitPendingWrites()
        eventually { row(routine.id).title == frase }
        assertEquals(frase, vm.titleInput)
    }

    @Test
    fun `edicao - rajada nas observacoes - o campo tem cada tecla em ordem`() = runTest {
        val routine = saved()
        val vm = viewModel(routine.id)
        vm.awaitLoaded()

        frase.forEachIndexed { i, _ ->
            vm.updateNotes(frase.substring(0, i + 1))
            assertEquals(frase.substring(0, i + 1), vm.notesInput)
        }
        vm.flushPending()
        vm.awaitPendingWrites()
        eventually { row(routine.id).notes == frase }
    }

    @Test
    fun `criacao - rajada no titulo - o campo tem cada tecla em ordem e o Criar grava o texto inteiro`() = runTest {
        val vm = viewModel()
        vm.awaitLoaded()

        frase.forEachIndexed { i, _ ->
            vm.updateTitle(frase.substring(0, i + 1))
            assertEquals(frase.substring(0, i + 1), vm.titleInput)
        }
        vm.create()
        vm.awaitClosed()

        assertEquals(listOf(frase), db.routineDao().all().map { it.title })
    }

    @Test
    fun `criacao - modelo troca tambem o texto do campo`() = runTest {
        val vm = viewModel()
        vm.awaitLoaded()
        vm.updateTitle("rascunho")

        vm.applyDraft(RoutineDraft(title = "Treino", notes = "aquecer", startMinute = 1080))

        assertEquals("Treino", vm.titleInput)
        assertEquals("aquecer", vm.notesInput)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
