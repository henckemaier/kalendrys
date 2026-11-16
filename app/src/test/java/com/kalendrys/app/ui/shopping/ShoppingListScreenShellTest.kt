package com.kalendrys.app.ui.shopping

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.data.settings.SettingsRepository
import java.io.File
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.ui.theme.KalendrysTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

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
    private var listId = 0L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val db = testDb.db
        repository = ShoppingRepository(db)
        listId = runBlocking {
            repository.createList("Mercado").also { repository.add(it, "Leite", quantity = 1, priceCents = null) }
        }
        val viewModel = testDb.track(
            ShoppingListViewModel(
                SavedStateHandle(mapOf(SHOPPING_LIST_ID_ARG to listId)),
                repository,
                TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao()),
                SettingsRepository(
                    PreferenceDataStoreFactory.create { File(context.cacheDir, "lista-${System.nanoTime()}.preferences_pb") },
                ),
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
    fun `adicao rapida no rodape adiciona cinco itens seguidos sem perder o foco`() {
        val field = compose.onNode(hasSetTextAction())
        listOf("Pão", "Ovos", "Queijo", "Manteiga", "Suco").forEach { name ->
            field.performTextInput(name)
            field.performImeAction()
            compose.waitUntil(LOAD_TIMEOUT_MS) {
                // A inclusão passa pelo Room; drena o looper principal do Robolectric (nota do Lince).
                shadowOf(Looper.getMainLooper()).idle()
                itemNames().contains(name)
            }
            // C3: a lista rola até o item novo (o último dos não marcados), que aparece na tela.
            compose.waitUntil(LOAD_TIMEOUT_MS) {
                shadowOf(Looper.getMainLooper()).idle()
                compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
            }
            // O campo volta vazio e continua focado: o teclado não fecha entre um item e outro.
            field.assertIsFocused()
            field.assert(hasText(""))
        }
        // Na ordem digitada, depois do Leite que já estava.
        assertEquals(listOf("Leite", "Pão", "Ovos", "Queijo", "Manteiga", "Suco"), itemNames())
        // Não há mais FAB nem diálogo de novo item (C1): a inclusão é só pelo campo.
        assertEquals(0, compose.onAllNodesWithText("Novo item").fetchSemanticsNodes().size)
    }

    @Test
    fun `cabecalho Marcados e um no so, cabecalho, com a acao de recolher`() {
        val leite = runBlocking { repository.observeItems(listId).first().single() }
        runBlocking { repository.setPicked(leite, true) }
        compose.waitUntil(LOAD_TIMEOUT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("Marcados").fetchSemanticsNodes().isNotEmpty()
        }

        val node = compose.onNodeWithText("Marcados").fetchSemanticsNode()
        assertTrue(node.config.contains(SemanticsProperties.Heading))
        assertEquals("Recolher marcados", node.config[SemanticsActions.OnClick].label)

        compose.onNodeWithText("Marcados").performClick()
        compose.waitForIdle()
        // Recolhido: o item marcado sai da lista e a ação passa a ser mostrar.
        assertEquals(0, compose.onAllNodesWithText("Leite").fetchSemanticsNodes().size)
        assertEquals(
            "Mostrar marcados",
            compose.onNodeWithText("Marcados").fetchSemanticsNode().config[SemanticsActions.OnClick].label,
        )
    }

    private fun itemNames(): List<String> = runBlocking { repository.observeItems(listId).first().map { it.name } }

    private companion object {
        const val LOAD_TIMEOUT_MS = 5_000L
    }
}
