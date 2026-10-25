package com.kalendrys.app.ui.routineeditor

const val ROUTINE_ID_ARG = "routineId"
const val ROUTINE_EDITOR_ROUTE = "routine/{$ROUTINE_ID_ARG}"

/** Id usado na rota para "nova rotina" (o Room nunca gera 0). */
const val NEW_ROUTINE_ID = 0L

fun routineEditorRoute(routineId: Long = NEW_ROUTINE_ID) = "routine/$routineId"
