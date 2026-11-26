package com.kalendrys.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.domain.ExerciseFields
import com.kalendrys.app.testutil.FakeAppClock
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

/** Exercícios salvos (v1.10.0, ADR-0002 16.10.5): um salvo é um exercício, com as regras de sempre. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SavedExerciseRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: SavedExerciseRepository
    private val clock = FakeAppClock(startMillis = 1_234L)

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = SavedExerciseRepository(db.savedExerciseDao(), clock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `salvar normaliza, carimba pelo relogio do app e aceita nome repetido`() = runTest {
        repository.save(ExerciseFields(" Supino ", sets = 0, reps = 8, load = " ", restSeconds = -1))
        repository.save(ExerciseFields("Supino", sets = 3, reps = 10))

        val all = db.savedExerciseDao().all()
        assertEquals(listOf("Supino", "Supino"), all.map { it.name })
        assertEquals(1, all[0].sets)
        assertNull(all[0].load)
        assertNull(all[0].restSeconds)
        assertTrue(all.all { it.createdAt == 1_234L })
    }

    @Test
    fun `sem nome nao salva nem edita`() = runTest {
        assertNull(repository.save(ExerciseFields("   ", sets = 3, reps = 10)))
        val id = repository.save(ExerciseFields("Remada", sets = 3, reps = 10))!!

        repository.update(id, ExerciseFields("  ", sets = 1, reps = 1))

        assertEquals("Remada", db.savedExerciseDao().byId(id)!!.name)
        assertEquals(1, db.savedExerciseDao().all().size)
    }

    @Test
    fun `editar e excluir pelo id, e a lista vem por nome`() = runTest {
        val b = repository.save(ExerciseFields("b", sets = 1, reps = 1))!!
        repository.save(ExerciseFields("A", sets = 1, reps = 1))

        repository.update(b, ExerciseFields("C", sets = 2, reps = 2, load = "5kg"))
        assertEquals(listOf("A", "C"), repository.saved.first().map { it.name })
        assertEquals(1_234L, db.savedExerciseDao().byId(b)!!.createdAt)

        repository.delete(b)
        assertEquals(listOf("A"), repository.saved.first().map { it.name })
    }
}
