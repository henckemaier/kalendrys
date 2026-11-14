package com.kalendrys.app.ui.shopping

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.R
import com.kalendrys.app.domain.ShoppingProgress
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "N de M marcado(s)" em pt-BR: só o 1 fica no singular. A regra CLDR do pt põe 0 em "one", por isso
 * a quantidade passa por [markedQuantity]; aqui os recursos são resolvidos de verdade.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "pt-rBR")
class ShoppingProgressPluralsTest {

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun card(picked: Int, total: Int): String {
        val progress = ShoppingProgress(picked, total)
        return resources.getQuantityString(R.plurals.list_card_progress, progress.markedQuantity, picked, total)
    }

    private fun description(picked: Int, total: Int): String {
        val progress = ShoppingProgress(picked, total)
        return resources.getQuantityString(R.plurals.list_progress_description, progress.markedQuantity, picked, total)
    }

    @Test
    fun `um marcado fica no singular`() {
        assertEquals("1 de 1 marcado", card(1, 1))
        assertEquals("1 de 8 marcado", card(1, 8))
        assertEquals("Lista de compras: 1 de 8 marcado", description(1, 8))
    }

    @Test
    fun `zero e demais numeros continuam no plural`() {
        assertEquals("0 de 8 marcados", card(0, 8))
        assertEquals("3 de 8 marcados", card(3, 8))
        assertEquals("8 de 8 marcados", card(8, 8))
        assertEquals("Lista de compras: 0 de 2 marcados", description(0, 2))
        assertEquals("Lista de compras: 2 de 2 marcados", description(2, 2))
    }
}
