package com.kalendrys.app.ui.shopping

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.ui.theme.KalendrysTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Casca da tela da lista (ADR-0002, 12.2; E3): barra com voltar, as ações raras no menu com rótulo
 * ("Desmarcar todos" só habilitado com item marcado, "Renomear lista") e o FAB de adicionar item.
 * O miolo (itens, progresso, total) é da E4 e não muda aqui.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ShoppingListScreenShellTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var repository: ShoppingRepository
    private var backs = 0

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val db = testDb.db
        repository = ShoppingRepository(db)
        val listId = runBlocking {
            repository.createList("Mercado").also { repository.add(it, "Leite", quantity = 1, priceCents = null) }
        }
        val viewModel = testDb.track(
            ShoppingListViewModel(
                SavedStateHandle(mapOf(SHOPPING_LIST_ID_ARG to listId)),
                repository,
                TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao()),
            ),
        )
        compose.setContent {
            KalendrysTheme { ShoppingListScreen(onBack = { backs++ }, viewModel = viewModel) }
        }
        // O Room responde no executor dele, fora do Compose: espera o item aparecer.
        compose.waitUntil(LOAD_TIMEOUT_MS) {
            compose.onAllNodesWithText("Leite").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @After
    fun tearDown() = testDb.close()

    @Test
    fun `titulo e o nome da lista e voltar chama onBack`() {
        compose.onNodeWithText("Mercado").assertIsDisplayed()

        compose.onNodeWithContentDescription("Voltar").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `menu tem Desmarcar todos desabilitado sem marcados e Renomear abre o dialogo`() {
        compose.onNodeWithContentDescription("Mais opções").performClick()
        compose.onNodeWithText("Desmarcar todos").assertIsNotEnabled()
        compose.onNodeWithText("Renomear lista").assertIsEnabled().performClick()

        // O menu fecha e o diálogo de renomear abre com o nome atual.
        compose.onAllNodesWithText("Desmarcar todos").fetchSemanticsNodes().let { assertEquals(0, it.size) }
        compose.onNodeWithText("Renomear lista").assertIsDisplayed()
    }

    @Test
    fun `FAB Adicionar item abre o dialogo de novo item`() {
        compose.onNodeWithContentDescription("Adicionar item").performClick()

        compose.onNodeWithText("Novo item").assertIsDisplayed()
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 5_000L
    }
}
