package com.kalendrys.app.ui.taskdetail

const val TASK_ID_ARG = "taskId"
const val TASK_DETAIL_ROUTE = "task/{$TASK_ID_ARG}"

fun taskDetailRoute(taskId: Long) = "task/$taskId"
