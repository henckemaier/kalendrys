package com.kalendrys.app.ui.categories

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.repository.RoutineCategoryRepository
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Categorias sem nenhuma (ADR-0002, D8): o estado vazio traz "Nova categoria", que abre o diálogo do FAB. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoutineCategoriesEmptyStateTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val viewModel = testDb.track(RoutineCategoriesViewModel(RoutineCategoryRepository(testDb.db)))
        compose.setContent {
            KalendrysTheme { RoutineCategoriesScreen(onBack = {}, viewModel = viewModel) }
        }
    }

    @After
    fun tearDown() = testDb.close()

    @Test
    fun `vazio tem a acao Nova categoria e ela abre o dialogo de criar`() {
        compose.onNodeWithText("Nenhuma categoria").assertIsDisplayed()

        // O botão do vazio (o FAB só tem descrição, não texto).
        compose.onNode(hasText("Nova categoria") and hasClickAction()).performClick()

        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("Nome").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Nome").assertIsDisplayed()
    }
}
