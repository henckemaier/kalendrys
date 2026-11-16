@file:OptIn(ExperimentalMaterial3Api::class)

package com.kalendrys.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.kalendrys.app.R
import com.kalendrys.app.ui.theme.KalendrysTheme

/**
 * Ação da barra de topo. Com [icon], aparece como botão de ícone ([label] vira a descrição para o
 * leitor de tela); sem ícone, ou quando passada em `menu`, vai para o menu de mais opções, com o
 * rótulo escrito. Com [prominent] (em `actions`), aparece como botão de texto com o [label], depois
 * dos ícones e antes do menu — para a ação principal de um editor, como "Salvar" (o [icon] é
 * ignorado).
 */
@Immutable
data class TopBarAction(
    val label: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val prominent: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Barra de topo pequena (ADR-0002, 4.2): substitui a `LargeTopAppBar` das abas e os ícones soltos
 * dos editores. Título numa linha (com reticências em fonte grande), voltar opcional, ações
 * frequentes como ícones e as raras num menu com rótulo. Fixa no topo (sem recolher ao rolar).
 */
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: List<TopBarAction> = emptyList(),
    menu: List<TopBarAction> = emptyList(),
) {
    TopAppBar(
        title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            }
        },
        actions = {
            actions.filterNot { it.prominent }.forEach { action ->
                val icon = action.icon
                if (icon != null) {
                    IconButton(onClick = action.onClick, enabled = action.enabled) {
                        Icon(icon, contentDescription = action.label)
                    }
                }
            }
            actions.filter { it.prominent }.forEach { action ->
                TextButton(onClick = action.onClick, enabled = action.enabled) { Text(action.label) }
            }
            val inMenu = menu + actions.filter { it.icon == null && !it.prominent }
            if (inMenu.isNotEmpty()) OverflowMenu(inMenu)
        },
        colors = TopAppBarDefaults.topAppBarColors(),
    )
}

@Composable
private fun OverflowMenu(items: List<TopBarAction>) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    enabled = item.enabled,
                    onClick = {
                        open = false
                        item.onClick()
                    },
                )
            }
        }
    }
}

@KalendrysPreviews
@Composable
private fun AppTopBarPreview() {
    KalendrysTheme {
        Surface {
            AppTopBar(
                title = "Mercado do mês com um nome comprido",
                onBack = {},
                actions = listOf(TopBarAction("Configurações", Icons.Outlined.Settings) {}),
                menu = listOf(TopBarAction("Renomear") {}, TopBarAction("Excluir lista") {}),
            )
        }
    }
}
