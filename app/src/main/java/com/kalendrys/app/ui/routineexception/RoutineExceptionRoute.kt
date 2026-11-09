package com.kalendrys.app.ui.routineexception

const val ROUTINE_EXCEPTION_ROUTINE_ID_ARG = "routineId"
const val ROUTINE_EXCEPTION_DAY_ARG = "epochDay"
const val ROUTINE_EXCEPTION_ROUTE =
    "routine-exception/{$ROUTINE_EXCEPTION_ROUTINE_ID_ARG}/{$ROUTINE_EXCEPTION_DAY_ARG}"

/**
 * Rota da tela de exceção de UMA ocorrência (pular/remarcar sem desligar a rotina). É o contrato de
 * navegação com o hub da timeline: o gatilho (long-press/menu na ocorrência) chama isto.
 */
fun routineExceptionRoute(routineId: Long, epochDay: Long) = "routine-exception/$routineId/$epochDay"
