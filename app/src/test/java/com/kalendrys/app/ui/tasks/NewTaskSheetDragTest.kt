package com.kalendrys.app.ui.tasks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arrastar o painel de nova tarefa (v1.9.0, ADR-0002, 15.7): em altura compacta (paisagem) com o
 * teclado aberto, arrastar NÃO fecha (no emulador, um arrasto rápido nos campos fechava o painel no
 * meio da digitação). Sem teclado, ou em altura normal, o arrasto segue valendo.
 */
class NewTaskSheetDragTest {

    @Test
    fun `compacta com teclado aberto - arrasto desligado`() =
        assertFalse(sheetDragEnabled(compact = true, imeOpen = true))

    @Test
    fun `compacta sem teclado - arrasto segue fechando`() =
        assertTrue(sheetDragEnabled(compact = true, imeOpen = false))

    @Test
    fun `altura normal - arrasto segue valendo com ou sem teclado`() {
        assertTrue(sheetDragEnabled(compact = false, imeOpen = true))
        assertTrue(sheetDragEnabled(compact = false, imeOpen = false))
    }
}
