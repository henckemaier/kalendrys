package com.kalendrys.app.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Desfazer com duração longa (v1.7.1, E4): [showUndo] devolve se a pessoa desfez. */
class ShowUndoTest {

    private val host = SnackbarHostState()

    @Test
    fun `mostra a mensagem e o Desfazer com duracao longa`() = runTest {
        val shown = async { host.showUndo("Tarefa concluída", "Desfazer") }
        runCurrent()

        val visuals = host.currentSnackbarData!!.visuals
        assertEquals("Tarefa concluída", visuals.message)
        assertEquals("Desfazer", visuals.actionLabel)
        assertEquals(SnackbarDuration.Long, visuals.duration)

        host.currentSnackbarData!!.dismiss()
        shown.await()
    }

    @Test
    fun `tocar em Desfazer devolve true`() = runTest {
        val shown = async { host.showUndo("Item removido", "Desfazer") }
        runCurrent()

        host.currentSnackbarData!!.performAction()

        assertTrue(shown.await())
    }

    @Test
    fun `sumir sem tocar devolve false`() = runTest {
        val shown = async { host.showUndo("Item removido", "Desfazer") }
        runCurrent()

        host.currentSnackbarData!!.dismiss()

        assertFalse(shown.await())
    }
}
