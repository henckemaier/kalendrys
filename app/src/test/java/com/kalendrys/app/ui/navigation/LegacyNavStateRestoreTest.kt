package com.kalendrys.app.ui.navigation

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.ui.routines.ALL_ROUTINES_ROUTE
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Atualizar por cima da v1.7.1 com a aba Rotinas aberta (E2, ADR-0002 14.4): o estado salvo pelo app
 * antigo (início em "tasks", aba "routines" à frente) é restaurado no grafo novo (início na Hoje) sem
 * quebrar, e cai na Hoje. Funciona porque a Hoje mantém a rota "routines" da aba que ela substitui.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LegacyNavStateRestoreTest {

    @get:Rule
    val compose = createComposeRule()

    /** Rotas do grafo da v1.7.1 (início em Tarefas, aba Rotinas). */
    private val legacyTabs = listOf("tasks", "routines", "calendar", "lists")

    @Test
    fun `estado salvo com a aba Rotinas da v1_7_1 abre na Hoje no grafo novo`() {
        var newGraph by mutableStateOf(false)
        lateinit var navController: NavHostController
        val tester = StateRestorationTester(compose)
        tester.setContent {
            navController = rememberNavController()
            if (newGraph) {
                // O grafo atual: abas do AppTab (Hoje é o início) e a rota nova "Todas as rotinas".
                NavHost(navController, startDestination = AppTab.Today.route) {
                    AppTab.entries.forEach { tab -> composable(tab.route) { BasicText("aba ${tab.name}") } }
                    composable(ALL_ROUTINES_ROUTE) { BasicText("todas") }
                }
            } else {
                NavHost(navController, startDestination = "tasks") {
                    legacyTabs.forEach { route -> composable(route) { BasicText("antiga $route") } }
                }
            }
        }
        compose.runOnUiThread { navController.navigate("routines") }
        compose.waitForIdle()
        compose.onNodeWithText("antiga routines").fetchSemanticsNode()

        // Processo recriado (atualização) com o estado salvo pelo app antigo, agora com o grafo novo.
        newGraph = true
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertEquals(AppTab.Today.route, navController.currentDestination?.route)
        compose.onNodeWithText("aba Today").fetchSemanticsNode()
    }

    @Test
    fun `a Hoje e a primeira aba e o destino inicial`() {
        assertEquals(AppTab.Today, AppTab.entries.first())
        // v1.10.0 (ADR-0002 16.10.1): três abas; a rota "lists" continua registrada e cai em Tarefas.
        assertEquals(listOf("Today", "Tasks", "Calendar"), AppTab.entries.map { it.name })
    }
}
