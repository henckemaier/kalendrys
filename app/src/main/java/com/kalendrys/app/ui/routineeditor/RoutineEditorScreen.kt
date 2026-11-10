@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.kalendrys.app.ui.routineeditor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kalendrys.app.R
import com.kalendrys.app.data.local.entity.ExerciseItem
import com.kalendrys.app.data.local.entity.RoutineCategory
import com.kalendrys.app.data.local.entity.ShoppingItem
import com.kalendrys.app.domain.DurationFormat
import com.kalendrys.app.domain.ExerciseFormat
import com.kalendrys.app.domain.MoneyFormat
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineDays
import com.kalendrys.app.domain.RoutineIcon
import com.kalendrys.app.ui.components.Dot
import com.kalendrys.app.ui.components.EmptyState
import com.kalendrys.app.ui.components.TimePickerDialog
import com.kalendrys.app.ui.components.firstDayOfWeek
import com.kalendrys.app.ui.components.groupedItemShape
import com.kalendrys.app.ui.routineeditor.RoutineEditorViewModel.Event
import com.kalendrys.app.ui.routines.imageVector
import com.kalendrys.app.ui.routines.minuteOfDayLabel
import com.kalendrys.app.ui.routines.routineTone
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest

@Composable
fun RoutineEditorScreen(
    onBack: () -> Unit,
    viewModel: RoutineEditorViewModel = viewModel(factory = RoutineEditorViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    val currentOnBack by rememberUpdatedState(onBack)

    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                Event.Closed -> currentOnBack()
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
                title = { Text(stringResource(if (state.isNew) R.string.routine_new_title else R.string.routine_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = { requestBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (state.draft != null) {
                        if (!state.isNew) {
                            val copyTitle = stringResource(R.string.routine_copy_suffix, state.routine?.title.orEmpty())
                            IconButton(onClick = { viewModel.duplicate(copyTitle) }) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.routine_duplicate))
                            }
                            val paused = state.routine?.pausedAt != null
                            IconButton(onClick = viewModel::togglePause) {
                                Icon(
                                    if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                                    contentDescription = stringResource(
                                        if (paused) R.string.routine_resume else R.string.routine_pause,
                                    ),
                                )
                            }
                            IconButton(onClick = { showDeleteDialog = true }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.routine_delete))
                            }
                        }
                        TextButton(onClick = viewModel::save, enabled = state.canSave) {
                            Text(stringResource(R.string.save))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        val draft = state.draft
        when {
            state.notFound -> EmptyState(
                icon = Icons.Outlined.SearchOff,
                title = stringResource(R.string.routine_not_found_title),
                message = stringResource(R.string.routine_not_found_message),
                modifier = Modifier.padding(innerPadding),
            )
            draft == null -> Unit
            else -> EditorContent(
                draft = draft,
                categories = state.categories,
                isShoppingCategory = state.isShoppingCategory,
                shoppingItems = state.shoppingItems,
                shoppingTotalCents = state.shoppingTotalCents,
                isExerciseCategory = state.isExerciseCategory,
                exerciseItems = state.exerciseItems,
                viewModel = viewModel,
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
            title = { Text(stringResource(R.string.routine_delete_confirm_title)) },
            text = { Text(stringResource(R.string.routine_delete_confirm_message)) },
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
            text = { Text(stringResource(R.string.routine_discard_message)) },
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
private fun EditorContent(
    draft: RoutineDraft,
    categories: List<RoutineCategory>,
    isShoppingCategory: Boolean,
    shoppingItems: List<ShoppingItem>,
    shoppingTotalCents: Long,
    isExerciseCategory: Boolean,
    exerciseItems: List<ExerciseItem>,
    viewModel: RoutineEditorViewModel,
    modifier: Modifier = Modifier,
) {
    var showStartPicker by rememberSaveable { mutableStateOf(false) }
    var showEndPicker by rememberSaveable { mutableStateOf(false) }
    var showStartDatePicker by rememberSaveable { mutableStateOf(false) }
    var showEndDatePicker by rememberSaveable { mutableStateOf(false) }
    // Diálogo de add/editar item de compras. editingItemId null + diálogo aberto = novo item; senão, edição.
    var showItemDialog by rememberSaveable { mutableStateOf(false) }
    var editingItemId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Diálogo de add/editar exercício (análogo ao de compras, mas com os campos do exercício).
    var showExerciseDialog by rememberSaveable { mutableStateOf(false) }
    var editingExerciseId by rememberSaveable { mutableStateOf<Long?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
    ) {
        item(key = "preview") { Preview(draft) }
        if (viewModel.isNew) {
            item(key = "templates") {
                SectionTitle(stringResource(R.string.routine_templates))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoutineTemplate.entries.forEach { template ->
                        val label = stringResource(template.titleRes)
                        AssistChip(
                            onClick = { viewModel.applyDraft(template.toDraft(label)) },
                            label = { Text(label) },
                            leadingIcon = {
                                Icon(
                                    imageVector = template.icon.imageVector,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                }
            }
        }
        item(key = "title") {
            OutlinedTextField(
                value = draft.title,
                onValueChange = viewModel::updateTitle,
                label = { Text(stringResource(R.string.task_title_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            )
        }
        item(key = "icon") {
            SectionTitle(stringResource(R.string.routine_icon))
            IconPicker(selected = draft.icon, color = draft.color, onSelect = viewModel::updateIcon)
        }
        item(key = "color") {
            SectionTitle(stringResource(R.string.routine_color))
            ColorPicker(selected = draft.color, onSelect = viewModel::updateColor)
        }
        if (categories.isNotEmpty()) {
            item(key = "category") {
                SectionTitle(stringResource(R.string.routine_category))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draft.categoryId == null,
                        onClick = { viewModel.updateCategory(null) },
                        label = { Text(stringResource(R.string.routine_category_none)) },
                    )
                    categories.forEach { category ->
                        FilterChip(
                            selected = draft.categoryId == category.id,
                            onClick = { viewModel.updateCategory(category.id) },
                            label = { Text(category.name) },
                            leadingIcon = {
                                Dot(
                                    color = routineTone(RoutineColor.fromKey(category.color)).accent,
                                    size = 10.dp,
                                    filled = true,
                                )
                            },
                        )
                    }
                }
            }
        }
        if (isShoppingCategory) {
            item(key = "shopping-title") {
                SectionTitle(stringResource(R.string.shopping_section_title))
            }
            if (viewModel.isNew) {
                item(key = "shopping-hint") {
                    Text(
                        text = stringResource(R.string.shopping_new_routine_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            } else {
                if (shoppingItems.isEmpty()) {
                    item(key = "shopping-empty") {
                        Text(
                            text = stringResource(R.string.shopping_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                } else {
                    itemsIndexed(shoppingItems, key = { _, it -> "shopping-item-${it.id}" }) { index, item ->
                        ShoppingItemRow(
                            item = item,
                            index = index,
                            count = shoppingItems.size,
                            onTogglePicked = { viewModel.toggleShoppingPicked(item) },
                            onEdit = {
                                editingItemId = item.id
                                showItemDialog = true
                            },
                            onDelete = { viewModel.removeShoppingItem(item) },
                        )
                    }
                    item(key = "shopping-total") {
                        ShoppingTotalRow(totalCents = shoppingTotalCents)
                    }
                }
                item(key = "shopping-add") {
                    TextButton(
                        onClick = {
                            editingItemId = null
                            showItemDialog = true
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.shopping_add_item))
                    }
                }
            }
        }
        if (isExerciseCategory) {
            item(key = "exercise-title") {
                SectionTitle(stringResource(R.string.exercise_section_title))
            }
            if (viewModel.isNew) {
                item(key = "exercise-hint") {
                    Text(
                        text = stringResource(R.string.exercise_new_routine_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            } else {
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
            }
        }
        item(key = "time") {
            SectionTitle(stringResource(R.string.routine_duration))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoutineDraft.DURATION_PRESETS.forEach { minutes ->
                    FilterChip(
                        selected = draft.durationMinutes == minutes,
                        onClick = { viewModel.updateDuration(minutes) },
                        label = {
                            Text(if (minutes == 0) stringResource(R.string.routine_no_duration) else DurationFormat.format(minutes))
                        },
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            TimeRow(
                label = stringResource(R.string.routine_start),
                value = minuteOfDayLabel(draft.startMinute),
                index = 0,
                onClick = { showStartPicker = true },
            )
            TimeRow(
                label = stringResource(R.string.routine_end),
                value = when {
                    draft.durationMinutes == 0 -> "—"
                    draft.endsNextDay -> stringResource(R.string.routine_end_next_day, minuteOfDayLabel(draft.endMinuteOfDay))
                    else -> minuteOfDayLabel(draft.endMinuteOfDay)
                },
                index = 1,
                onClick = { showEndPicker = true },
            )
        }
        item(key = "days") {
            SectionTitle(stringResource(R.string.routine_days))
            DayPicker(mask = draft.daysOfWeek, onToggle = viewModel::toggleDay)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                listOf(
                    RoutineDays.ALL to R.string.routine_days_everyday,
                    RoutineDays.WEEKDAYS to R.string.routine_days_weekdays,
                    RoutineDays.WEEKEND to R.string.routine_days_weekend,
                ).forEach { (mask, label) ->
                    FilterChip(
                        selected = draft.daysOfWeek == mask,
                        onClick = { viewModel.setDays(mask) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
            if (!draft.hasDays) {
                Text(
                    text = stringResource(R.string.routine_days_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
        }
        item(key = "recurrence") {
            SectionTitle(stringResource(R.string.routine_repeat))
            WeekIntervalStepper(
                weeks = draft.weekInterval,
                onChange = viewModel::updateWeekInterval,
            )
            SectionTitle(stringResource(R.string.routine_validity))
            DateRow(
                label = stringResource(R.string.routine_start_date),
                value = draft.startEpochDay?.let(::formatEpochDay) ?: stringResource(R.string.routine_date_any),
                index = 0,
                onClick = { showStartDatePicker = true },
                onClear = draft.startEpochDay?.let { { viewModel.updateStartDate(null) } },
            )
            DateRow(
                label = stringResource(R.string.routine_end_date),
                value = draft.endEpochDay?.let(::formatEpochDay) ?: stringResource(R.string.routine_date_none),
                index = 1,
                onClick = { showEndDatePicker = true },
                onClear = draft.endEpochDay?.let { { viewModel.updateEndDate(null) } },
            )
            if (!draft.datesValid) {
                Text(
                    text = stringResource(R.string.routine_dates_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
        }
        item(key = "remind") {
            RemindRow(
                checked = draft.remind,
                onCheckedChange = viewModel::updateRemind,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
        item(key = "notes") {
            OutlinedTextField(
                value = draft.notes,
                onValueChange = viewModel::updateNotes,
                label = { Text(stringResource(R.string.task_notes_label)) },
                minLines = 2,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            )
        }
    }

    if (showStartPicker) {
        TimePickerDialog(
            title = stringResource(R.string.routine_pick_start),
            initialTime = timeOf(draft.startMinute),
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
                if (draft.durationMinutes == 0) draft.startMinute + RoutineDraft.DEFAULT_DURATION_MINUTES else draft.endMinuteOfDay,
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
            initialEpochDay = draft.startEpochDay,
            onConfirm = {
                viewModel.updateStartDate(it)
                showStartDatePicker = false
            },
            onDismiss = { showStartDatePicker = false },
        )
    }
    if (showEndDatePicker) {
        RoutineDatePicker(
            initialEpochDay = draft.endEpochDay ?: draft.startEpochDay,
            onConfirm = {
                viewModel.updateEndDate(it)
                showEndDatePicker = false
            },
            onDismiss = { showEndDatePicker = false },
        )
    }
    if (showItemDialog) {
        val editing = editingItemId?.let { id -> shoppingItems.firstOrNull { it.id == id } }
        ShoppingItemDialog(
            existing = editing,
            onConfirm = { name, quantity, priceCents ->
                if (editing == null) {
                    viewModel.addShoppingItem(name, quantity, priceCents)
                } else {
                    viewModel.updateShoppingItem(editing, name, quantity, priceCents)
                }
                showItemDialog = false
                editingItemId = null
            },
            onDismiss = {
                showItemDialog = false
                editingItemId = null
            },
        )
    }
    if (showExerciseDialog) {
        val editing = editingExerciseId?.let { id -> exerciseItems.firstOrNull { it.id == id } }
        ExerciseItemDialog(
            existing = editing,
            onConfirm = { name, sets, reps, load, restSeconds ->
                if (editing == null) {
                    viewModel.addExerciseItem(name, sets, reps, load, restSeconds)
                } else {
                    viewModel.updateExerciseItem(editing, name, sets, reps, load, restSeconds)
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
}

@Composable
private fun ShoppingItemRow(
    item: ShoppingItem,
    index: Int,
    count: Int,
    onTogglePicked: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val pickedDescription = stringResource(R.string.shopping_item_picked)
    Surface(
        shape = groupedItemShape(index, count),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(start = 4.dp, end = 4.dp),
        ) {
            Checkbox(
                checked = item.picked,
                onCheckedChange = { onTogglePicked() },
                modifier = Modifier.semantics { contentDescription = pickedDescription },
            )
            val qtyBadge = stringResource(R.string.shopping_item_quantity_badge, item.quantity)
            val subtitle = item.priceCents?.let { "$qtyBadge · ${MoneyFormat.formatCents(it)}" } ?: qtyBadge
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (item.picked) TextDecoration.LineThrough else null,
                    color = if (item.picked) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Subtotal da linha (quantity*priceCents); só quando há preço.
            item.priceCents?.let { price ->
                Text(
                    text = MoneyFormat.formatCents(price * item.quantity),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.shopping_item_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.shopping_item_delete))
            }
        }
    }
}

@Composable
private fun ShoppingTotalRow(totalCents: Long) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.shopping_total),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = MoneyFormat.formatCents(totalCents),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ShoppingItemDialog(
    existing: ShoppingItem?,
    onConfirm: (name: String, quantity: Int, priceCents: Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var quantityText by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.quantity?.toString() ?: "1")
    }
    var priceText by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.priceCents?.let { MoneyFormat.centsToInput(it) } ?: "")
    }
    val quantity = quantityText.toIntOrNull()?.coerceAtLeast(1) ?: 1
    // Preço só é inválido quando tem texto sem nenhum dígito (ex.: só um separador). Vazio é válido
    // (= sem preço, remoção explícita). Bloquear o inválido impede zerar o preço por engano na edição.
    val priceInvalid = priceText.isNotBlank() && priceText.none { it.isDigit() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (existing == null) R.string.shopping_add_title else R.string.shopping_edit_title))
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.shopping_item_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { new -> quantityText = new.filter { it.isDigit() }.take(4) },
                    label = { Text(stringResource(R.string.shopping_item_quantity_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    // Filtra: dígitos + no máximo um separador decimal. Entrada inválida não chega ao parse.
                    value = priceText,
                    onValueChange = { priceText = MoneyFormat.filterPriceInput(it) },
                    label = { Text(stringResource(R.string.shopping_item_price_label)) },
                    singleLine = true,
                    isError = priceInvalid,
                    supportingText = if (priceInvalid) {
                        { Text(stringResource(R.string.shopping_price_invalid)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Vazio → sem preço (null, explícito). Com dígitos → parse garantido não-nulo. O
                    // caso inválido nunca chega aqui (Save desabilitado), então nunca zera à toa.
                    val priceCents = if (priceText.isBlank()) null else MoneyFormat.parseReaisToCents(priceText)
                    onConfirm(name.trim(), quantity, priceCents)
                },
                enabled = name.isNotBlank() && !priceInvalid,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ExerciseItemRow(
    item: ExerciseItem,
    index: Int,
    count: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    // Legenda: séries×reps · carga (se houver) · descanso (se houver).
    val subtitle = buildList {
        add(ExerciseFormat.setsReps(item.sets, item.reps))
        item.load?.let { add(it) }
        item.restSeconds?.let { add(ExerciseFormat.restLabel(it)) }
    }.joinToString(" · ")
    Surface(
        shape = groupedItemShape(index, count),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, end = 4.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onMoveUp, enabled = index > 0) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.exercise_item_move_up))
            }
            IconButton(onClick = onMoveDown, enabled = index < count - 1) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.exercise_item_move_down))
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.exercise_item_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.exercise_item_delete))
            }
        }
    }
}

@Composable
private fun ExerciseItemDialog(
    existing: ExerciseItem?,
    onConfirm: (name: String, sets: Int, reps: Int, load: String?, restSeconds: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var setsText by rememberSaveable(existing?.id) { mutableStateOf(existing?.sets?.toString() ?: "3") }
    var repsText by rememberSaveable(existing?.id) { mutableStateOf(existing?.reps?.toString() ?: "10") }
    var loadText by rememberSaveable(existing?.id) { mutableStateOf(existing?.load ?: "") }
    var restText by rememberSaveable(existing?.id) { mutableStateOf(existing?.restSeconds?.toString() ?: "") }
    // Séries/reps caem no mínimo 1 quando em branco; carga e descanso em branco = sem valor (null).
    val sets = setsText.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val reps = repsText.toIntOrNull()?.coerceAtLeast(1) ?: 1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (existing == null) R.string.exercise_add_title else R.string.exercise_edit_title))
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.exercise_item_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = setsText,
                        onValueChange = { new -> setsText = new.filter { it.isDigit() }.take(3) },
                        label = { Text(stringResource(R.string.exercise_item_sets_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = repsText,
                        onValueChange = { new -> repsText = new.filter { it.isDigit() }.take(3) },
                        label = { Text(stringResource(R.string.exercise_item_reps_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = loadText,
                    onValueChange = { loadText = it },
                    label = { Text(stringResource(R.string.exercise_item_load_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = restText,
                    onValueChange = { new -> restText = new.filter { it.isDigit() }.take(5) },
                    label = { Text(stringResource(R.string.exercise_item_rest_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val load = loadText.trim().takeIf { it.isNotEmpty() }
                    val rest = restText.toIntOrNull()
                    onConfirm(name.trim(), sets, reps, load, rest)
                },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun timeOf(minute: Int): LocalTime = LocalTime.of(minute % 1440 / 60, minute % 60)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

/** Como o bloco vai aparecer na linha do tempo. */
@Composable
private fun Preview(draft: RoutineDraft) {
    val tone = routineTone(draft.color)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 48.dp, height = 64.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(tone.fill),
        ) {
            Icon(draft.icon.imageVector, contentDescription = null, tint = tone.onFill)
        }
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(
                text = when (draft.durationMinutes) {
                    0 -> minuteOfDayLabel(draft.startMinute)
                    else -> stringResource(
                        R.string.routine_time_range,
                        minuteOfDayLabel(draft.startMinute),
                        minuteOfDayLabel(draft.endMinuteOfDay),
                        DurationFormat.format(draft.durationMinutes),
                    )
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = draft.title.ifBlank { stringResource(R.string.routine_new_title) },
                style = MaterialTheme.typography.titleMedium,
                color = if (draft.title.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun IconPicker(selected: RoutineIcon, color: RoutineColor, onSelect: (RoutineIcon) -> Unit) {
    val tone = routineTone(color)
    val labels = stringArrayResource(R.array.routine_icon_labels)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RoutineIcon.entries.forEach { icon ->
            val isSelected = icon == selected
            val description = stringResource(R.string.routine_icon_option, labels.getOrElse(icon.ordinal) { icon.name })
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) tone.fill else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(icon) })
                    .semantics { contentDescription = description },
            ) {
                Icon(
                    imageVector = icon.imageVector,
                    contentDescription = null,
                    tint = if (isSelected) tone.onFill else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun ColorPicker(selected: RoutineColor, onSelect: (RoutineColor) -> Unit) {
    val labels = stringArrayResource(R.array.routine_color_labels)
    // As 8 cores cabem numa linha só, distribuídas na largura.
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        RoutineColor.entries.forEach { color ->
            val tone = routineTone(color)
            val isSelected = color == selected
            val description = stringResource(R.string.routine_color_option, labels.getOrElse(color.ordinal) { color.name })
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(tone.fill)
                    .then(if (isSelected) Modifier.border(2.dp, tone.onFill, CircleShape) else Modifier)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(color) })
                    .semantics { contentDescription = description },
            ) {
                if (isSelected) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = tone.onFill, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun TimeRow(label: String, value: String, index: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = groupedItemShape(index, 2),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(text = value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun WeekIntervalStepper(weeks: Int, onChange: (Int) -> Unit) {
    val label = if (weeks <= 1) {
        stringResource(R.string.routine_week_interval_weekly)
    } else {
        stringResource(R.string.routine_week_interval_every, weeks)
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = label },
            )
            IconButton(
                onClick = { onChange(weeks - 1) },
                enabled = weeks > 1,
            ) {
                Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.routine_week_decrease))
            }
            IconButton(
                onClick = { onChange(weeks + 1) },
                enabled = weeks < RoutineDraft.MAX_WEEK_INTERVAL,
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.routine_week_increase))
            }
        }
    }
}

@Composable
private fun DateRow(label: String, value: String, index: Int, onClick: () -> Unit, onClear: (() -> Unit)?) {
    Surface(
        onClick = onClick,
        shape = groupedItemShape(index, 2),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(text = value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            if (onClear != null) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.routine_date_clear))
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}

@Composable
private fun RoutineDatePicker(initialEpochDay: Long?, onConfirm: (Long) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = (initialEpochDay ?: LocalDate.now().toEpochDay()) * MILLIS_PER_DAY,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onConfirm(it / MILLIS_PER_DAY) } },
                enabled = state.selectedDateMillis != null,
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    ) {
        DatePicker(state = state)
    }
}

private const val MILLIS_PER_DAY = 86_400_000L
private val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun formatEpochDay(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(dateFormatter)

@Composable
private fun DayPicker(mask: Int, onToggle: (DayOfWeek) -> Unit) {
    val locale = Locale.getDefault()
    val firstDay = firstDayOfWeek(locale)
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        for (offset in 0L until 7L) {
            val day = firstDay.plus(offset)
            val selected = RoutineDays.contains(mask, day)
            val colors = MaterialTheme.colorScheme
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.primary else colors.surfaceContainerHigh)
                    .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle(day) })
                    .semantics { contentDescription = day.getDisplayName(TextStyle.FULL, locale) },
            ) {
                Text(
                    text = day.getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) colors.onPrimary else colors.onSurface,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}

@Composable
private fun RemindRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.routine_remind), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.routine_remind_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            // O toque vale para a linha inteira (toggleable acima); o Switch só mostra o estado.
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}
