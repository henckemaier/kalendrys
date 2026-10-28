@file:OptIn(ExperimentalMaterial3Api::class)

package com.kalendrys.app.ui.taskdetail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kalendrys.app.R
import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.ui.components.EmptyState
import com.kalendrys.app.ui.components.groupedItemShape
import com.kalendrys.app.ui.components.longDateLabel
import com.kalendrys.app.ui.components.weekdayDateLabel
import com.kalendrys.app.ui.taskdetail.TaskDetailViewModel.Event
import com.kalendrys.app.ui.tasks.LocationField
import com.kalendrys.app.ui.tasks.TaskTimingField
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.collectLatest

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val SectionSpacing = 16.dp

@Composable
fun TaskDetailScreen(
    onBack: () -> Unit,
    viewModel: TaskDetailViewModel = viewModel(factory = TaskDetailViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    val currentOnBack by rememberUpdatedState(onBack)

    val savedMessage = stringResource(R.string.changes_saved)
    val completedMessage = stringResource(R.string.task_completed)
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.events.collectLatest { event ->
            when (event) {
                Event.Saved -> snackbarHostState.showSnackbar(savedMessage)
                is Event.Completed -> {
                    val result = snackbarHostState.showSnackbar(
                        message = completedMessage,
                        actionLabel = undoLabel,
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.undoCompletion(event.logId)
                }
                Event.Deleted -> currentOnBack()
            }
        }
    }

    fun requestBack() {
        if (state.hasChanges) showDiscardDialog = true else onBack()
    }
    BackHandler(enabled = state.hasChanges) { showDiscardDialog = true }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.task_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = { requestBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (state.draft != null) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete_task))
                        }
                        TextButton(onClick = viewModel::save, enabled = state.canSave) {
                            Text(stringResource(R.string.save))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val draft = state.draft
        when {
            state.notFound -> EmptyState(
                icon = Icons.Outlined.SearchOff,
                title = stringResource(R.string.task_not_found_title),
                message = stringResource(R.string.task_not_found_message),
                modifier = Modifier.padding(innerPadding),
            )
            draft == null -> Unit
            else -> TaskDetailContent(
                state = state,
                draft = draft,
                onTitleChange = viewModel::updateTitle,
                onNotesChange = viewModel::updateNotes,
                onTimingChange = viewModel::updateTiming,
                onLocationChange = viewModel::updateLocation,
                onComplete = viewModel::complete,
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding(),
            )
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_task_confirm_title)) },
            text = { Text(stringResource(R.string.delete_task_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.delete()
                    },
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.discard_changes_title)) },
            text = { Text(stringResource(R.string.discard_changes_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onBack()
                    },
                ) {
                    Text(stringResource(R.string.discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text(stringResource(R.string.keep_editing)) }
            },
        )
    }
}

@Composable
private fun TaskDetailContent(
    state: TaskDetailViewModel.UiState,
    draft: TaskDraft,
    onTitleChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onTimingChange: (TaskTiming) -> Unit,
    onLocationChange: (String) -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = state.zone
    val context = LocalContext.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
    ) {
        item(key = "title") {
            OutlinedTextField(
                value = draft.title,
                onValueChange = onTitleChange,
                label = { Text(stringResource(R.string.task_title_label)) },
                singleLine = true,
                isError = draft.title.isBlank(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = SectionSpacing),
            )
        }
        item(key = "timing") {
            TaskTimingField(
                timing = draft.timing,
                onTimingChange = onTimingChange,
                // O lembrete já salvo pode ter tocado; só um lembrete novo precisa estar no futuro.
                warnIfPast = state.task?.let { !draft.keepsReminderOf(it, zone) } ?: true,
                modifier = Modifier.padding(bottom = SectionSpacing),
            )
        }
        item(key = "notes") {
            OutlinedTextField(
                value = draft.notes,
                onValueChange = onNotesChange,
                label = { Text(stringResource(R.string.task_notes_label)) },
                minLines = 3,
                maxLines = 8,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = SectionSpacing),
            )
        }
        item(key = "location") {
            LocationField(
                value = draft.location,
                onValueChange = onLocationChange,
                modifier = Modifier.padding(bottom = SectionSpacing),
                trailing = if (draft.location.isNotBlank()) {
                    {
                        IconButton(onClick = { openInMaps(context, draft.location) }) {
                            Icon(Icons.Outlined.Map, contentDescription = stringResource(R.string.task_open_map))
                        }
                    }
                } else {
                    null
                },
            )
        }
        item(key = "status") {
            StatusCard(doneAt = state.doneAt, repeats = state.repeats, zone = zone, onComplete = onComplete)
        }
        state.task?.let { task ->
            item(key = "created") {
                Text(
                    text = stringResource(
                        R.string.task_created_on,
                        longDateLabel(Instant.ofEpochMilli(task.createdAt).atZone(zone).toLocalDate()),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }
        }
        item(key = "history_header") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.history_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = state.history.size.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.history.isEmpty()) {
            item(key = "history_empty") {
                Text(
                    text = stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            itemsIndexed(state.history, key = { _, log -> log.id }) { index, log ->
                HistoryRow(
                    log = log,
                    zone = zone,
                    shape = groupedItemShape(index, state.history.size),
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

/** Situação da tarefa: hoje (se ela se repete) ou de vez (se não se repete). */
@Composable
private fun StatusCard(doneAt: Long?, repeats: Boolean, zone: ZoneId, onComplete: () -> Unit) {
    val done = doneAt != null
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 64.dp)
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Icon(
                imageVector = if (done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = when {
                    doneAt == null -> stringResource(if (repeats) R.string.status_pending_today else R.string.status_pending)
                    repeats -> stringResource(
                        R.string.status_done_today_at,
                        Instant.ofEpochMilli(doneAt).atZone(zone).format(TIME),
                    )
                    else -> {
                        val at = Instant.ofEpochMilli(doneAt).atZone(zone)
                        stringResource(R.string.status_done_on, historyDayLabel(at.toLocalDate()), at.format(TIME))
                    }
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (!done) {
                FilledTonalButton(onClick = onComplete) {
                    Text(stringResource(R.string.complete_today))
                }
            }
        }
    }
}

/** Abre o local no app de mapas (busca pelo texto; não precisa de permissão de localização). */
private fun openInMaps(context: Context, location: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(location)))
    // Sem app de mapas, a busca no navegador; sem nenhum dos dois, avisa em vez de fechar o app.
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(location)))
    val opened = listOf(intent, web).any { candidate ->
        try {
            context.startActivity(candidate)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
    if (!opened) Toast.makeText(context, R.string.task_no_map_app, Toast.LENGTH_SHORT).show()
}

@Composable
private fun HistoryRow(
    log: CompletionLog,
    zone: ZoneId,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val dateTime = Instant.ofEpochMilli(log.completedAt).atZone(zone)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = historyDayLabel(dateTime.toLocalDate()),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = dateTime.format(TIME),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Hoje", "Ontem", "Quinta-feira, 22 de outubro" ou, em outro ano, "22 de outubro de 2025". */
@Composable
private fun historyDayLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when {
        date == today -> stringResource(R.string.reminder_today)
        date == today.minusDays(1) -> stringResource(R.string.yesterday)
        date.year == today.year -> weekdayDateLabel(date)
        else -> longDateLabel(date)
    }
}
