package com.kalendrys.app.ui.shopping

const val SHOPPING_LIST_ID_ARG = "listId"
const val SHOPPING_LIST_TASK_ID_ARG = "taskId"

/** Sem tarefa de origem (o argumento opcional do Navigation não aceita Long nulo). */
const val NO_TASK_ID = -1L

/**
 * Tela de uso de uma lista (ADR-0001, seção 4.5). [SHOPPING_LIST_TASK_ID_ARG] é opcional e só
 * alimenta a oferta "Concluir tarefa" da v1.6.0.
 */
const val SHOPPING_LIST_ROUTE =
    "shopping-list/{$SHOPPING_LIST_ID_ARG}?$SHOPPING_LIST_TASK_ID_ARG={$SHOPPING_LIST_TASK_ID_ARG}"

fun shoppingListRoute(listId: Long, taskId: Long? = null): String =
    if (taskId == null) "shopping-list/$listId" else "shopping-list/$listId?$SHOPPING_LIST_TASK_ID_ARG=$taskId"
