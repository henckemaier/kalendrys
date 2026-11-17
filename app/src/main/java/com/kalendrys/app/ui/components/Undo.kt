package com.kalendrys.app.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult

/**
 * Mostra o aviso com a ação Desfazer (v1.7.1, E4) e espera a resposta. Duração sempre
 * [SnackbarDuration.Long]: com a curta (4s) não dava tempo de ler e tocar em Desfazer.
 *
 * Devolve `true` se a pessoa tocou em [actionLabel] (desfazer) e `false` se o aviso sumiu sozinho
 * ou foi substituído. Quem chama continua usando `collectLatest`: uma ação nova cancela a espera da
 * anterior, e a anterior não é desfeita.
 */
suspend fun SnackbarHostState.showUndo(message: String, actionLabel: String): Boolean =
    showSnackbar(
        message = message,
        actionLabel = actionLabel,
        duration = SnackbarDuration.Long,
    ) == SnackbarResult.ActionPerformed
