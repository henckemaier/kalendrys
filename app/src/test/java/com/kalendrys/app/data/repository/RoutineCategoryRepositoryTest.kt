package com.kalendrys.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.domain.RoutineCategoryKind
import com.kalendrys.app.domain.RoutineColor
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

/** Categorias no [RoutineCategoryRepository] com Room em memória. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoutineCategoryRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RoutineCategoryRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoutineCategoryRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `create grava uma categoria SIMPLE`() = runTest {
        val id = repository.create("Academia", RoutineColor.GREEN.name)
        val saved = repository.categories.first().first { it.id == id }
        assertEquals("Academia", saved.name)
        assertEquals(RoutineCategoryKind.SIMPLE.name, saved.kind)
    }

    @Test
    fun `excluir desvincula as rotinas da categoria e remove a categoria`() = runTest {
        val categoryId = repository.create("Academia", RoutineColor.GREEN.name)
        val otherId = repository.create("Estudo", RoutineColor.BLUE.name)
        val routineDao = db.routineDao()
        val r1 = routineDao.insert(Routine(title = "Treino", startMinute = 420, categoryId = categoryId))
        val r2 = routineDao.insert(Routine(title = "Corrida", startMinute = 480, categoryId = categoryId))
        val r3 = routineDao.insert(Routine(title = "Leitura", startMinute = 1200, categoryId = otherId))

        val category = repository.categories.first().first { it.id == categoryId }
        repository.delete(category)

        // A categoria sumiu.
        assertTrue(repository.categories.first().none { it.id == categoryId })
        // As rotinas dela ficaram sem categoria.
        assertNull(routineDao.byId(r1)?.categoryId)
        assertNull(routineDao.byId(r2)?.categoryId)
        // As de outra categoria não foram tocadas.
        assertEquals(otherId, routineDao.byId(r3)?.categoryId)
    }
}
