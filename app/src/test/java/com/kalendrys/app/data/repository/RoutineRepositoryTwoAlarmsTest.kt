package com.kalendrys.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCompletion
import com.kalendrys.app.domain.RoutineChange
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.FakeRoutineAlarms.Call
import com.kalendrys.app.testutil.FakeRoutineNotifications
import com.kalendrys.app.testutil.TEST_CREATED_AT
import com.kalendrys.app.testutil.clockOf
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * v1.9.1 (E5-B, ADR-0003 seção 8.4): os dois horários que o [RoutineRepository] entrega ao
 * [com.kalendrys.app.reminder.RoutineAlarms] (AVISO e WIDGETS) em cada caminho que reagenda.
 *
 * Regra conferida em todos: o aviso é o próximo início em que alguma rotina avisa e não foi concluída
 * no dia; o dos widgets é o próximo início de qualquer bloco, só se vem ANTES do aviso (ou não há
 * aviso). Nos mesmos dados, o diagnóstico ([RoutineRepository.nextRemindingStart]) dá o horário do aviso.
 *
 * Cenário base (segunda, 7/1/2030, fuso fixo de São Paulo, sem horário de verão em 2030): "Leitura"
 * às 8:30 sem aviso e "Treino" às 9:00 com aviso, todo dia; o relógio das alterações marca 8:00.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoutineRepositoryTwoAlarmsTest {

    private lateinit var db: AppDatabase
    private lateinit var alarms: FakeRoutineAlarms
    private lateinit var mark: RecordingMark
    private lateinit var repository: RoutineRepository

    /** Avisos entregues: (dia, id da rotina), na ordem. */
    private val delivered = mutableListOf<Pair<LocalDate, Long>>()

    private var zone: ZoneId = SAO_PAULO
    private var clock: () -> Long = { 0L }
    private val day: LocalDate = LocalDate.of(2030, 1, 7)

    /** Marca em memória que guarda cada gravação, para conferir que não anda para trás. */
    private class RecordingMark : RoutineCatchUpMark {
        val written = mutableListOf<Long>()

        override suspend fun get(): Long? = written.lastOrNull()

        override suspend fun set(millis: Long) {
            written += millis
        }
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        alarms = FakeRoutineAlarms(context, zone = { zone })
        mark = RecordingMark()
        clock = { at(8, 0) }
        repository = RoutineRepository(
            db.routineDao(),
            db.routineCompletionDao(),
            db.routineOverrideDao(),
            alarms,
            FakeRoutineNotifications(context),
            deliverDueBlocks = { routines, date -> synchronized(delivered) { routines.forEach { delivered += date to it.id } } },
            mark = mark,
            clock = clockOf(zone = { zone }) { clock() },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- Apoio ----------------------------------------------------------------------------------

    private fun at(hour: Int, minute: Int = 0, date: LocalDate = day): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun t(hour: Int, minute: Int = 0, date: LocalDate = day): LocalDateTime = date.atTime(hour, minute)

    private fun block(title: String, hour: Int, minute: Int = 0, remind: Boolean = true) =
        Routine(title = title, startMinute = hour * 60 + minute, remind = remind, createdAt = 0L)

    private suspend fun silent(hour: Int, minute: Int = 0) = repository.add(block("Leitura", hour, minute, remind = false))

    private suspend fun reminding(hour: Int, minute: Int = 0) = repository.add(block("Treino", hour, minute))

    private fun last(): Call = alarms.calls.last()

    private fun call(remindAt: LocalDateTime?, refreshAt: LocalDateTime?) = Call(remindAt = remindAt, refreshAt = refreshAt)

    /** O diagnóstico, nos mesmos dados e no mesmo "agora", mostra o horário do alarme de aviso agendado. */
    private suspend fun assertDiagnosticMatchesSchedule(now: Long) {
        repository.catchUp(nowOverride = now)
        // O instante que o repositório entregou ao RoutineAlarms, sem conversão no meio (OBS-1).
        val scheduled = alarms.millis.last().first
        assertEquals("diagnóstico = agendamento", scheduled, repository.nextRemindingStart(now))
    }

    // --- Composição dos dois alarmes ------------------------------------------------------------

    @Test
    fun `silenciosa antes da que avisa - aviso no horario dela e widgets no bloco sem aviso`() = runTest {
        silent(8, 30)
        reminding(9)

        assertEquals(call(remindAt = t(9), refreshAt = t(8, 30)), last())
    }

    @Test
    fun `so rotinas que avisam - nenhum alarme dos widgets`() = runTest {
        reminding(9)
        repository.add(block("Estudo", 10))

        assertEquals(call(remindAt = t(9), refreshAt = null), last())
    }

    @Test
    fun `so silenciosas - nenhum alarme de aviso e o dos widgets no proximo bloco`() = runTest {
        silent(8, 30)
        repository.add(block("Almoço", 12, remind = false))

        assertEquals(call(remindAt = null, refreshAt = t(8, 30)), last())
    }

    @Test
    fun `empate de horario entre silenciosa e a que avisa - um alarme so, o de aviso`() = runTest {
        silent(9)
        reminding(9)

        assertEquals(call(remindAt = t(9), refreshAt = null), last())
    }

    @Test
    fun `empate com a que avisa concluida no dia - aviso amanha e widgets no horario do empate`() = runTest {
        silent(9)
        val treino = reminding(9)

        repository.complete(treino.id, day)

        assertEquals(call(remindAt = t(9, date = day.plusDays(1)), refreshAt = t(9)), last())
    }

    @Test
    fun `a que avisa concluida hoje com a proxima amanha - silencioso hoje e aviso amanha`() = runTest {
        repository.add(block("Almoço", 12, remind = false))
        val treino = reminding(9)

        repository.complete(treino.id, day)
        // O início das 9:00 continua redesenhando os widgets; o aviso vai para amanhã.
        assertEquals(call(remindAt = t(9, date = day.plusDays(1)), refreshAt = t(9)), last())

        // O silencioso das 9:00 tocou: nada a avisar, o seguinte é o do almoço.
        repository.catchUp(nowOverride = at(9, 0))
        assertTrue(delivered.isEmpty())
        assertEquals(call(remindAt = t(9, date = day.plusDays(1)), refreshAt = t(12)), last())
    }

    // --- Cada alteração move o alarme de aviso (8.4, item 3) -------------------------------------

    @Test
    fun `concluir antes da hora move o aviso para a proxima que avisa e desmarcar devolve`() = runTest {
        silent(8, 30)
        val treino = reminding(9)
        repository.add(block("Estudo", 10))

        repository.complete(treino.id, day)
        assertEquals("o aviso vai para a próxima que avisa", call(remindAt = t(10), refreshAt = t(8, 30)), last())

        repository.uncomplete(treino.id, day)
        assertEquals(call(remindAt = t(9), refreshAt = t(8, 30)), last())
    }

    @Test
    fun `desligar o aviso tira o alarme de aviso e religar devolve`() = runTest {
        silent(8, 30)
        val treino = reminding(9)

        repository.edit(treino.id, RoutineChange.Remind(false))
        assertEquals("sem rotina que avise, sem despertador", call(remindAt = null, refreshAt = t(8, 30)), last())

        repository.edit(treino.id, RoutineChange.Remind(true))
        assertEquals(call(remindAt = t(9), refreshAt = t(8, 30)), last())
    }

    @Test
    fun `pausar tira o alarme de aviso e retomar devolve`() = runTest {
        silent(8, 30)
        val treino = reminding(9)

        repository.pause(treino, now = at(8, 0))
        assertEquals(call(remindAt = null, refreshAt = t(8, 30)), last())

        repository.resume(checkNotNull(db.routineDao().byId(treino.id)))
        assertEquals(call(remindAt = t(9), refreshAt = t(8, 30)), last())
    }

    @Test
    fun `pular a ocorrencia de hoje leva o aviso para amanha e desfazer devolve`() = runTest {
        silent(8, 30)
        val treino = reminding(9)

        repository.skipOccurrence(treino.id, day.toEpochDay())
        assertEquals(call(remindAt = t(9, date = day.plusDays(1)), refreshAt = t(8, 30)), last())

        repository.clearOccurrenceException(treino.id, day.toEpochDay())
        assertEquals(call(remindAt = t(9), refreshAt = t(8, 30)), last())
    }

    @Test
    fun `remarcar o minuto de hoje - antes da silenciosa some o dos widgets, depois volta`() = runTest {
        silent(8, 30)
        val treino = reminding(9)

        repository.rescheduleOccurrence(treino.id, day.toEpochDay(), newStartMinute = 8 * 60 + 15, newEpochDay = null)
        assertEquals("o próximo início já é o do aviso", call(remindAt = t(8, 15), refreshAt = null), last())

        repository.rescheduleOccurrence(treino.id, day.toEpochDay(), newStartMinute = 8 * 60 + 45, newEpochDay = null)
        assertEquals(call(remindAt = t(8, 45), refreshAt = t(8, 30)), last())
    }

    @Test
    fun `remarcar a ocorrencia de hoje para outro dia leva o aviso para la`() = runTest {
        silent(8, 30)
        val treino = reminding(9)

        val tomorrow = day.plusDays(1)
        repository.rescheduleOccurrence(treino.id, day.toEpochDay(), newStartMinute = 7 * 60, newEpochDay = tomorrow.toEpochDay())

        assertEquals(call(remindAt = t(7, date = tomorrow), refreshAt = t(8, 30)), last())
    }

    @Test
    fun `mudar o horario da rotina que avisa para antes da silenciosa`() = runTest {
        silent(8, 30)
        val treino = reminding(9)

        repository.edit(treino.id, RoutineChange.Start(8 * 60 + 10))

        assertEquals(call(remindAt = t(8, 10), refreshAt = null), last())
    }

    @Test
    fun `excluir a que avisa tira o alarme de aviso e o desfazer devolve`() = runTest {
        silent(8, 30)
        val treino = reminding(9)

        repository.archive(treino, now = at(8, 0))
        assertEquals(call(remindAt = null, refreshAt = t(8, 30)), last())

        repository.unarchive(treino.id)
        assertEquals(call(remindAt = t(9), refreshAt = t(8, 30)), last())
    }

    @Test
    fun `excluir a silenciosa deixa so o aviso`() = runTest {
        val leitura = silent(8, 30)
        reminding(9)

        repository.archive(leitura, now = at(8, 0))

        assertEquals(call(remindAt = t(9), refreshAt = null), last())
    }

    // --- Virada de dia e troca de fuso ----------------------------------------------------------

    @Test
    fun `virada de dia - silenciosa 23h30 e aviso 0h15 do dia seguinte`() = runTest {
        clock = { at(23, 0) }
        silent(23, 30)
        val madrugada = reminding(0, 15)
        val tomorrow = day.plusDays(1)
        // A conclusão de hoje não segura o aviso de amanhã.
        repository.complete(madrugada.id, day)
        assertEquals(call(remindAt = t(0, 15, tomorrow), refreshAt = t(23, 30)), last())

        // 23:30: o silencioso tocou; o próximo início já é o do aviso.
        repository.catchUp(nowOverride = at(23, 30))
        assertEquals(call(remindAt = t(0, 15, tomorrow), refreshAt = null), last())

        // 0:15 do dia seguinte: avisa e reagenda os dois.
        repository.catchUp(nowOverride = at(0, 15, tomorrow))
        assertEquals(listOf(tomorrow to madrugada.id), delivered)
        assertEquals(call(remindAt = t(0, 15, tomorrow.plusDays(1)), refreshAt = t(23, 30, tomorrow)), last())
    }

    @Test
    fun `troca de fuso para leste - reagenda nos horarios locais do fuso novo sem avisar nada`() = runTest {
        silent(8, 30)
        reminding(9)
        val instant = at(8, 0) // 8:00 em São Paulo = 11:00 em Lisboa

        zone = LISBON
        repository.catchUp(nowOverride = instant)

        assertTrue(delivered.isEmpty())
        // Em Lisboa já são 11:00: os dois de hoje passaram, ficam os de amanhã no relógio local.
        assertEquals(call(remindAt = t(9, date = day.plusDays(1)), refreshAt = t(8, 30, day.plusDays(1))), last())
        // OBS-1 (v1.10.0): o instante sai convertido no fuso do repositório, não no do sistema.
        assertEquals(at(9, date = day.plusDays(1)) to at(8, 30, day.plusDays(1)), alarms.millis.last())
        assertDiagnosticMatchesSchedule(instant)
    }

    @Test
    fun `troca de fuso para oeste - o bloco volta a ficar pela frente no relogio local`() = runTest {
        zone = LISBON
        clock = { at(8, 0) }
        silent(8, 30)
        val treino = reminding(9)
        repository.catchUp(nowOverride = at(9, 0))
        assertEquals(listOf(day to treino.id), delivered)

        val instant = at(9, 30) // 9:30 em Lisboa = 6:30 em São Paulo
        zone = SAO_PAULO
        repository.catchUp(nowOverride = instant)

        // A rotina segue o relógio local (RoutineSchedule): em São Paulo as 9:00 de hoje ainda vêm.
        assertEquals("nada vencido na janela", listOf(day to treino.id), delivered)
        assertEquals(call(remindAt = t(9), refreshAt = t(8, 30)), last())
        assertDiagnosticMatchesSchedule(instant)
    }

    // --- Horário de verão: o instante sai no fuso do repositório (v1.10.0, OBS-1) -------------------

    @Test
    fun `inicio do horario de verao - bloco na hora que nao existe toca na hora seguinte e avisa uma vez`() = runTest {
        zone = NEW_YORK
        val springForward = LocalDate.of(2030, 3, 10) // 2:00 vira 3:00: 2:30 não existe
        clock = { at(1, 0, springForward) }
        val madrugada = reminding(2, 30)

        // java.time leva 2:30 inexistente para 3:30 (horário de verão): o mesmo instante no alarme e na entrega.
        val expected = springForward.atTime(2, 30).atZone(NEW_YORK).toInstant().toEpochMilli()
        assertEquals(springForward.atTime(3, 30).atZone(NEW_YORK).toInstant().toEpochMilli(), expected)
        assertEquals(expected, alarms.millis.last().first)
        assertEquals(expected, repository.nextRemindingStart(at(1, 0, springForward)))

        repository.catchUp(nowOverride = expected)
        repository.catchUp(nowOverride = expected + 60_000L)
        assertEquals(listOf(springForward to madrugada.id), delivered)
        assertEquals(at(2, 30, springForward.plusDays(1)), alarms.millis.last().first)
    }

    @Test
    fun `fim do horario de verao - bloco na hora repetida avisa so na primeira passagem`() = runTest {
        zone = NEW_YORK
        val fallBack = LocalDate.of(2030, 11, 3) // 2:00 volta para 1:00: 1:30 acontece duas vezes
        clock = { at(0, 30, fallBack) }
        val madrugada = reminding(1, 30)
        val first = fallBack.atTime(1, 30).atZone(NEW_YORK).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
        val second = fallBack.atTime(1, 30).atZone(NEW_YORK).withLaterOffsetAtOverlap().toInstant().toEpochMilli()
        assertEquals(60 * 60_000L, second - first)
        assertEquals("o alarme fica na primeira 1:30", first, alarms.millis.last().first)

        repository.catchUp(nowOverride = first)
        // Uma hora depois o relógio marca 1:30 de novo: nada a avisar, e o próximo é o de amanhã.
        repository.catchUp(nowOverride = second)

        assertEquals(listOf(fallBack to madrugada.id), delivered)
        assertEquals(at(1, 30, fallBack.plusDays(1)), alarms.millis.last().first)
    }

    // --- Limite de 64 inícios descartados -------------------------------------------------------

    @Test
    fun `limite de 64 - com 63 dias concluidos o aviso e o 64o, com 64 nao ha aviso e o dos widgets segue`() = runTest {
        silent(8, 30)
        val treino = reminding(9)
        val dao = db.routineCompletionDao()
        repeat(63) { offset -> dao.insert(RoutineCompletion(routineId = treino.id, epochDay = day.plusDays(offset.toLong()).toEpochDay(), completedAt = TEST_CREATED_AT)) }

        repository.catchUp(nowOverride = at(8, 0))
        assertEquals(call(remindAt = t(9, date = day.plusDays(63)), refreshAt = t(8, 30)), last())
        assertEquals(at(9, 0, day.plusDays(63)), repository.nextRemindingStart(at(8, 0)))

        dao.insert(RoutineCompletion(routineId = treino.id, epochDay = day.plusDays(63).toEpochDay(), completedAt = TEST_CREATED_AT))
        repository.catchUp(nowOverride = at(8, 0))
        assertEquals("limite esgotado: sem aviso, a corrente dos widgets", call(remindAt = null, refreshAt = t(8, 30)), last())
        assertNull(repository.nextRemindingStart(at(8, 0)))
    }

    @Test
    fun `limite esgotado sem silenciosa - o dos widgets fica no proximo inicio descartado`() = runTest {
        val treino = reminding(9)
        val dao = db.routineCompletionDao()
        repeat(64) { offset -> dao.insert(RoutineCompletion(routineId = treino.id, epochDay = day.plusDays(offset.toLong()).toEpochDay(), completedAt = TEST_CREATED_AT)) }

        repository.catchUp(nowOverride = at(8, 0))

        assertEquals(call(remindAt = null, refreshAt = t(9)), last())
    }

    // --- catchUp pelas duas ações (ROUTINE_START e ROUTINE_REFRESH) -----------------------------

    @Test
    fun `os dois alarmes em sequencia - o silencioso atrasado chega junto do aviso, um aviso so`() = runTest {
        silent(8, 30)
        val treino = reminding(9)

        // O receiver trata ROUTINE_REFRESH e ROUTINE_START com o mesmo catchUp() (relógio lido no Mutex).
        clock = { at(9, 0) }
        repository.catchUp() // o silencioso das 8:30, atrasado, entregue às 9:00
        clock = { at(9, 2) }
        repository.catchUp() // o despertador das 9:00

        assertEquals(listOf(day to treino.id), delivered)
        assertEquals(call(remindAt = t(9, date = day.plusDays(1)), refreshAt = t(8, 30, day.plusDays(1))), last())
        assertTrue("a marca não anda para trás", mark.written.zipWithNext().all { (a, b) -> a <= b })
        assertEquals(at(9, 2), mark.written.last())
    }

    @Test
    fun `os dois alarmes em paralelo - um aviso so e a marca nao anda para tras`() = runTest {
        silent(8, 30)
        val treino = reminding(9)
        val ticks = AtomicLong(at(9, 0))
        clock = { ticks.getAndAdd(1_000L) }

        listOf(
            async(Dispatchers.Default) { repository.catchUp() },
            async(Dispatchers.Default) { repository.catchUp() },
        ).awaitAll()

        assertEquals(listOf(day to treino.id), delivered)
        assertTrue("a marca não anda para trás: ${mark.written}", mark.written.zipWithNext().all { (a, b) -> a <= b })
        assertEquals(call(remindAt = t(9, date = day.plusDays(1)), refreshAt = t(8, 30, day.plusDays(1))), last())
    }

    // --- Diagnóstico = agendamento (8.4, item 4) ------------------------------------------------

    @Test
    fun `diagnostico mostra o mesmo horario do alarme de aviso em varios estados`() = runTest {
        silent(8, 30)
        val treino = reminding(9)
        val estudo = repository.add(block("Estudo", 10))
        val now = at(8, 0)

        assertDiagnosticMatchesSchedule(now)
        repository.complete(treino.id, day)
        assertDiagnosticMatchesSchedule(now)
        repository.skipOccurrence(estudo.id, day.toEpochDay())
        assertDiagnosticMatchesSchedule(now)
        repository.rescheduleOccurrence(treino.id, day.plusDays(1).toEpochDay(), newStartMinute = 6 * 60, newEpochDay = null)
        assertDiagnosticMatchesSchedule(now)
        repository.edit(treino.id, RoutineChange.Remind(false))
        repository.edit(estudo.id, RoutineChange.Remind(false))
        assertDiagnosticMatchesSchedule(now)
        assertNull("sem rotina que avise", last().remindAt)
    }

    private companion object {
        val SAO_PAULO: ZoneId = ZoneId.of("America/Sao_Paulo")
        val LISBON: ZoneId = ZoneId.of("Europe/Lisbon")
        val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
    }
}
