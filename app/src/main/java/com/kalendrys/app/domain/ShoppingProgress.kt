package com.kalendrys.app.domain

/** Progresso de uma lista de compras: [picked] de [total] itens marcados (ex.: 3/8). */
data class ShoppingProgress(val picked: Int, val total: Int) {

    /** Lista não vazia com todos os itens marcados. Lista vazia nunca está "completa". */
    val isComplete: Boolean get() = total > 0 && picked >= total

    /** "3/8". */
    val label: String get() = "$picked/$total"

    companion object {
        val EMPTY = ShoppingProgress(picked = 0, total = 0)

        /**
         * Título com o progresso da lista ao lado, no formato único do app (ADR-0001, P8):
         * "Feira · 3/8". Sem lista, vínculo órfão (`null`) ou lista vazia: só o título.
         * Usado no widget Hoje, no resumo diário, no lembrete de tarefa e no aviso de bloco.
         */
        fun titleWithProgress(title: String, progress: ShoppingProgress?): String =
            if (progress == null || progress.total == 0) title else "$title · ${progress.label}"
    }
}
