package com.kalendrys.app

import com.kalendrys.app.domain.NewTaskDraft
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * Dono único do rascunho do painel de nova tarefa (ADR-0002, 13; v1.7.1), criado no [KalendrysApp]
 * e compartilhado pela aba Tarefas e pelo Calendário; desde a v1.8.0 (E6) mora dentro do [NewTaskPanel],
 * que salva e observa as listas. Este continua o estado puro. Vive enquanto o processo viver: se o sistema
 * matar o processo, o rascunho se perde (perda aceita, sem persistência).
 *
 * Regras: fechar o painel (voltar, tocar fora, arrastar) GUARDA; Cancelar e Salvar DESCARTAM.
 * `null` = não há rascunho.
 */
class NewTaskDraftStore {
    private val _draft = MutableStateFlow<NewTaskDraft?>(null)
    val draft: StateFlow<NewTaskDraft?> = _draft.asStateFlow()

    /** O painel abriu sugerindo [date]: cria o rascunho ou devolve o guardado (ver [NewTaskDraft.openedOn]). */
    fun open(date: LocalDate?) = _draft.update { it?.openedOn(date) ?: NewTaskDraft.startingOn(date) }

    /** Mudança vinda do painel; sem rascunho aberto (já descartado) é ignorada. */
    fun update(transform: (NewTaskDraft) -> NewTaskDraft) = _draft.update { it?.let(transform) }

    /** Descarta e devolve o rascunho (Cancelar devolve e esquece; Salvar grava o devolvido). */
    fun discard(): NewTaskDraft? = _draft.getAndUpdate { null }

    /** Lista excluída enquanto escolhida no rascunho some da escolha. */
    fun keepListsIn(existingListIds: Set<Long>) = update { it.keepingListsIn(existingListIds) }
}
