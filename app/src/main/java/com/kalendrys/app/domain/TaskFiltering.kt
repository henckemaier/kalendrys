package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Task

/**
 * Busca textual da lista de tarefas: casa a consulta como substring, sem diferenciar
 * maiúsculas, no título, nas observações e no local. Lógica pura, testada na JVM.
 */
object TaskFiltering {

    /** `true` se [query] (vazia = tudo) aparece no título, nas observações ou no local de [task]. */
    fun matches(task: Task, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return task.title.contains(q, ignoreCase = true) ||
            task.notes.contains(q, ignoreCase = true) ||
            task.location.contains(q, ignoreCase = true)
    }
}
