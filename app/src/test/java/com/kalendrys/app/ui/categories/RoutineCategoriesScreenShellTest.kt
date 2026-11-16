package com.kalendrys.app.ui.categories

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.repository.RoutineCategoryRepository
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Casca da tela de categorias (ADR-0002, 12.2; E3): barra com voltar e o FAB de nova categoria. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoutineCategoriesScreenShellTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase
    private var backs = 0

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val viewModel = testDb.track(RoutineCategoriesViewModel(RoutineCategoryRepository(testDb.db)))
        compose.setContent {
            KalendrysTheme { RoutineCategoriesScreen(onBack = { backs++ }, viewModel = viewModel) }
        }
    }

    @After
    fun tearDown() = testDb.close()

    @Test
    fun `barra com titulo e voltar`() {
        compose.onNodeWithText("Categorias").assertIsDisplayed()

        compose.onNodeWithContentDescription("Voltar").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `FAB Nova categoria abre o dialogo de criar`() {
        compose.onNodeWithContentDescription("Nova categoria").performClick()

        // Campo de nome do diálogo de criar.
        compose.onNodeWithText("Nome").assertIsDisplayed()
    }
}
