package com.kalendrys.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kalendrys.app.ui.theme.KalendrysTheme
import com.kalendrys.app.ui.theme.Spacing

/**
 * Estado vazio de uma tela (ADR-0002, 4.2): ícone, título, mensagem e, opcional, a ação principal
 * dentro (ex.: "Nova lista"). A ação só aparece com [actionLabel] e [onAction] juntos; sem eles o
 * componente fica igual ao de antes, então os usos atuais não mudam. Uma ação secundária
 * (botão de texto abaixo da principal) só aparece com [secondaryActionLabel] e [onSecondaryAction]
 * juntos (v1.8.0, E3: o vazio da Hoje oferece Nova tarefa e Nova rotina).
 *
 * Centralizado quando cabe; quando não cabe (paisagem, fonte grande), ROLA em vez de cortar: sem
 * isso, em paisagem de 360dp a ação de baixo ficava fora da tela (E3).
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Altura mínima = a área toda: o conteúdo fica no centro quando cabe e a rolagem só age quando falta espaço.
        val minHeight = maxHeight
        EmptyStateContent(
            icon = icon,
            title = title,
            message = message,
            actionLabel = actionLabel,
            actionIcon = actionIcon,
            onAction = onAction,
            secondaryActionLabel = secondaryActionLabel,
            onSecondaryAction = onSecondaryAction,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minHeight),
        )
    }
}

@Composable
private fun EmptyStateContent(
    icon: ImageVector,
    title: String,
    message: String,
    actionLabel: String?,
    actionIcon: ImageVector?,
    onAction: (() -> Unit)?,
    secondaryActionLabel: String?,
    onSecondaryAction: (() -> Unit)?,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .padding(horizontal = 40.dp)
            .padding(bottom = 64.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.height(Spacing.xl))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.s))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.xl))
            FilledTonalButton(onClick = onAction) {
                if (actionIcon != null) {
                    Icon(actionIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(Spacing.s))
                }
                Text(actionLabel, textAlign = TextAlign.Center)
            }
        }
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            Spacer(Modifier.height(Spacing.xs))
            TextButton(onClick = onSecondaryAction) {
                Text(secondaryActionLabel, textAlign = TextAlign.Center)
            }
        }
    }
}

@KalendrysPreviews
@Composable
private fun EmptyStatePreview() {
    KalendrysTheme {
        Surface {
            EmptyState(
                icon = Icons.Outlined.ShoppingCart,
                title = "Nenhuma lista ainda",
                message = "Crie uma lista de compras e use no mercado.",
                actionLabel = "Nova lista",
                actionIcon = Icons.Outlined.Add,
                onAction = {},
            )
        }
    }
}
