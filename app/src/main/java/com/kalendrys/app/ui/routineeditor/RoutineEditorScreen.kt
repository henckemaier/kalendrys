@file:OptIn(ExperimentalLayoutApi::class)

package com.kalendrys.app.ui.routineeditor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kalendrys.app.R
import com.kalendrys.app.domain.ExerciseFields
import com.kalendrys.app.ui.components.AppScaffold
import com.kalendrys.app.ui.components.AppTopBar
import com.kalendrys.app.ui.components.EmptyState
import com.kalendrys.app.ui.components.TimePickerDialog
import com.kalendrys.app.ui.components.TopBarAction
import com.kalendrys.app.ui.components.bottomBarInsets
import com.kalendrys.app.ui.components.isCompactHeight
import com.kalendrys.app.ui.components.plus
import com.kalendrys.app.ui.components.windowHeightDp
import com.kalendrys.app.ui.routineeditor.RoutineEditorViewModel.Event
import com.kalendrys.app.ui.routines.imageVector
import com.kalendrys.app.ui.theme.Spacing
import kotlinx.coroutines.flow.collectLatest

/**
 * Editor de rotina (v1.9.0, ADR-0002 15.5). Na criação, "Criar rotina" fica fixo no rodapé (ao
 * alcance também em paisagem com teclado: a casca encolhe acima dele) e voltar com algo preenchido
 * pede confirmação. Na edição, cada mudança grava na hora: sem Salvar e sem diálogo de descarte, e o
 * voltar preditivo funciona. Ir para segundo plano grava o texto pendente (15.3).
 */
@Composable
fun RoutineEditorScreen(
    onBack: () -> Unit,
    viewModel: RoutineEditorViewModel = viewModel(factory = RoutineEditorViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    val currentOnBack by rememberUpdatedState(onBack)

    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                Event.Closed -> currentOnBack()
            }
        }
    }
    // Segundo plano: o texto em digitação grava já, no escopo do app (15.3). Sair grava no onCleared.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.flushPending() }

    // Só a criação tem o que descartar; na edição tudo já está gravado. Criando, não há o que descartar.
    val askToDiscard = state.hasDraftChanges && !state.creating
    fun requestBack() {
        if (askToDiscard) showDiscardDialog = true else onBack()
    }
    BackHandler(enabled = askToDiscard) { showDiscardDialog = true }

    // Pausar, Duplicar, Reverter alterações e Excluir no menu (15.5); nada na barra além do voltar.
    val menu = mutableListOf<TopBarAction>()
    val fields = state.fields
    if (fields != null && !state.isNew) {
        val paused = state.routine?.pausedAt != null
        menu += TopBarAction(
            stringResource(if (paused) R.string.routine_resume else R.string.routine_pause),
            onClick = viewModel::togglePause,
        )
        val copyTitle = stringResource(R.string.routine_copy_suffix, fields.title.ifBlank { state.routine?.title.orEmpty() })
        menu += TopBarAction(stringResource(R.string.routine_duplicate)) { viewModel.duplicate(copyTitle) }
        menu += TopBarAction(stringResource(R.string.editor_revert), enabled = state.canRevert, onClick = viewModel::revert)
        // P8: sem confirmação (ação com Desfazer, ADR-0002 5.4); a mensagem aparece na origem.
        menu += TopBarAction(stringResource(R.string.routine_delete), onClick = viewModel::delete)
    }

    // Criação em altura COMPACTA (o mesmo corte que recolhe o FAB e o painel, E3-B da Coruja): o
    // "Criar rotina" sai do rodapé, que com o teclado não sobra, e vai para a barra do topo como ação
    // em destaque; a lista traz o campo em foco para a área visível. Altura normal: no rodapé.
    val compact = isCompactHeight(windowHeightDp())
    val actions = mutableListOf<TopBarAction>()
    if (state.isNew && fields != null && compact) {
        actions += TopBarAction(
            stringResource(R.string.routine_create),
            enabled = state.canCreate && !state.creating,
            prominent = true,
            onClick = viewModel::create,
        )
    }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(if (state.isNew) R.string.routine_new_title else R.string.routine_edit_title),
                onBack = { requestBack() },
                actions = actions,
                menu = menu,
            )
        },
        bottomBar = {
            if (state.isNew && fields != null && !compact) {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                    Button(
                        onClick = viewModel::create,
                        enabled = state.canCreate && !state.creating,
                        // Barra do sistema E laterais (recorte em paisagem), como as outras barras de rodapé.
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(bottomBarInsets)
                            .padding(horizontal = Spacing.l, vertical = Spacing.s),
                    ) {
                        Text(stringResource(R.string.routine_create))
                    }
                }
            }
        },
    ) { padding ->
        when {
            state.notFound -> EmptyState(
                icon = Icons.Outlined.SearchOff,
                title = stringResource(R.string.routine_not_found_title),
                message = stringResource(R.string.routine_not_found_message),
                contentPadding = padding,
            )
            fields == null -> Unit
            else -> EditorContent(
                state = state,
                fields = fields,
                viewModel = viewModel,
                // O safeDrawing do AppScaffold já inclui o teclado: a lista rola até o campo focado.
                contentPadding = padding.plus(horizontal = Spacing.l, top = Spacing.s, bottom = Spacing.xxl),
            )
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.discard_changes_title)) },
            text = { Text(stringResource(R.string.routine_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        viewModel.discardDraft()
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
private fun EditorContent(
    state: RoutineEditorViewModel.UiState,
    fields: RoutineDraft,
    viewModel: RoutineEditorViewModel,
    contentPadding: PaddingValues,
) {
    var showStartPicker by rememberSaveable { mutableStateOf(false) }
    var showEndPicker by rememberSaveable { mutableStateOf(false) }
    var showStartDatePicker by rememberSaveable { mutableStateOf(false) }
    var showEndDatePicker by rememberSaveable { mutableStateOf(false) }
    // Diálogo de add/editar exercício. editingExerciseId null + diálogo aberto = novo item; senão, edição.
    var showExerciseDialog by rememberSaveable { mutableStateOf(false) }
    var editingExerciseId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Painel "Dos salvos" e o salvo em edição nele (v1.10.0, 16.10.5).
    var showSavedSheet by rememberSaveable { mutableStateOf(false) }
    var editingSavedId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Excluir um salvo pede confirmação (B2 do Esquadro, critério da P45: confirmação, sem Desfazer).
    var confirmDeleteSavedId by rememberSaveable { mutableStateOf<Long?>(null) }
    // "Mais opções" (Vigência e Observações): aberto se a rotina já tem vigência ou observações, decidido
    // UMA vez, na abertura (vigência escondida é armadilha, (c) da revisão da E3); depois só pelo toque.
    var moreOptions by rememberSaveable {
        mutableStateOf(fields.startEpochDay != null || fields.endEpochDay != null || fields.notes.isNotBlank())
    }
    val exerciseItems = state.exerciseItems

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "preview") { Preview(fields) }
        if (state.isNew) {
            item(key = "templates") {
                SectionTitle(stringResource(R.string.routine_templates))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoutineTemplate.entries.forEach { template ->
                        val label = stringResource(template.titleRes)
                        AssistChip(
                            onClick = { viewModel.applyDraft(template.toDraft(label)) },
                            label = { Text(label) },
                            leadingIcon = {
                                Icon(imageVector = template.icon.imageVector, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                        )
                    }
                }
            }
        }
        // O texto dos campos vem do estado de composição do ViewModel, escrito na tecla (E3-A).
        item(key = "title") {
            val title = viewModel.titleInput
            TitleSection(title = title, showError = !state.isNew && title.isBlank(), onChange = viewModel::updateTitle)
        }
        item(key = "when") {
            WhenSection(
                fields = fields,
                onPickStart = { showStartPicker = true },
                onPickEnd = { showEndPicker = true },
                onDuration = viewModel::updateDuration,
                onToggleDay = viewModel::toggleDay,
                onSetDays = viewModel::setDays,
                onWeekInterval = viewModel::updateWeekInterval,
            )
        }
        item(key = "remind") { RemindSection(remind = fields.remind, onChange = viewModel::updateRemind) }
        item(key = "kind") { KindSection(kind = fields.kind, onSelect = viewModel::updateKind) }
        if (state.isExercise) {
            item(key = "exercise-title") { SectionTitle(stringResource(R.string.exercise_section_title)) }
            // Também na criação (v1.10.0, 16.10.5): o rascunho guarda e o "Criar rotina" grava junto.
            if (exerciseItems.isEmpty()) {
                item(key = "exercise-empty") {
                    Text(
                        text = stringResource(R.string.exercise_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            } else {
                itemsIndexed(exerciseItems, key = { _, it -> "exercise-item-${it.id}" }) { index, item ->
                    ExerciseItemRow(
                        item = item,
                        index = index,
                        count = exerciseItems.size,
                        onMoveUp = { viewModel.moveExerciseItem(item, up = true) },
                        onMoveDown = { viewModel.moveExerciseItem(item, up = false) },
                        onEdit = {
                            editingExerciseId = item.id
                            showExerciseDialog = true
                        },
                        onDelete = { viewModel.removeExerciseItem(item) },
                    )
                }
            }
            item(key = "exercise-add") {
                TextButton(
                    onClick = {
                        editingExerciseId = null
                        showExerciseDialog = true
                    },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.exercise_add_item))
                }
            }
            item(key = "exercise-from-saved") {
                TextButton(onClick = { showSavedSheet = true }) {
                    Icon(Icons.Outlined.Bookmarks, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.exercise_from_saved))
                }
            }
        }
        item(key = "appearance") {
            AppearanceSection(fields = fields, onIcon = viewModel::updateIcon, onColor = viewModel::updateColor)
        }
        item(key = "more-options") { MoreOptionsHeader(expanded = moreOptions, onToggle = { moreOptions = !moreOptions }) }
        if (moreOptions) {
            item(key = "validity") {
                ValiditySection(
                    fields = fields,
                    onPickStartDate = { showStartDatePicker = true },
                    onPickEndDate = { showEndDatePicker = true },
                    onClearStart = { viewModel.updateStartDate(null) },
                    onClearEnd = { viewModel.updateEndDate(null) },
                )
            }
            item(key = "notes") { NotesSection(notes = viewModel.notesInput, onChange = viewModel::updateNotes) }
        }
    }

    if (showStartPicker) {
        TimePickerDialog(
            title = stringResource(R.string.routine_pick_start),
            initialTime = timeOf(fields.startMinute),
            onConfirm = {
                viewModel.updateStart(it)
                showStartPicker = false
            },
            onDismiss = { showStartPicker = false },
        )
    }
    if (showEndPicker) {
        TimePickerDialog(
            title = stringResource(R.string.routine_pick_end),
            // Sem duração ainda: sugere meia hora depois do início.
            initialTime = timeOf(
                if (fields.durationMinutes == 0) fields.startMinute + RoutineDraft.DEFAULT_DURATION_MINUTES else fields.endMinuteOfDay,
            ),
            onConfirm = {
                viewModel.updateEnd(it)
                showEndPicker = false
            },
            onDismiss = { showEndPicker = false },
        )
    }
    if (showStartDatePicker) {
        RoutineDatePicker(
            initialEpochDay = fields.startEpochDay,
            onConfirm = {
                viewModel.updateStartDate(it)
                showStartDatePicker = false
            },
            onDismiss = { showStartDatePicker = false },
        )
    }
    if (showEndDatePicker) {
        RoutineDatePicker(
            initialEpochDay = fields.endEpochDay ?: fields.startEpochDay,
            // O fim só a partir do início (5.3): o estado "fim antes do início" não é alcançável.
            minEpochDay = fields.startEpochDay,
            onConfirm = {
                viewModel.updateEndDate(it)
                showEndDatePicker = false
            },
            onDismiss = { showEndDatePicker = false },
        )
    }
    if (showExerciseDialog) {
        val editing = editingExerciseId?.let { id -> exerciseItems.firstOrNull { it.id == id } }
        ExerciseItemDialog(
            initial = editing?.let(ExerciseFields::of),
            stateKey = "exercise-${editing?.id}",
            titleRes = if (editing == null) R.string.exercise_add_title else R.string.exercise_edit_title,
            offerSaveForReuse = true,
            onConfirm = { name, sets, reps, load, restSeconds, saveForReuse ->
                if (editing == null) {
                    viewModel.addExerciseItem(name, sets, reps, load, restSeconds, saveForReuse)
                } else {
                    viewModel.updateExerciseItem(editing, name, sets, reps, load, restSeconds, saveForReuse)
                }
                showExerciseDialog = false
                editingExerciseId = null
            },
            onDismiss = {
                showExerciseDialog = false
                editingExerciseId = null
            },
        )
    }
    if (showSavedSheet) {
        SavedExercisesSheet(
            saved = state.savedExercises,
            onApply = { saved ->
                viewModel.applySavedExercise(saved)
                showSavedSheet = false
            },
            onEdit = { saved -> editingSavedId = saved.id },
            onDelete = { saved -> confirmDeleteSavedId = saved.id },
            onDismiss = { showSavedSheet = false },
        )
    }
    val deletingSaved = confirmDeleteSavedId?.let { id -> state.savedExercises.firstOrNull { it.id == id } }
    if (deletingSaved != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSavedId = null },
            title = { Text(stringResource(R.string.saved_exercise_delete_title)) },
            text = { Text(stringResource(R.string.saved_exercise_delete_message, deletingSaved.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSavedExercise(deletingSaved)
                        confirmDeleteSavedId = null
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSavedId = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    val editingSaved = editingSavedId?.let { id -> state.savedExercises.firstOrNull { it.id == id } }
    if (editingSaved != null) {
        ExerciseItemDialog(
            initial = ExerciseFields(editingSaved.name, editingSaved.sets, editingSaved.reps, editingSaved.load, editingSaved.restSeconds),
            stateKey = "saved-${editingSaved.id}",
            titleRes = R.string.saved_exercise_edit_title,
            offerSaveForReuse = false,
            onConfirm = { name, sets, reps, load, restSeconds, _ ->
                viewModel.updateSavedExercise(editingSaved, name, sets, reps, load, restSeconds)
                editingSavedId = null
            },
            onDismiss = { editingSavedId = null },
        )
    }
}
