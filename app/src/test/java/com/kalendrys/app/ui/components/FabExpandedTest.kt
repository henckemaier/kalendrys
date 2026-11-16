package com.kalendrys.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** Corte de altura do FAB (parecer do Esquadro na E3): janela baixa já nasce recolhido. */
class FabExpandedTest {

    @Test
    fun `aberto so no topo da lista e com altura de sobra`() {
        assertEquals(true, fabExpanded(atTop = true, windowHeightDp = 800))
        assertEquals(false, fabExpanded(atTop = false, windowHeightDp = 800))
    }

    @Test
    fun `em janela abaixo de 480dp fica recolhido mesmo no topo`() {
        assertEquals(false, fabExpanded(atTop = true, windowHeightDp = 479))
        assertEquals(false, fabExpanded(atTop = true, windowHeightDp = 360))
        assertEquals(true, fabExpanded(atTop = true, windowHeightDp = COMPACT_HEIGHT_DP))
    }
}
