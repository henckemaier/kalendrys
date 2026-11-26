package com.kalendrys.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.settings.SettingsRepository
import com.kalendrys.app.domain.RecurrenceUnit
import com.kalendrys.app.domain.ReminderRepeat
import java.io.File
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
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
 * Restauro de um backup exportado pela v1.4.1 (schemaVersion 5, banco 9), o formato do app que o
 * usuário tinha no celular antes da medição da v1.7.1 (E6). O JSON segue, chave por chave, os
 * `toJson` do BackupManager da tag v1.4.1, com todos os tipos de dado preenchidos e os nulos como
 * `JSONObject.NULL`, como aquela versão gravava.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackupFromV141Test {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun obj(vararg pairs: Pair<String, Any?>) =
        JSONObject().apply { pairs.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }

    private fun arr(vararg items: JSONObject) = JSONArray().apply { items.forEach { put(it) } }

    private fun backupV141(): String = obj(
        "schemaVersion" to 5,
        "databaseVersion" to 9,
        "appVersion" to "1.4.1",
        "exportedAt" to 1_763_000_000_000L,
        "tasks" to arr(
            obj(
                "id" to 1L, "title" to "Pagar luz", "notes" to "conta", "createdAt" to 100L,
                "dueDate" to 20_400L, "reminderAt" to 1_762_600_000_000L, "repeat" to "CUSTOM",
                "intervalEvery" to 2, "intervalUnit" to "WEEKS", "byWeekdays" to 0b0000010, "location" to "Banco",
            ),
            obj(
                "id" to 2L, "title" to "Sem data", "notes" to "", "createdAt" to 200L,
                "dueDate" to null, "reminderAt" to null, "repeat" to "NONE",
                "intervalEvery" to 1, "byWeekdays" to 0, "location" to "",
            ),
        ),
        "completionLogs" to arr(obj("id" to 10L, "taskId" to 1L, "completedAt" to 1_762_000_000_000L)),
        "routines" to arr(
            obj(
                "id" to 3L, "title" to "Feira", "notes" to "", "icon" to "CART", "color" to "GREEN",
                "startMinute" to 480, "durationMinutes" to 60, "daysOfWeek" to 0b1000000, "remind" to true,
                "createdAt" to 300L, "archivedAt" to null, "categoryId" to 5L, "pausedAt" to null,
                "weekInterval" to 1, "startEpochDay" to null, "endEpochDay" to null,
            ),
            obj(
                "id" to 4L, "title" to "Treino", "notes" to "perna", "icon" to "FITNESS", "color" to "BLUE",
                "startMinute" to 1080, "durationMinutes" to 45, "daysOfWeek" to 0b0010101, "remind" to false,
                "createdAt" to 400L, "archivedAt" to null, "categoryId" to 6L, "pausedAt" to 1_762_500_000_000L,
                "weekInterval" to 2, "startEpochDay" to 20_300L, "endEpochDay" to 20_700L,
            ),
        ),
        "routineCompletions" to arr(obj("id" to 20L, "routineId" to 3L, "epochDay" to 20_400L, "completedAt" to 1_762_100_000_000L)),
        "routineCategories" to arr(
            obj("id" to 5L, "name" to "Casa", "color" to "GREEN", "kind" to "COMPRAS"),
            obj("id" to 6L, "name" to "Saúde", "color" to "BLUE", "kind" to "EXERCICIO"),
        ),
        "routineOverrides" to arr(
            obj(
                "id" to 30L, "routineId" to 3L, "epochDay" to 20_401L, "type" to "RESCHEDULE",
                "newStartMinute" to 540, "newEpochDay" to 20_402L, "createdAt" to 500L,
            ),
            obj(
                "id" to 31L, "routineId" to 4L, "epochDay" to 20_403L, "type" to "SKIP",
                "newStartMinute" to null, "newEpochDay" to null, "createdAt" to 600L,
            ),
        ),
        "shoppingItems" to arr(
            obj(
                "id" to 40L, "routineId" to 3L, "name" to "Banana", "quantity" to 6, "priceCents" to 899L,
                "picked" to true, "position" to 0, "createdAt" to 700L,
            ),
            obj(
                "id" to 41L, "routineId" to 3L, "name" to "Alface", "quantity" to 1, "priceCents" to null,
                "picked" to false, "position" to 1, "createdAt" to 800L,
            ),
        ),
        "exerciseItems" to arr(
            obj(
                "id" to 50L, "routineId" to 4L, "name" to "Agachamento", "sets" to 4, "reps" to 12,
                "load" to "40 kg", "restSeconds" to 90, "position" to 0, "createdAt" to 900L,
            ),
            obj(
                "id" to 51L, "routineId" to 4L, "name" to "Prancha", "sets" to 3, "reps" to 1,
                "load" to null, "restSeconds" to null, "position" to 1, "createdAt" to 1000L,
            ),
        ),
        "settings" to obj("summaryEnabled" to false, "summaryMinuteOfDay" to 1290),
    ).toString()

    @Test
    fun `backup da v1_4_1 restaura todos os dados no formato atual`() = runTest {
        val settings = SettingsRepository(
            PreferenceDataStoreFactory.create { File(context.cacheDir, "v141-${System.nanoTime()}.preferences_pb") },
        )

        val stats = BackupManager(db, context.contentResolver, settings, clock = SystemAppClock).restore(backupV141())

        // Tarefas: recorrência personalizada e nulos preservados.
        val pagar = db.taskDao().byId(1)!!
        assertEquals(ReminderRepeat.CUSTOM, pagar.repeat)
        assertEquals(2, pagar.intervalEvery)
        assertEquals(RecurrenceUnit.WEEKS, pagar.intervalUnit)
        assertEquals(1_762_600_000_000L, pagar.reminderAt)
        assertEquals("Banco", pagar.location)
        assertNull(db.taskDao().byId(2)!!.dueDate)
        assertEquals(1, db.completionLogDao().lastCompletions().size)

        // Rotinas (v1.10.0, ADR-0002 16.10.3): os itens de compra por rotina viravam a lista da rotina;
        // rotina não tem mais lista e essa lista não tem tarefa, então sai com os itens. A rotina fica,
        // com o tipo tirado da categoria (Casa = COMPRAS → Nenhum).
        val feira = db.routineDao().byId(3)!!
        assertNull(feira.shoppingListId)
        assertEquals("NONE", feira.kind)
        assertTrue(db.shoppingListDao().all().isEmpty())
        assertTrue(db.shoppingItemDao().all().isEmpty())

        val treino = db.routineDao().byId(4)!!
        assertEquals(2, treino.weekInterval)
        assertEquals(1_762_500_000_000L, treino.pausedAt)
        assertEquals(20_700L, treino.endEpochDay)
        assertNull(treino.shoppingListId)
        assertEquals("categoria Saúde do tipo EXERCICIO → Exercício", "EXERCISE", treino.kind)
        assertEquals(2, db.exerciseItemDao().all().size)
        assertEquals(2, db.routineOverrideDao().all().size)

        // Ajustes: resumo desligado às 21:30; aparência ausente na 1.4.1 mantém a do aparelho.
        val restored = settings.settings.first()
        assertFalse(restored.summaryEnabled)
        assertEquals(LocalTime.of(21, 30), restored.summaryTime)

        assertEquals(2, stats.tasks)
        assertEquals(1, stats.completionLogs)
        assertEquals(2, stats.routines)
        assertEquals(1, stats.routineCompletions)
        assertEquals(2, stats.routineCategories)
        assertEquals(2, stats.routineOverrides)
        assertEquals(0, stats.shoppingItems)
        assertEquals(2, stats.exerciseItems)
        assertEquals(0, stats.shoppingLists)
    }
}
