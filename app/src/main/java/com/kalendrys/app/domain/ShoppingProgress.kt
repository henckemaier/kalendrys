package com.kalendrys.app.domain

/** Progresso de uma lista de compras: [picked] de [total] itens marcados (ex.: 3/8). */
data class ShoppingProgress(val picked: Int, val total: Int) {

    /**
     * Se o "3/8" aparece (ADR-0002, 12.4): só com a lista tendo itens. Lista vazia esconde o
     * progresso em todo lugar (linha de tarefa, bloco de rotina, cartão, seletor, `ProgressLine`,
     * widget e notificações). Origem única da regra: quem mostra progresso pergunta aqui.
     */
    val isVisible: Boolean get() = total > 0

    /** Lista não vazia com todos os itens marcados. Lista vazia nunca está "completa". */
    val isComplete: Boolean get() = isVisible && picked >= total

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
            progress.visibleOrNull()?.let { "$title · ${it.label}" } ?: title
    }
}

/** O progresso, se ele aparece ([ShoppingProgress.isVisible]); `null` sem lista ou com lista vazia. */
fun ShoppingProgress?.visibleOrNull(): ShoppingProgress? = this?.takeIf { it.isVisible }

/**
 * Progresso a mostrar para o dono que aponta para [listId] (ADR-0001, P8): `null` sem lista, com
 * vínculo órfão (lista ausente do mapa) ou com lista vazia, igual ao widget e às notificações
 * ([ShoppingProgress.titleWithProgress]).
 */
fun Map<Long, ShoppingProgress>.visibleProgressFor(listId: Long?): ShoppingProgress? =
    listId?.let(::get).visibleOrNull()
