package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineOverride
import com.kalendrys.app.data.local.entity.Task
import java.time.LocalDate
import java.time.ZoneId

/**
 * A linha do tempo de um dia: os blocos de rotina em ordem, os intervalos livres entre eles e as
 * tarefas pontuais (com horário) daquele dia. Lógica pura, para a tela de rotinas desenhar sem
 * calcular nada. As tarefas sem horário não entram aqui — a tela as mostra numa faixa à parte.
 *
 * Honra as exceções pontuais (T20): uma ocorrência com `SKIP` some do dia, e uma `RESCHEDULE` entra
 * (ou sai) no horário/dia efetivo — por isso cada [Block] carrega o seu início já resolvido.
 */
object DayTimeline {

    /** Como a linha vertical liga um bloco ao vizinho. */
    enum class Link {
        /** Primeiro ou último bloco do dia. */
        NONE,

        /** O próximo começa antes de este terminar (ou junto). */
        SOLID,

        /** Há tempo livre entre os dois. */
        DASHED,
    }

    sealed interface Item {
        val key: String

        /** Minuto do dia em que o item começa; ordena a trilha. */
        val startMinute: Int
    }

    data class Block(
        val routine: Routine,
        val done: Boolean,
        /** Início efetivo no dia (honra uma remarcação), em minutos desde a meia-noite. */
        override val startMinute: Int,
        val linkAbove: Link,
        val linkBelow: Link,
    ) : Item {
        /** Pode passar de 1440 (bloco que termina depois da meia-noite). */
        val endMinute: Int get() = startMinute + routine.durationMinutes
        override val key: String get() = "routine_${routine.id}"
    }

    data class Gap(override val startMinute: Int, val minutes: Int) : Item {
        override val key: String get() = "gap_$startMinute"
    }

    /**
     * Uma tarefa pontual no horário do seu lembrete. Ao contrário da rotina (que se repete e liga
     * aos vizinhos), ela fica solta na trilha: a tela a desenha com destaque oposto ao do bloco.
     */
    data class TaskItem(
        val task: Task,
        override val startMinute: Int,
        val done: Boolean,
    ) : Item {
        override val key: String get() = "task_${task.id}"
    }

    /** Intervalos menores que isso não ganham linha própria; a ligação só fica tracejada. */
    const val MIN_GAP_MINUTES = 30

    fun build(
        routines: List<Routine>,
        doneIds: Set<Long>,
        date: LocalDate,
        zone: ZoneId,
        tasks: List<Task> = emptyList(),
        doneTaskIds: Set<Long> = emptySet(),
        overrides: Map<Long, List<RoutineOverride>> = emptyMap(),
    ): List<Item> {
        val routineItems = buildRoutineItems(routines, doneIds, date, zone, overrides)
        val taskItems = tasks
            .mapNotNull { task ->
                val time = TaskSchedule.reminderOn(task, date, zone) ?: return@mapNotNull null
                TaskItem(task = task, startMinute = time.hour * 60 + time.minute, done = task.id in doneTaskIds)
            }
            .sortedWith(compareBy({ it.startMinute }, { it.task.id }))

        if (taskItems.isEmpty()) return routineItems
        if (routineItems.isEmpty()) return taskItems
        // Mescla estável por horário: mantém a ordem (e os intervalos) das rotinas e intercala as tarefas.
        return (routineItems + taskItems).sortedBy { it.startMinute }
    }

    private fun buildRoutineItems(
        routines: List<Routine>,
        doneIds: Set<Long>,
        date: LocalDate,
        zone: ZoneId,
        overrides: Map<Long, List<RoutineOverride>>,
    ): List<Item> {
        // Cada rotina que acontece no dia (já honrando SKIP/RESCHEDULE) com o seu início efetivo.
        val day = routines
            .mapNotNull { routine ->
                val ov = overrides[routine.id].orEmpty()
                if (!RoutineSchedule.occursOn(routine, date, zone, ov)) return@mapNotNull null
                val start = RoutineSchedule.startOn(routine, date, ov)
                routine to (start.hour * 60 + start.minute)
            }
            .sortedWith(compareBy({ it.second }, { it.first.id }))
        if (day.isEmpty()) return emptyList()

        fun endOf(index: Int): Int = day[index].second + day[index].first.durationMinutes

        // Um bloco "encosta" no anterior se começa antes de tudo o que veio antes terminar.
        val contiguous = BooleanArray(day.size)
        val gapBefore = IntArray(day.size)
        var latestEnd = endOf(0)
        for (i in 1 until day.size) {
            val start = day[i].second
            contiguous[i] = start <= latestEnd
            gapBefore[i] = (start - latestEnd).coerceAtLeast(0)
            latestEnd = maxOf(latestEnd, endOf(i))
        }

        val items = ArrayList<Item>(day.size * 2)
        day.forEachIndexed { i, (routine, startMinute) ->
            if (i > 0 && gapBefore[i] >= MIN_GAP_MINUTES) {
                items += Gap(startMinute = startMinute - gapBefore[i], minutes = gapBefore[i])
            }
            items += Block(
                routine = routine,
                done = routine.id in doneIds,
                startMinute = startMinute,
                linkAbove = when {
                    i == 0 -> Link.NONE
                    contiguous[i] -> Link.SOLID
                    else -> Link.DASHED
                },
                linkBelow = when {
                    i == day.lastIndex -> Link.NONE
                    contiguous[i + 1] -> Link.SOLID
                    else -> Link.DASHED
                },
            )
        }
        return items
    }
}
