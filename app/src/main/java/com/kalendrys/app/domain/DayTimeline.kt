package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Routine
import java.time.LocalDate
import java.time.ZoneId

/**
 * A linha do tempo de um dia: os blocos de rotina em ordem e os intervalos livres entre eles.
 * Lógica pura, para a tela de rotinas desenhar sem calcular nada.
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
    }

    data class Block(
        val routine: Routine,
        val done: Boolean,
        val linkAbove: Link,
        val linkBelow: Link,
    ) : Item {
        val startMinute: Int get() = routine.startMinute

        /** Pode passar de 1440 (bloco que termina depois da meia-noite). */
        val endMinute: Int get() = routine.endMinute
        override val key: String get() = "routine_${routine.id}"
    }

    data class Gap(val startMinute: Int, val minutes: Int) : Item {
        override val key: String get() = "gap_$startMinute"
    }

    /** Intervalos menores que isso não ganham linha própria; a ligação só fica tracejada. */
    const val MIN_GAP_MINUTES = 30

    fun build(routines: List<Routine>, doneIds: Set<Long>, date: LocalDate, zone: ZoneId): List<Item> {
        val day = routines
            .filter { RoutineSchedule.occursOn(it, date, zone) }
            .sortedWith(compareBy({ it.startMinute }, { it.id }))
        if (day.isEmpty()) return emptyList()

        // Um bloco "encosta" no anterior se começa antes de tudo o que veio antes terminar.
        val contiguous = BooleanArray(day.size)
        val gapBefore = IntArray(day.size)
        var latestEnd = day.first().endMinute
        for (i in 1 until day.size) {
            val start = day[i].startMinute
            contiguous[i] = start <= latestEnd
            gapBefore[i] = (start - latestEnd).coerceAtLeast(0)
            latestEnd = maxOf(latestEnd, day[i].endMinute)
        }

        val items = ArrayList<Item>(day.size * 2)
        day.forEachIndexed { i, routine ->
            if (i > 0 && gapBefore[i] >= MIN_GAP_MINUTES) {
                items += Gap(startMinute = routine.startMinute - gapBefore[i], minutes = gapBefore[i])
            }
            items += Block(
                routine = routine,
                done = routine.id in doneIds,
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
