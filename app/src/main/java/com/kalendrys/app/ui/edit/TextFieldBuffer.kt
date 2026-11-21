package com.kalendrys.app.ui.edit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Buffer local de UM campo de texto do salvar na hora (v1.9.0, ADR-0002 15.3). Peça comum do detalhe
 * da tarefa (E2) e do editor de rotina (E3); não crie outra.
 *
 * - Enquanto a pessoa digita, o campo mostra o que ela digitou ([text]), não o que o banco reemite:
 *   sem isso o cursor pula e teclas se perdem.
 * - Grava [debounceMillis] depois da última tecla e SEMPRE em [flush] (o ViewModel chama ao sair da
 *   tela, ao ir para segundo plano e no onCleared). A gravação roda em [writeScope], o escopo do app
 *   ([com.kalendrys.app.KalendrysApp.editScope]), para não morrer com a tela.
 * - Texto que mudou por fora ([onStored]) só entra no campo se não houver digitação pendente, e o
 *   eco da própria gravação (o mesmo texto depois de [normalize]) não troca o campo.
 * - Valor que [isValid] recusa (título vazio) não é gravado. O campo fica com ele (a tela mostra o
 *   erro) até a pessoa corrigir ou sair; ao reabrir a tela, o campo vem do banco, com o último valor
 *   válido (5.3).
 *
 * Chame [onType], [onStored] e [flush] da thread principal (ViewModel/tela). Só a conclusão da gravação
 * corre em [writeScope]; ela mexe apenas no marcador atômico [pending].
 */
class TextFieldBuffer(
    /** Escopo do temporizador de espera (o viewModelScope): fechar a tela cancela só a espera. */
    private val timerScope: CoroutineScope,
    /** Escopo das gravações (o do app): sobrevive à tela. */
    private val writeScope: CoroutineScope,
    /** Se o valor pode ser gravado (título: não vazio). Recusado não grava. */
    private val isValid: (String) -> Boolean = { true },
    /**
     * A mesma normalização que a gravação aplica (o edit apara título, observações e local). Serve
     * para o buffer reconhecer o PRÓPRIO eco ([onStored]): sem isso, o banco devolvia o texto aparado e
     * apagava o espaço ou a quebra de linha do fim enquanto a pessoa digitava (A1 da revisão da E3).
     */
    private val normalize: (String) -> String = { it.trim() },
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
    /**
     * Dispara a gravação de fato (o edit do repositório) e devolve o trabalho dela. É chamada de forma
     * SÍNCRONA no [flush]: quem enfileira gravações em ordem (o editor de rotina) enfileira aqui mesmo,
     * e o fim do ViewModel pode dar [flush] e logo depois fechar a fila sem perder o texto.
     */
    private val write: (String) -> Job,
) {
    private val _text = MutableStateFlow<String?>(null)

    /** O que o campo mostra; `null` até o primeiro valor do banco ([onStored]). */
    val text: StateFlow<String?> = _text.asStateFlow()

    /**
     * Digitação ainda não confirmada no banco (`null` = nenhuma). Enquanto houver, o banco não
     * sobrescreve o campo. Atômico: a gravação termina em [writeScope].
     */
    private val pending = MutableStateFlow<String?>(null)

    private var timer: Job? = null

    /** Há digitação que ainda não foi gravada (ou que foi recusada por [isValid]). */
    val hasPending: Boolean get() = pending.value != null

    /**
     * Há digitação pendente que [isValid] recusa (título vazio). Diferente de [hasPending], que segue
     * verdadeiro enquanto um valor válido grava: quem sai da tela só devolve ao campo o recusado.
     */
    val hasRejectedPending: Boolean get() = pending.value?.let { !isValid(it) } ?: false

    /**
     * O banco reemitiu [value]. Entra no campo só sem digitação pendente E se for diferente do que o
     * campo mostra depois de normalizado: o eco da própria gravação ("Comprar " gravado como
     * "Comprar") não troca o campo, e uma mudança de fora de verdade entra.
     */
    fun onStored(value: String) {
        if (pending.value != null) return
        val current = _text.value
        if (current != null && normalize(current) == value) return
        _text.value = value
    }

    /** A pessoa digitou: o campo mostra na hora e a gravação espera [debounceMillis] sem tecla. */
    fun onType(value: String) {
        _text.value = value
        pending.value = value
        timer?.cancel()
        timer = timerScope.launch {
            delay(debounceMillis)
            flush()
        }
    }

    /**
     * Grava já o que está pendente, no escopo do app. Valor recusado por [isValid] não é gravado e
     * continua pendente (o banco não o sobrescreve enquanto a tela estiver aberta).
     */
    fun flush() {
        timer?.cancel()
        timer = null
        val value = pending.value ?: return
        if (!isValid(value)) return
        val job = write(value)
        writeScope.launch {
            job.join()
            // Só limpa depois de gravado (o banco já reemitiu o valor novo) e se não houve tecla nova
            // enquanto gravava; senão a nova continua pendente.
            pending.compareAndSet(value, null)
        }
    }

    /**
     * Esquece a digitação pendente e volta o campo para [value] (o que está no banco). Para o
     * "Reverter alterações", que grava o retrato pelo repositório e não pelo buffer.
     */
    fun reset(value: String) {
        timer?.cancel()
        timer = null
        pending.value = null
        _text.value = value
    }

    companion object {
        /** Espera depois da última tecla (5.2). */
        const val DEBOUNCE_MILLIS = 500L
    }
}
