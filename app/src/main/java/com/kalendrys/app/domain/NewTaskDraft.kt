package com.kalendrys.app.domain

import java.time.LocalDate

/**
 * Lista de compras escolhida no painel de nova tarefa (ADR-0001, P2). "Criar nova" só cria a lista
 * ao salvar a tarefa: cancelar o painel não deixa lista avulsa para trás.
 */
sealed interface NewTaskList {
    data class Existing(val listId: Long) : NewTaskList
    data class New(val name: String) : NewTaskList
}

/**
 * Rascunho do painel de nova tarefa (ADR-0002, 13; v1.7.1). Só memória: nada vai para o banco,
 * DataStore ou backup. Um rascunho só para a aba Tarefas e o Calendário.
 *
 * [dateEdited] diz se o usuário já mexeu na data: enquanto não mexeu, abrir o painel de outro
 * lugar (o dia tocado no Calendário, ou hoje na aba Tarefas) troca a data; depois, a dele vale.
 */
data class NewTaskDraft(
    val title: String = "",
    val notes: String = "",
    val location: String = "",
    val timing: TaskTiming = TaskTiming(date = null),
    val list: NewTaskList? = null,
    val dateEdited: Boolean = false,
) {
    val canSave: Boolean get() = title.isNotBlank()

    /** Troca o horário/repetição; mudar a data marca o rascunho como "data escolhida pelo usuário". */
    fun withTiming(changed: TaskTiming): NewTaskDraft =
        copy(timing = changed, dateEdited = dateEdited || changed.date != timing.date)

    /** O painel abriu sugerindo [date]: vale só se o usuário ainda não escolheu a data. */
    fun openedOn(date: LocalDate?): NewTaskDraft =
        if (dateEdited) this else copy(timing = timing.copy(date = date))

    /**
     * Lista existente escolhida que já não está entre [existingListIds] (excluída no meio) some da
     * escolha, para a tarefa não nascer com vínculo órfão. Lista nova pendente não é afetada.
     */
    fun keepingListsIn(existingListIds: Set<Long>): NewTaskDraft {
        val chosen = list as? NewTaskList.Existing ?: return this
        return if (chosen.listId in existingListIds) this else copy(list = null)
    }

    companion object {
        fun startingOn(date: LocalDate?) = NewTaskDraft(timing = TaskTiming(date = date))
    }
}
