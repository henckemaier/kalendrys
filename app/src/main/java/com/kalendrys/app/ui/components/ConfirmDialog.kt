package com.kalendrys.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kalendrys.app.R
import com.kalendrys.app.ui.theme.KalendrysTheme

/**
 * Confirmação de ação destrutiva sem Desfazer (ADR-0002, 4.2), ex.: excluir uma lista inteira.
 * O botão de confirmar sai na cor de erro. Sem estado: quem chama decide quando mostrar e fecha
 * o diálogo nos dois callbacks ([onDismiss] vale para "Cancelar", voltar e toque fora).
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = stringResource(R.string.cancel),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        },
    )
}

@KalendrysPreviews
@Composable
private fun ConfirmDialogPreview() {
    KalendrysTheme {
        Surface {
            ConfirmDialog(
                title = "Excluir lista?",
                text = "A lista \"Mercado\" e os 8 itens dela serão apagados.",
                confirmLabel = "Excluir",
                onConfirm = {},
                onDismiss = {},
            )
        }
    }
}
