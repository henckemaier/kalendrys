package com.kalendrys.app.ui.shopping

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kalendrys.app.R
import com.kalendrys.app.data.repository.ShoppingListSummary

/**
 * Seletor "Criar nova" / "Usar lista existente" (ADR-0001, P2). Duas etapas no mesmo diálogo:
 * a escolha e, em "Criar nova", o nome (pré-preenchido com [suggestedName]; vazio cai no nome
 * padrão). A etapa e o nome sobrevivem a rotação.
 *
 * [lists] são as candidatas a "Usar existente"; cada uma mostra o progresso e os donos atuais,
 * porque usar uma lista já ligada a outro dono a compartilha (marcações e reinício valem para
 * todos).
 */
@Composable
fun ShoppingListPicker(
    lists: List<ShoppingListSummary>,
    suggestedName: String,
    onCreate: (name: String) -> Unit,
    onPick: (listId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    val defaultName = stringResource(R.string.list_picker_default_name)
    // TextFieldValue para abrir com o nome sugerido todo selecionado: digitar já substitui.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val initial = suggestedName.trim().ifEmpty { defaultName }
        mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length)))
    }
    val name = field.text

    if (creating) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.list_picker_new_title)) },
            text = {
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    label = { Text(stringResource(R.string.list_picker_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onCreate(name.trim()) }),
                    modifier = Modifier.fillMaxWidth().then(rememberFocusWhenShown()),
                )
            },
            confirmButton = {
                TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) {
                    Text(stringResource(R.string.list_picker_create_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { creating = false }) { Text(stringResource(R.string.back)) }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.list_picker_title)) },
        text = {
            Column {
                PickerRow(
                    title = stringResource(R.string.list_picker_create),
                    subtitle = null,
                    leading = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = { creating = true },
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = stringResource(R.string.list_picker_existing),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (lists.isEmpty()) {
                    Text(
                        text = stringResource(R.string.list_picker_no_lists),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(lists, key = { it.id }) { summary ->
                            PickerRow(
                                title = summary.name,
                                subtitle = listSubtitle(summary),
                                leading = { Icon(Icons.Outlined.ShoppingCart, contentDescription = null) },
                                onClick = { onPick(summary.id) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun listSubtitle(summary: ShoppingListSummary): String {
    val progress = summary.progress
    val status = if (progress.total == 0) {
        stringResource(R.string.list_card_empty)
    } else {
        stringResource(R.string.list_card_progress, progress.picked, progress.total)
    }
    val owners = if (summary.owners.isEmpty()) {
        stringResource(R.string.list_picker_standalone)
    } else {
        summary.owners.joinToString { it.title }
    }
    return "$status · $owners"
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String?,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp)) { leading() }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
