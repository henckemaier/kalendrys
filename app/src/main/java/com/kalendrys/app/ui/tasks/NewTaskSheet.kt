@file:OptIn(ExperimentalMaterial3Api::class)

package com.kalendrys.app.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kalendrys.app.R
import com.kalendrys.app.domain.NewTaskDraft
import com.kalendrys.app.ui.components.isCompactHeight
import com.kalendrys.app.ui.components.windowHeightDp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Painel de criação de tarefa, usado pela aba Tarefas e pelo Calendário. SEM estado próprio
 * (v1.7.1): mostra o [draft] guardado no NewTaskDraftStore e devolve as mudanças por [onChange].
 *
 * Fechar pelo voltar, tocando fora ou arrastando para baixo ([onDismiss]) GUARDA o rascunho;
 * [onCancel] o descarta; [onSave] grava a tarefa e o descarta (quem chama decide, aqui só avisa).
 *
 * "Adicionar lista" (v1.10.0, ADR-0002 16.10.4): só marca o rascunho; a lista nasce ao salvar, com o
 * nome da tarefa (P46). Sem seletor e sem lista existente.
 */
@Composable
internal fun NewTaskSheet(
    draft: NewTaskDraft,
    onChange: ((NewTaskDraft) -> NewTaskDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    // Foco no Título só na PRIMEIRA abertura (v1.9.0): salvo, para girar a tela ou recriar o painel não
    // tirar o foco do campo em que a pessoa está. Fechar e reabrir é outra abertura (estado novo).
    var titleFocusRequested by rememberSaveable { mutableStateOf(false) }

    fun hideThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    // SÓ OS CAMPOS ROLAM (v1.8.0, O-6): a coluna dos campos tem peso e Cancelar/Salvar ficam FORA da
    // rolagem nas duas alturas. Altura normal (L29): título fixo no topo (rolando, o foco no Título o
    // escondia sob a alça) e Cancelar/Salvar fixos no FIM do painel, logo acima do teclado. Altura
    // COMPACTA (o mesmo corte que recolhe o FAB; E6, 14.7, fecha L29 e O-1): título, Cancelar e Salvar
    // numa linha FIXA no topo, e sem a alça, que gastaria a altura que falta (arrastar para fechar
    // continua valendo).
    //
    // O teclado é descontado UMA vez, pelo próprio ModalBottomSheet (material3 1.4.0): a janela dele não
    // se redimensiona (adjustNothing a partir da API 30, decorFitsSystemWindows false), a caixa do painel
    // leva imePadding e o conteúdo recebe os insets de safeDrawing já sem a parte do teclado. Por isso
    // nada aqui soma imePadding: o que escondia o Salvar era ele estar no fim da rolagem.
    val compact = isCompactHeight(windowHeightDp())
    val cancel = { hideThen(onCancel) }
    val save = { hideThen(onSave) }
    // Teclado aberto, lido DENTRO do painel (a janela dele) e subido para cá.
    var imeOpen by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = if (compact) null else ({ BottomSheetDefaults.DragHandle() }),
        // Arrastar NÃO fecha em altura compacta com o teclado aberto (v1.9.0, 15.7): com os campos no topo
        // da rolagem, um arrasto rápido para baixo passava ao painel e o fechava no meio da digitação
        // (reproduzido no emulador em paisagem). Voltar, tocar fora e Cancelar seguem fechando.
        sheetGesturesEnabled = sheetDragEnabled(compact = compact, imeOpen = imeOpen),
    ) {
        // Dentro do painel: o teclado lido aqui é o da janela do painel, não o da Activity.
        val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
        SideEffect { imeOpen = imeBottom > 0 }
        val keepTitleVisible = rememberKeepVisibleWhileFocused()
        val keepNotesVisible = rememberKeepVisibleWhileFocused()
        val keepLocationVisible = rememberKeepVisibleWhileFocused()
        if (compact) {
            CompactTopRow(canSave = draft.canSave, onCancel = cancel, onSave = save)
        } else {
            Text(
                stringResource(R.string.new_task),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    // Cabeçalho do painel para o leitor de tela (E7, 14.9).
                    .semantics { heading() }
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 16.dp),
            )
        }
        Column(
            modifier = Modifier
                // Peso sem preencher: com espaço sobrando o painel continua do tamanho do conteúdo;
                // sem espaço (teclado, janela baixa) é esta coluna que encolhe e rola, não os botões.
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = if (compact) 24.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = draft.title,
                onValueChange = { value -> onChange { it.copy(title = value) } },
                label = { Text(stringResource(R.string.task_title_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .then(keepTitleVisible.modifier),
            )
            TaskTimingField(
                timing = draft.timing,
                onTimingChange = { changed -> onChange { it.withTiming(changed) } },
            )
            OutlinedTextField(
                value = draft.notes,
                onValueChange = { value -> onChange { it.copy(notes = value) } },
                label = { Text(stringResource(R.string.task_notes_label)) },
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(keepNotesVisible.modifier),
            )
            LocationField(
                value = draft.location,
                onValueChange = { value -> onChange { it.copy(location = value) } },
                modifier = keepLocationVisible.modifier,
            )
            NewTaskListField(
                withList = draft.withList,
                title = draft.title,
                onAdd = { onChange { it.copy(withList = true) } },
                onClear = { onChange { it.copy(withList = false) } },
            )
        }
        if (!compact) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = cancel) {
                    Text(stringResource(R.string.cancel))
                }
                Button(onClick = save, enabled = draft.canSave) {
                    Text(stringResource(R.string.save))
                }
            }
        }
        LaunchedEffect(Unit) {
            if (!titleFocusRequested) {
                titleFocusRequested = true
                focusRequester.requestFocus()
            }
        }
    }

}

/** Arrastar move/fecha o painel? Não em altura compacta com o teclado aberto (v1.9.0, 15.7). */
internal fun sheetDragEnabled(compact: Boolean, imeOpen: Boolean): Boolean = !(compact && imeOpen)

/**
 * Traz o campo em foco para a área visível da rolagem (O-6) também quando a área ENCOLHE depois do foco:
 * o campo pede para aparecer ao ganhar o foco, mas o teclado sobe em seguida e encolhe o painel, e aí
 * ninguém pede de novo. Aqui, enquanto o campo tem o foco, cada mudança na altura do teclado pede outra
 * vez. Campo mais alto que a área aparece pelo começo (regra da rolagem).
 *
 * Devolve um [KeepVisibleWhileFocused] (use [KeepVisibleWhileFocused.modifier]) em vez do `Modifier`
 * direto: função que devolve `Modifier` sem ser extensão dele dispara o lint
 * ModifierFactoryExtensionFunction, e um `Modifier.Node` não leria o teclado (`WindowInsets.ime` só
 * existe na composição).
 */
@Composable
private fun rememberKeepVisibleWhileFocused(): KeepVisibleWhileFocused {
    val keep = remember { KeepVisibleWhileFocused() }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(keep.focused) {
        if (!keep.focused) return@LaunchedEffect
        snapshotFlow { ime.getBottom(density) }.collectLatest { keep.requester.bringIntoView() }
    }
    return keep
}

/** Estado de [rememberKeepVisibleWhileFocused]: o pedido de aparecer e o foco do campo. */
@Stable
private class KeepVisibleWhileFocused {
    val requester = BringIntoViewRequester()
    var focused by mutableStateOf(false)
        private set

    /** Vai no campo: pede para aparecer e acompanha o foco. */
    val modifier: Modifier = Modifier
        .bringIntoViewRequester(requester)
        .onFocusChanged { focused = it.hasFocus }
}

/**
 * Linha fixa do painel em altura compacta (E6): título, Cancelar e, por ÚLTIMO, Salvar (regra da barra,
 * 12.4). Quem cede espaço com fonte grande é o TÍTULO (reticências); os botões ficam com a largura
 * deles e nunca cortam.
 */
@Composable
private fun CompactTopRow(canSave: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 16.dp, bottom = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.new_task),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Cabeçalho também na linha compacta; com reticências o nó continua com o texto inteiro.
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.cancel), maxLines = 1, softWrap = false)
        }
        Button(onClick = onSave, enabled = canSave) {
            Text(stringResource(R.string.save), maxLines = 1, softWrap = false)
        }
    }
}

/**
 * Lista da nova tarefa: botão "Adicionar lista" ou a lista que vai nascer ("Nova lista: <título>", o
 * nome acompanha o título digitado) com a ação de remover.
 */
@Composable
private fun NewTaskListField(
    withList: Boolean,
    title: String,
    onAdd: () -> Unit,
    onClear: () -> Unit,
) {
    if (!withList) {
        TextButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.list_card_add))
        }
        return
    }
    val label = stringResource(R.string.list_card_new_pending, title.trim())
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, end = 4.dp),
        ) {
            Icon(
                Icons.Outlined.ShoppingCart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            )
            IconButton(onClick = onClear) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.list_card_remove_choice))
            }
        }
    }
}

/** Local opcional (endereço ou nome do lugar); [trailing] serve para o atalho do mapa no detalhe. */
@Composable
internal fun LocationField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.task_location_label)) },
        singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Place, contentDescription = null) },
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = modifier.fillMaxWidth(),
    )
}
