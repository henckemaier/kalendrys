package com.kalendrys.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.testutil.TEST_CREATED_AT
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

/** Exercícios no [ExerciseRepository] com Room em memória: round-trip e reordenação. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ExerciseRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ExerciseRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ExerciseRepository(db.exerciseItemDao(), clock = SystemAppClock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `add preserva os campos e normaliza carga e descanso vazios para null`() = runTest {
        repository.add(routineId = 1, name = "  Agachamento ", sets = 3, reps = 12, load = " 20kg ", restSeconds = 90)
        // Carga só com espaços e descanso em branco viram null (sem valor).
        repository.add(routineId = 1, name = "Flexão", sets = 4, reps = 15, load = "   ", restSeconds = null)

        val items = repository.observeItems(1).first()
        assertEquals(2, items.size)
        val first = items[0]
        assertEquals("Agachamento", first.name) // nome é trimado
        assertEquals(3, first.sets)
        assertEquals(12, first.reps)
        assertEquals("20kg", first.load) // carga é trimada
        assertEquals(90, first.restSeconds)
        assertEquals(0, first.position) // primeiro item na posição 0
        val second = items[1]
        assertNull("carga só de espaços deveria virar null", second.load)
        assertNull(second.restSeconds)
        assertEquals(1, second.position) // position = max + 1
    }

    @Test
    fun `sets e reps em branco caem no minimo 1`() = runTest {
        repository.add(routineId = 1, name = "Prancha", sets = 0, reps = 0, load = null, restSeconds = null)
        val item = repository.observeItems(1).first().single()
        assertEquals(1, item.sets)
        assertEquals(1, item.reps)
    }

    @Test
    fun `update troca os campos preservando id, routineId, position e createdAt`() = runTest {
        repository.add(routineId = 1, name = "Supino", sets = 3, reps = 10, load = "40kg", restSeconds = 60)
        val original = repository.observeItems(1).first().single()

        repository.update(original, name = "Supino inclinado", sets = 4, reps = 8, load = "45kg", restSeconds = 120)

        val updated = repository.observeItems(1).first().single()
        assertEquals(original.id, updated.id)
        assertEquals(original.routineId, updated.routineId)
        assertEquals(original.position, updated.position)
        assertEquals(original.createdAt, updated.createdAt)
        assertEquals("Supino inclinado", updated.name)
        assertEquals(4, updated.sets)
        assertEquals(8, updated.reps)
        assertEquals("45kg", updated.load)
        assertEquals(120, updated.restSeconds)
    }

    @Test
    fun `dois subir seguidos com o item velho da tela nao repetem posicoes`() = runTest {
        // NB-E3-A do Lince: A, B, C, X; subir o X duas vezes com o MESMO objeto (o da tela, velho).
        listOf("A", "B", "C", "X").forEach { repository.add(1, it, sets = 1, reps = 1, load = null, restSeconds = null) }
        val staleX = repository.observeItems(1).first().single { it.name == "X" }

        repository.move(staleX, up = true)
        repository.move(staleX, up = true)

        val items = repository.observeItems(1).first()
        assertEquals(listOf("A", "X", "B", "C"), items.map { it.name })
        assertEquals(items.size, items.map { it.position }.distinct().size)
        // E descer o B depois funciona (não fica preso por posição repetida).
        repository.move(items.single { it.name == "B" }, up = false)
        assertEquals(listOf("A", "X", "C", "B"), repository.observeItems(1).first().map { it.name })
    }

    @Test
    fun `lista com posicoes repetidas (defeito da v1_8_0) se conserta ao mover`() = runTest {
        // Esquadro: com posições iguais, a troca antiga trocava dois valores iguais e o item ficava preso.
        db.exerciseItemDao().insertAll(
            listOf(
                com.kalendrys.app.data.local.entity.ExerciseItem(id = 1, routineId = 1, name = "A", sets = 1, reps = 1, position = 0, createdAt = TEST_CREATED_AT),
                com.kalendrys.app.data.local.entity.ExerciseItem(id = 2, routineId = 1, name = "B", sets = 1, reps = 1, position = 3, createdAt = TEST_CREATED_AT),
                com.kalendrys.app.data.local.entity.ExerciseItem(id = 3, routineId = 1, name = "C", sets = 1, reps = 1, position = 3, createdAt = TEST_CREATED_AT),
            ),
        )
        val c = repository.observeItems(1).first().single { it.name == "C" }

        repository.move(c, up = true)
        assertEquals(listOf("A", "C", "B"), repository.observeItems(1).first().map { it.name })

        repository.move(c, up = false)
        val items = repository.observeItems(1).first()
        assertEquals(listOf("A", "B", "C"), items.map { it.name })
        assertEquals(listOf(0, 1, 2), items.map { it.position })
    }

    @Test
    fun `editar com o item velho logo depois de mover nao devolve a posicao antiga`() = runTest {
        listOf("A", "B").forEach { repository.add(1, it, sets = 1, reps = 1, load = null, restSeconds = null) }
        val staleB = repository.observeItems(1).first().single { it.name == "B" }

        repository.move(staleB, up = true)
        repository.update(staleB, name = "B2", sets = 2, reps = 2, load = null, restSeconds = null)

        assertEquals(listOf("B2", "A"), repository.observeItems(1).first().map { it.name })
    }

    @Test
    fun `remove apaga o exercicio`() = runTest {
        repository.add(routineId = 1, name = "Remada", sets = 3, reps = 12, load = null, restSeconds = null)
        val item = repository.observeItems(1).first().single()
        repository.remove(item)
        assertTrue(repository.observeItems(1).first().isEmpty())
    }

    @Test
    fun `move para cima troca a ordem com o vizinho acima`() = runTest {
        repository.add(routineId = 1, name = "A", sets = 1, reps = 1, load = null, restSeconds = null)
        repository.add(routineId = 1, name = "B", sets = 1, reps = 1, load = null, restSeconds = null)
        repository.add(routineId = 1, name = "C", sets = 1, reps = 1, load = null, restSeconds = null)
        val before = repository.observeItems(1).first()
        assertEquals(listOf("A", "B", "C"), before.map { it.name })

        // Move o B para cima → B troca de posição com A.
        repository.move(before[1], up = true)
        assertEquals(listOf("B", "A", "C"), repository.observeItems(1).first().map { it.name })

        // Move o C para baixo (já é o último) → no-op.
        val mid = repository.observeItems(1).first()
        repository.move(mid.last(), up = false)
        assertEquals(listOf("B", "A", "C"), repository.observeItems(1).first().map { it.name })

        // Move o primeiro para cima → no-op (nada acima).
        repository.move(repository.observeItems(1).first().first(), up = true)
        assertEquals(listOf("B", "A", "C"), repository.observeItems(1).first().map { it.name })
    }

    @Test
    fun `move para baixo troca a ordem com o vizinho abaixo`() = runTest {
        repository.add(routineId = 1, name = "A", sets = 1, reps = 1, load = null, restSeconds = null)
        repository.add(routineId = 1, name = "B", sets = 1, reps = 1, load = null, restSeconds = null)
        val a = repository.observeItems(1).first().first()

        repository.move(a, up = false)
        assertEquals(listOf("B", "A"), repository.observeItems(1).first().map { it.name })
    }
}
