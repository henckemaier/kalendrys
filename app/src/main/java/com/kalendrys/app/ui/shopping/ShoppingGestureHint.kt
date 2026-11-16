package com.kalendrys.app.ui.shopping

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.kalendrys.app.R
import com.kalendrys.app.ui.components.KalendrysPreviews
import com.kalendrys.app.ui.theme.KalendrysTheme
import com.kalendrys.app.ui.theme.Spacing

/**
 * Dica única dos gestos da lista de compras (ADR-0002, 12.4): deslizar para marcar/desmarcar ou
 * remover e tocar para editar. Sem estado: quem chama decide quando mostrar (só enquanto
 * `SettingsRepository.shoppingGestureHintSeen` for `false`) e grava no [onDismiss]
 * (`setShoppingGestureHintSeen`).
 */
@Composable
internal fun ShoppingGestureHint(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(
            modifier = Modifier.padding(start = Spacing.l, end = Spacing.s, top = Spacing.m, bottom = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Icon(Icons.Outlined.Swipe, contentDescription = null)
                Text(stringResource(R.string.shopping_gesture_hint_title), style = MaterialTheme.typography.titleSmall)
            }
            Text(
                text = stringResource(R.string.shopping_gesture_hint_message),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = Spacing.s),
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.shopping_gesture_hint_dismiss))
            }
        }
    }
}

@KalendrysPreviews
@Composable
private fun ShoppingGestureHintPreview() {
    KalendrysTheme {
        Surface {
            ShoppingGestureHint(onDismiss = {}, modifier = Modifier.padding(Spacing.l))
        }
    }
}
