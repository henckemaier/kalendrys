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
 * Honra as exceções pontuais (T20): uma `RESCHEDULE` entra (ou sai) no horário/dia efetivo — por isso
 * cada [Block] carrega o seu início já resolvido. Uma ocorrência com `SKIP` some do dia, a não ser
 * com `includeSkipped` (a Hoje, P5 da v1.8.0): aí o bloco PULADO continua na lista, marcado
 * ([Block.skipped]), no horário normal, para a tela o mostrar esmaecido com a ação de desfazer.
 *
 * O bloco pulado não ocupa o lugar de bloco ativo: não entra nas contas de intervalo ([Gap]) nem de
 * ligação ([Link]) dos outros, como se não existisse; as ligações dele mesmo são [Link.NONE]. Fica
 * na ordem pelo início, depois dos itens que começam no mesmo minuto. Quem conta o dia ("próximo
 * item", progresso, agrupamento) deve ignorar os pulados ([Item.isActive]).
 *
 * TAREFA ANINHADA (v1.10.0, ADR-0002 16.10.8, P47-P50): uma tarefa com horário DENTRO de um bloco ativo
 * com duração (início do bloco <= horário < fim do bloco: o início conta, o fim não) é marcada com o
 * bloco ([TaskItem.container]); entre blocos sobrepostos, fica com o que começou por último (empate: o
 * que vem depois na trilha). Bloco pulado e bloco sem duração não contêm nada. O bloco que contém
 * tarefas diz quantas ([Block.nestedTasks]) e ganha, logo depois da última delas, um [BlockEnd] com a
 * hora de fim, para a faixa do bloco se alongar por cima delas. A ordem continua a do horário; as
 * tarefas continuam [TaskItem] (contagens, Feitos e Desfazer não mudam).
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

        /** Conta no dia (não é um bloco pulado): o que "próximo item", progresso e agrupamento olham. */
        val isActive: Boolean get() = !(this is Block && skipped)
    }

    data class Block(
        val routine: Routine,
        val done: Boolean,
        /** Início efetivo no dia (honra uma remarcação), em minutos desde a meia-noite. */
        override val startMinute: Int,
        val linkAbove: Link,
        val linkBelow: Link,
        /**
         * Dia da OCORRÊNCIA: o dia em que mora a exceção dela (`RoutineOverride.epochDay`). É o dia
         * de ORIGEM quando o bloco veio remarcado de outro dia (A1 do Esquadro na E3); nos outros casos,
         * o próprio dia mostrado. Pular, Remarcar e Desfazer pular agem neste dia, não no mostrado.
         */
        val occurrenceDate: LocalDate,
        /** Ocorrência pulada (`SKIP`) naquele dia: só vem com `includeSkipped`; ver [DayTimeline]. */
        val skipped: Boolean = false,
        /** Quantas tarefas estão dentro deste bloco (v1.10.0): com alguma, a hora de fim vai no [BlockEnd]. */
        val nestedTasks: Int = 0,
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
        /** A rotina do bloco em que a tarefa está DENTRO (v1.10.0); null = solta na trilha. */
        val container: Routine? = null,
    ) : Item {
        /** Ponto no tempo: termina onde começa. */
        val endMinute: Int get() = startMinute
        override val key: String get() = "task_${task.id}"
    }

    /**
     * Fim de um bloco que contém tarefas (v1.10.0): vem logo depois da última tarefa de dentro e leva a
     * hora de fim ([startMinute] = fim do bloco) para baixo delas. Não é um item do dia: não conta
     * ([isActive] falso) e não tem ação; só desenha o fim da faixa.
     */
    data class BlockEnd(
        val routine: Routine,
        override val startMinute: Int,
        val linkBelow: Link,
    ) : Item {
        override val key: String get() = "routine_end_${routine.id}"
        override val isActive: Boolean get() = false
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
        includeSkipped: Boolean = false,
    ): List<Item> {
        // Cada rotina (já honrando SKIP/RESCHEDULE) e cada tarefa com horário, como pontos [start, end]
        // na mesma trilha: a rotina ocupa a sua duração, a tarefa é um ponto (end = start).
        val entries = ArrayList<Entry>(routines.size + tasks.size)
        // Pulados ficam à parte: entram na lista no fim, sem mexer em intervalos e ligações.
        val skippedBlocks = ArrayList<Block>()
        routines.forEach { routine ->
            val ov = overrides[routine.id].orEmpty()
            if (!RoutineSchedule.occursOn(routine, date, zone, ov)) {
                if (includeSkipped && isSkippedOn(routine, date, zone, ov)) {
                    skippedBlocks += Block(
                        routine = routine,
                        done = routine.id in doneIds,
                        startMinute = routine.startMinute,
                        linkAbove = Link.NONE,
                        linkBelow = Link.NONE,
                        // O SKIP mora no próprio dia mostrado.
                        occurrenceDate = date,
                        skipped = true,
                    )
                }
                return@forEach
            }
            val start = RoutineSchedule.startOn(routine, date, ov)
            val startMinute = start.hour * 60 + start.minute
            val occurrenceDate = occurrenceDateOf(date, ov)
            entries += Entry(
                startMinute = startMinute,
                endMinute = startMinute + routine.durationMinutes,
                isRoutine = true,
                orderId = routine.id,
            ) { above, below -> Block(routine, routine.id in doneIds, startMinute, above, below, occurrenceDate) }
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
        if (entries.isEmpty()) return withSkipped(emptyList(), skippedBlocks)
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
        return withSkipped(nest(items), skippedBlocks)
    }

    /**
     * Bloco que contém a tarefa [task], pela regra da tarefa aninhada: início dentro e fim fora; entre
     * sobrepostos, o que começou por último (empate: o último na trilha). Pulado ou sem duração, nenhum.
     */
    internal fun containerOf(task: TaskItem, blocks: List<Block>): Block? =
        blocks.withIndex()
            .filter { (_, b) ->
                !b.skipped && b.routine.durationMinutes > 0 && task.startMinute >= b.startMinute && task.startMinute < b.endMinute
            }
            .maxWithOrNull(compareBy({ it.value.startMinute }, { it.index }))
            ?.value

    /** Marca as tarefas de dentro, conta as de cada bloco e põe o [BlockEnd] depois da última. */
    private fun nest(items: List<Item>): List<Item> {
        val blocks = items.filterIsInstance<Block>()
        if (blocks.isEmpty()) return items
        val containerOf = items.filterIsInstance<TaskItem>()
            .mapNotNull { task -> containerOf(task, blocks)?.let { task.key to it } }
            .toMap()
        if (containerOf.isEmpty()) return items
        val nestedCount = containerOf.values.groupingBy { it.key }.eachCount()
        val lastNested = containerOf.entries.groupBy({ it.value.key }, { it.key })
            .mapValues { (_, taskKeys) -> items.last { it.key in taskKeys }.key }
        val result = ArrayList<Item>(items.size + nestedCount.size)
        items.forEach { item ->
            when (item) {
                is Block -> result += item.copy(nestedTasks = nestedCount[item.key] ?: 0)
                is TaskItem -> {
                    val container = containerOf[item.key]
                    result += item.copy(container = container?.routine)
                    if (container != null && lastNested[container.key] == item.key) {
                        result += BlockEnd(container.routine, container.endMinute, item.linkBelow)
                    }
                }
                else -> result += item
            }
        }
        return result
    }

    /**
     * Dia da ocorrência que aparece em [date]: o de ORIGEM se ela veio remarcada de outro dia (a
     * mesma remarcação de entrada que o [RoutineSchedule.occursOn] e o [RoutineSchedule.startOn]
     * honram primeiro); senão, o próprio [date].
     */
    private fun occurrenceDateOf(date: LocalDate, overrides: List<RoutineOverride>): LocalDate {
        val epochDay = date.toEpochDay()
        val inbound = overrides.firstOrNull { it.type == RoutineOverrideType.RESCHEDULE && it.newEpochDay == epochDay }
        return if (inbound != null && inbound.epochDay != epochDay) LocalDate.ofEpochDay(inbound.epochDay) else date
    }

    /**
     * A ocorrência de [routine] em [date] foi pulada: aconteceria pela regra base, mas há um `SKIP`
     * naquele dia. Remarcada para outro dia não é "pulada" (ela aparece onde caiu).
     */
    private fun isSkippedOn(routine: Routine, date: LocalDate, zone: ZoneId, overrides: List<RoutineOverride>): Boolean {
        val epochDay = date.toEpochDay()
        val skip = overrides.firstOrNull { it.epochDay == epochDay }?.type == RoutineOverrideType.SKIP
        return skip && RoutineSchedule.occursOn(routine, date, zone)
    }

    /** Põe cada pulado na ordem pelo início, depois dos itens que começam no mesmo minuto (ou antes). */
    private fun withSkipped(items: List<Item>, skipped: List<Block>): List<Item> {
        if (skipped.isEmpty()) return items
        val result = items.toMutableList()
        skipped.sortedWith(compareBy({ it.startMinute }, { it.routine.id })).forEach { block ->
            val index = result.indexOfFirst { it.startMinute > block.startMinute }.let { if (it < 0) result.size else it }
            result.add(index, block)
        }
        return result
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
