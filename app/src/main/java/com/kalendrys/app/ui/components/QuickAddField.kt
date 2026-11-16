package com.kalendrys.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.kalendrys.app.ui.theme.KalendrysTheme
import com.kalendrys.app.ui.theme.Spacing

/**
 * Campo de adição rápida, fixo no rodapé (ADR-0002, 4.2; C1): digita, Enter adiciona, e o campo
 * fica limpo, com o foco e o teclado, pronto para o próximo ("5 itens seguidos sem fechar o
 * teclado", aceite da E4). Texto em branco não adiciona nada (e não limpa o que houver).
 *
 * O texto é do chamador ([value]/[onValueChange]), como num `TextField`: o componente não guarda
 * estado. Assim a tela decide quanto ele dura (um `rememberSaveable` na tela sobrevive a rotação e
 * a troca de aba; limpar ao trocar de lista é só zerar o estado) e os testes controlam o valor. O
 * componente só limpa (via [onValueChange]) depois de chamar [onAdd], com o texto sem espaços nas
 * pontas.
 *
 * A posição no rodapé fica com o chamador. Sem `imePadding` próprio: o `AppScaffold` usa
 * `WindowInsets.safeDrawing`, que já inclui o teclado (recuar de novo contaria duas vezes).
 * O botão ao fim faz o mesmo que o Enter, para quem não usa o teclado na tela (leitor de tela,
 * teclado físico sem Enter mapeado); [addLabel] é o nome dele para o leitor.
 */
@Composable
fun QuickAddField(
    value: String,
    onValueChange: (String) -> Unit,
    onAdd: (String) -> Unit,
    placeholder: String,
    addLabel: String,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val submit: () -> Unit = {
        val text = value.trim()
        if (text.isNotEmpty()) {
            onAdd(text)
            onValueChange("")
        }
        // O botão pode tirar o foco do campo; o Enter não o tira. Nos dois casos ele volta/fica.
        focusRequester.requestFocus()
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        // Tratar o Done aqui substitui o padrão (que fecharia o teclado): o foco e o teclado ficam.
        keyboardActions = KeyboardActions(onDone = { submit() }),
        trailingIcon = {
            IconButton(onClick = submit, enabled = value.isNotBlank()) {
                Icon(Icons.Outlined.Add, contentDescription = addLabel)
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
    )
}

@KalendrysPreviews
@Composable
private fun QuickAddFieldPreview() {
    KalendrysTheme {
        Surface {
            var text by remember { mutableStateOf("Leite") }
            QuickAddField(
                value = text,
                onValueChange = { text = it },
                onAdd = {},
                placeholder = "Adicionar item",
                addLabel = "Adicionar",
                modifier = Modifier.padding(Spacing.l),
            )
        }
    }
}
