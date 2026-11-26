package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Routine

/**
 * Uma alteração de UMA rotina já criada, como o editor a envia no salvar na hora (ADR-0002, 15.2).
 * O repositório aplica cada uma sobre a linha MAIS RECENTE do banco
 * ([com.kalendrys.app.data.repository.RoutineRepository.edit]): pausar, arquivar ou concluir por fora
 * com o editor aberto não são desfeitos. Um caso por campo ou grupo de campos.
 *
 * [applyTo] devolve `null` quando a mudança é inválida (regras da 5.3): nada é gravado. Lógica pura,
 * testada na JVM. A criação de rotina continua pelo rascunho do editor (15.5), fora daqui.
 */
sealed interface RoutineChange {

    /**
     * Mexe no que decide QUANDO a rotina avisa ou acorda o app: horário de início, dias, intervalo de
     * semanas, vigência e lembrar. Só esses casos passam pelo catch-up e reagendam (15.2); texto,
     * duração, cor, ícone, categoria e lista não tocam em alarme.
     */
    val touchesSchedule: Boolean get() = false

    /** A rotina com a mudança aplicada, ou `null` se a mudança é inválida (não grava). */
    fun applyTo(routine: Routine): Routine?

    /** Título aparado; vazio nunca é gravado (o banco mantém o último válido). */
    data class Title(val value: String) : RoutineChange {
        override fun applyTo(routine: Routine): Routine? =
            value.trim().takeIf { it.isNotEmpty() }?.let { routine.copy(title = it) }
    }

    data class Notes(val value: String) : RoutineChange {
        override fun applyTo(routine: Routine): Routine = routine.copy(notes = value.trim())
    }

    /** Minuto do início no dia (0..1439). */
    data class Start(val minuteOfDay: Int) : RoutineChange {
        override val touchesSchedule: Boolean get() = true

        override fun applyTo(routine: Routine): Routine? =
            routine.copy(startMinute = minuteOfDay).takeIf { minuteOfDay in 0 until MINUTES_PER_DAY }
    }

    /**
     * Duração em minutos (0 = sem duração; menos de um dia). Não reagenda: o alarme é só do início
     * ([com.kalendrys.app.reminder.RoutineAlarms]).
     */
    data class Duration(val minutes: Int) : RoutineChange {
        override fun applyTo(routine: Routine): Routine? =
            routine.copy(durationMinutes = minutes).takeIf { minutes in 0 until MINUTES_PER_DAY }
    }

    /** Dias da semana ([RoutineDays]); sem nenhum dia é inválido (o último dia não desmarca, 5.3). */
    data class Days(val mask: Int) : RoutineChange {
        override val touchesSchedule: Boolean get() = true

        override fun applyTo(routine: Routine): Routine? =
            (mask and RoutineDays.ALL).takeIf { it != 0 }?.let { routine.copy(daysOfWeek = it) }
    }

    /** "A cada N semanas", de 1 a [MAX_WEEK_INTERVAL]. */
    data class WeekInterval(val weeks: Int) : RoutineChange {
        override val touchesSchedule: Boolean get() = true

        override fun applyTo(routine: Routine): Routine? =
            routine.copy(weekInterval = weeks).takeIf { weeks in 1..MAX_WEEK_INTERVAL }
    }

    /**
     * Primeiro dia de vigência (epoch day) ou `null` (sem início). Mover o início para depois do fim
     * LIMPA o fim (5.3), em vez de recusar.
     */
    data class ValidFrom(val epochDay: Long?) : RoutineChange {
        override val touchesSchedule: Boolean get() = true

        override fun applyTo(routine: Routine): Routine {
            val end = routine.endEpochDay
            val clearsEnd = epochDay != null && end != null && epochDay > end
            return routine.copy(startEpochDay = epochDay, endEpochDay = if (clearsEnd) null else end)
        }
    }

    /** Último dia de vigência (epoch day) ou `null` (sem fim). Antes do início é inválido. */
    data class ValidUntil(val epochDay: Long?) : RoutineChange {
        override val touchesSchedule: Boolean get() = true

        override fun applyTo(routine: Routine): Routine? {
            val start = routine.startEpochDay
            if (epochDay != null && start != null && epochDay < start) return null
            return routine.copy(endEpochDay = epochDay)
        }
    }

    data class Remind(val remind: Boolean) : RoutineChange {
        override val touchesSchedule: Boolean get() = true

        override fun applyTo(routine: Routine): Routine = routine.copy(remind = remind)
    }

    /** Categoria ([com.kalendrys.app.data.local.entity.RoutineCategory.id]) ou `null`. */
    data class Category(val categoryId: Long?) : RoutineChange {
        override fun applyTo(routine: Routine): Routine = routine.copy(categoryId = categoryId)
    }

    data class Color(val color: RoutineColor) : RoutineChange {
        override fun applyTo(routine: Routine): Routine = routine.copy(color = color.name)
    }

    data class Icon(val icon: RoutineIcon) : RoutineChange {
        override fun applyTo(routine: Routine): Routine = routine.copy(icon = icon.name)
    }

    companion object {
        /** Mesmo teto do editor ([com.kalendrys.app.ui.routineeditor.RoutineDraft.MAX_WEEK_INTERVAL]). */
        const val MAX_WEEK_INTERVAL = 12
        private const val MINUTES_PER_DAY = 24 * 60
    }
}
