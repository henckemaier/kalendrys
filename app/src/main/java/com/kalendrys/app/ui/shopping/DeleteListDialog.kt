package com.kalendrys.app.ui.shopping

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.kalendrys.app.R
import com.kalendrys.app.ui.components.ConfirmDialog

/**
 * Confirmação de "Excluir lista" (v1.10.0, ADR-0002 16.10.4, P45): a lista é da tarefa, e excluir apaga
 * a lista e os itens, sem Desfazer. Mesmo diálogo no detalhe da tarefa e na tela da lista.
 */
@Composable
fun DeleteListDialog(
    name: String,
    itemCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.shopping_list_delete_title),
        text = pluralStringResource(R.plurals.shopping_list_delete_message, itemCount, name, itemCount),
        confirmLabel = stringResource(R.string.delete),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
