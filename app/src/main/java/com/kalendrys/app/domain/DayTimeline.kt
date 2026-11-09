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
     * Uma tarefa pontual no horário do seu lembrete. É um ponto na trilha (duração 0): liga aos
     * vizinhos pela mesma regra dos blocos, mas a tela a desenha com destaque oposto ao do bloco.
     */
    data class TaskItem(
        val task: Task,
        override val startMinute: Int,
        val done: Boolean,
        val linkAbove: Link,
        val linkBelow: Link,
    ) : Item {
        /** Ponto no tempo: termina onde começa. */
        val endMinute: Int get() = startMinute
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
        // Cada rotina (já honrando SKIP/RESCHEDULE) e cada tarefa com horário, como pontos [start, end]
        // na mesma trilha: a rotina ocupa a sua duração, a tarefa é um ponto (end = start).
        val entries = ArrayList<Entry>(routines.size + tasks.size)
        routines.forEach { routine ->
            val ov = overrides[routine.id].orEmpty()
            if (!RoutineSchedule.occursOn(routine, date, zone, ov)) return@forEach
            val start = RoutineSchedule.startOn(routine, date, ov)
            val startMinute = start.hour * 60 + start.minute
            entries += Entry(
                startMinute = startMinute,
                endMinute = startMinute + routine.durationMinutes,
                isRoutine = true,
                orderId = routine.id,
            ) { above, below -> Block(routine, routine.id in doneIds, startMinute, above, below) }
        }
        tasks.forEach { task ->
            val time = TaskSchedule.reminderOn(task, date, zone) ?: return@forEach
            val startMinute = time.hour * 60 + time.minute
            entries += Entry(
                startMinute = startMinute,
                endMinute = startMinute,
                isRoutine = false,
                orderId = task.id,
            ) { above, below -> TaskItem(task, startMinute, task.id in doneTaskIds, above, below) }
        }
        if (entries.isEmpty()) return emptyList()
        // Ordem estável por horário; no mesmo minuto a rotina (com duração) vem antes da tarefa pontual.
        entries.sortWith(compareBy({ it.startMinute }, { if (it.isRoutine) 0 else 1 }, { it.orderId }))

        // Um item "encosta" no anterior se começa antes de tudo o que veio antes terminar (gap <= 0).
        val contiguous = BooleanArray(entries.size)
        val gapBefore = IntArray(entries.size)
        var latestEnd = entries[0].endMinute
        for (i in 1 until entries.size) {
            val start = entries[i].startMinute
            contiguous[i] = start <= latestEnd
            gapBefore[i] = (start - latestEnd).coerceAtLeast(0)
            latestEnd = maxOf(latestEnd, entries[i].endMinute)
        }

        val items = ArrayList<Item>(entries.size * 2)
        entries.forEachIndexed { i, entry ->
            if (i > 0 && gapBefore[i] >= MIN_GAP_MINUTES) {
                items += Gap(startMinute = entry.startMinute - gapBefore[i], minutes = gapBefore[i])
            }
            val linkAbove = when {
                i == 0 -> Link.NONE
                contiguous[i] -> Link.SOLID
                else -> Link.DASHED
            }
            val linkBelow = when {
                i == entries.lastIndex -> Link.NONE
                contiguous[i + 1] -> Link.SOLID
                else -> Link.DASHED
            }
            items += entry.toItem(linkAbove, linkBelow)
        }
        return items
    }

    /** Um item da trilha reduzido ao que o cálculo de intervalo/ligação precisa, com a fábrica do [Item] final. */
    private class Entry(
        val startMinute: Int,
        val endMinute: Int,
        val isRoutine: Boolean,
        val orderId: Long,
        val toItem: (linkAbove: Link, linkBelow: Link) -> Item,
    )
}
