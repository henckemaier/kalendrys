package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Task
import java.time.ZoneId

/**
 * Uma alteração de UMA tarefa já criada, como o detalhe a envia no salvar na hora (ADR-0002, 15.2).
 * A tela não guarda cópia da tarefa: manda mudanças, e o repositório aplica cada uma sobre a linha
 * MAIS RECENTE do banco ([com.kalendrys.app.data.repository.TaskRepository.edit]). Um caso por campo
 * ou grupo de campos; data, hora e repetição são UM caso ([Timing]), nunca aplicado pela metade.
 *
 * Cada caso sabe se aplicar ([applyTo]) e dizer se o resultado é válido (regras da 5.3): `null` =
 * inválida, não grava nada. Lógica pura, testada na JVM.
 */
sealed interface TaskChange {

    /**
     * Mexe no que o alarme do lembrete usa (data, hora, repetição). Só esses casos fecham a janela do
     * catch-up e refazem o alarme; texto e lista não tocam em alarme (15.2).
     */
    val touchesReminder: Boolean get() = false

    /** A tarefa com a mudança aplicada, ou `null` se a mudança é inválida (não grava). */
    fun applyTo(task: Task, zone: ZoneId): Task?

    /** Título aparado. Vazio nunca é gravado: o banco mantém o último título válido (5.3). */
    data class Title(val value: String) : TaskChange {
        override fun applyTo(task: Task, zone: ZoneId): Task? =
            value.trim().takeIf { it.isNotEmpty() }?.let { task.copy(title = it) }
    }

    data class Notes(val value: String) : TaskChange {
        override fun applyTo(task: Task, zone: ZoneId): Task = task.copy(notes = value.trim())
    }

    data class Location(val value: String) : TaskChange {
        override fun applyTo(task: Task, zone: ZoneId): Task = task.copy(location = value.trim())
    }

    /**
     * Data, horário do lembrete e repetição juntos ([TaskTiming]). Repetição personalizada sem
     * nenhum dia utilizável ([CustomRecurrence.isUsable]) é inválida: a tela não deve chegar nela, e
     * se chegar nada é gravado.
     */
    data class Timing(val timing: TaskTiming) : TaskChange {
        override val touchesReminder: Boolean get() = true

        override fun applyTo(task: Task, zone: ZoneId): Task? =
            timing.takeIf { it.isValid }?.applyTo(task, zone)
    }

    /** Vincula a uma lista EXISTENTE ([listId]) ou desvincula (`null`). */
    data class ShoppingList(val listId: Long?) : TaskChange {
        override fun applyTo(task: Task, zone: ZoneId): Task = task.copy(shoppingListId = listId)
    }

    /**
     * Cria uma lista nova com [name] e vincula a tarefa a ela, numa transação só no repositório (o id
     * só existe depois de gravar a lista). Aqui só a validação: nome em branco não cria lista.
     */
    data class NewShoppingList(val name: String) : TaskChange {
        override fun applyTo(task: Task, zone: ZoneId): Task? = task.takeIf { name.isNotBlank() }
    }
}

/**
 * Quando a repetição personalizada ([ReminderRepeat.CUSTOM]) tem como acontecer, ESPELHANDO o
 * [ReminderRecurrence]: com dias marcados, algum deles entre os sete (bits 0..6); sem dias, um
 * intervalo com unidade (o intervalo menor que 1 conta como 1 lá, então não invalida aqui). É falso
 * se e somente se ela nunca ocorre.
 *
 * Uma regra só para a escrita ([TaskTiming.isValid], que o [TaskChange.Timing] usa para recusar; o
 * [TaskTiming.applyTo] ainda grava o intervalo como no mínimo 1) e para a leitura tolerante
 * ([TaskTiming.of], que mostra o dado antigo que nunca ocorre como "não repete").
 */
object CustomRecurrence {
    fun isUsable(unit: RecurrenceUnit?, byWeekdays: Int): Boolean =
        if (byWeekdays != 0) byWeekdays and RoutineDays.ALL != 0 else unit != null
}
