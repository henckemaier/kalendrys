package com.kalendrys.app.domain

/** Progresso de uma lista de compras: [picked] de [total] itens marcados (ex.: 3/8). */
data class ShoppingProgress(val picked: Int, val total: Int) {

    /** Lista não vazia com todos os itens marcados. Lista vazia nunca está "completa". */
    val isComplete: Boolean get() = total > 0 && picked >= total

    companion object {
        val EMPTY = ShoppingProgress(picked = 0, total = 0)
    }
}
