package com.kalendrys.app.ui.routineeditor

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.repository.ExerciseRepository
import com.kalendrys.app.data.repository.RoutineCategoryRepository
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.SystemAppClock
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.FakeRoutineNotifications
import com.kalendrys.app.testutil.InMemoryRoutineCatchUpMark
import com.kalendrys.app.testutil.TEST_CREATED_AT
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.testUndoOffers
import com.kalendrys.app.ui.components.assertWeekdayCellsFit
import com.kalendrys.app.ui.components.weekdayPillWidthsPx
import com.kalendrys.app.ui.theme.KalendrysTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Dias da rotina no editor (v1.7.1, E2): em 360dp e fonte 2.0, células de 48dp sem corte nem vão. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-port")
// Texto medido com fonte de verdade (no modo padrão do Robolectric a largura do texto é 0).
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoutineEditorWeekdaysTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var pills: List<Int>

    @After
    fun tearDown() = testDb.close()

    @Test
    fun `dias da rotina em 360dp e fonte 2_0 cabem sem corte`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val db = testDb.db
        val routineRepository = RoutineRepository(
            db.routineDao(),
            db.routineCompletionDao(),
            db.routineOverrideDao(),
            FakeRoutineAlarms(context),
            FakeRoutineNotifications(context),
            db.shoppingItemDao(),
            InMemoryRoutineCatchUpMark(),
            clock = SystemAppClock,
        )
        val routine = runBlocking { routineRepository.add(Routine(title = "Treino", startMinute = 420, createdAt = TEST_CREATED_AT)) }
        val viewModel = testDb.track(
            RoutineEditorViewModel(
                SavedStateHandle(mapOf(ROUTINE_ID_ARG to routine.id)),
                routineRepository,
                RoutineCategoryRepository(db),
                ShoppingRepository(db, clock = SystemAppClock),
                ExerciseRepository(db.exerciseItemDao(), clock = SystemAppClock),
                testUndoOffers(testDb, routineRepository),
                testDb.appScope(),
                clock = SystemAppClock,
            ),
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                KalendrysTheme {
                    pills = weekdayPillWidthsPx()
                    RoutineEditorScreen(onBack = {}, viewModel = viewModel)
                }
            }
        }
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("Treino").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNode(hasScrollToKeyAction()).performScrollToKey("when") // os dias ficam na seção Quando (v1.9.0, P4)
        compose.waitForIdle()

        compose.assertWeekdayCellsFit(pills, compose.density.density)
    }
}
