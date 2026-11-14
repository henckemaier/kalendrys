@file:OptIn(ExperimentalMaterial3Api::class)

package com.kalendrys.app.ui.categories

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kalendrys.app.R
import com.kalendrys.app.data.local.entity.RoutineCategory
import com.kalendrys.app.domain.RoutineCategoryKind
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.ui.components.fabListContentPadding
import com.kalendrys.app.ui.components.Dot
import com.kalendrys.app.ui.components.EmptyState
import com.kalendrys.app.ui.routines.routineTone

@Composable
fun RoutineCategoriesScreen(
    onBack: () -> Unit,
    viewModel: RoutineCategoriesViewModel = viewModel(factory = RoutineCategoriesViewModel.Factory),
) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var editing by remember { mutableStateOf<CategoryDialog?>(null) }
    var deleting by remember { mutableStateOf<RoutineCategory?>(null) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.routine_categories_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = CategoryDialog.Create }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.routine_category_add))
            }
        },
    ) { innerPadding ->
        if (categories.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Category,
                title = stringResource(R.string.routine_categories_empty_title),
                message = stringResource(R.string.routine_categories_empty_message),
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(innerPadding),
                contentPadding = fabListContentPadding(horizontal = 8.dp, top = 4.dp),
            ) {
                items(categories, key = { it.id }) { category ->
                    CategoryRow(
                        category = category,
                        onEdit = { editing = CategoryDialog.Edit(category) },
                        onDelete = { deleting = category },
                    )
                }
            }
        }
    }

    editing?.let { dialog ->
        val existing = (dialog as? CategoryDialog.Edit)?.category
        CategoryEditDialog(
            existing = existing,
            onConfirm = { name, color, kind ->
                if (existing == null) {
                    viewModel.create(name, color, kind)
                } else {
                    viewModel.update(existing, name, color, kind)
                }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    deleting?.let { category ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.routine_category_delete_title)) },
            text = { Text(stringResource(R.string.routine_category_delete_message, category.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(category)
                        deleting = null
                    },
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private sealed interface CategoryDialog {
    data object Create : CategoryDialog
    data class Edit(val category: RoutineCategory) : CategoryDialog
}

@Composable
private fun CategoryRow(
    category: RoutineCategory,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Dot(color = routineTone(RoutineColor.fromKey(category.color)).accent, size = 14.dp, filled = true)
        Text(
            text = category.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
        )
        IconButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.routine_category_edit, category.name))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.routine_category_delete, category.name))
        }
    }
}

@Composable
private fun CategoryEditDialog(
    existing: RoutineCategory?,
    onConfirm: (name: String, color: RoutineColor, kind: RoutineCategoryKind) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var color by remember { mutableStateOf(RoutineColor.fromKey(existing?.color ?: RoutineColor.DEFAULT.name)) }
    // SIMPLE, COMPRAS e EXERCICIO são oferecidos; um kind fora dessa lista (ex. backup) cai no padrão SIMPLE.
    var kind by remember {
        val current = RoutineCategoryKind.fromKey(existing?.kind ?: RoutineCategoryKind.DEFAULT.name)
        mutableStateOf(if (current in CATEGORY_KIND_OPTIONS) current else RoutineCategoryKind.SIMPLE)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (existing == null) R.string.routine_category_add else R.string.routine_category_edit_title,
                ),
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.routine_category_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        keyboardType = KeyboardType.Text,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(16.dp))
                Text(
                    text = stringResource(R.string.routine_category_kind_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CATEGORY_KIND_OPTIONS.forEach { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { kind = option },
                            label = { Text(stringResource(categoryKindLabel(option))) },
                        )
                    }
                }
                Spacer(Modifier.size(16.dp))
                ColorRow(selected = color, onSelect = { color = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim(), color, kind) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Tipos oferecidos na UI: SIMPLE, COMPRAS e EXERCICIO. Só EXERCICIO destrava uma seção no editor;
 * desde a v1.5.0 (ADR-0001, P6) COMPRAS é só um rótulo: qualquer rotina pode anexar uma lista.
 */
private val CATEGORY_KIND_OPTIONS =
    listOf(RoutineCategoryKind.SIMPLE, RoutineCategoryKind.COMPRAS, RoutineCategoryKind.EXERCICIO)

private fun categoryKindLabel(kind: RoutineCategoryKind): Int = when (kind) {
    RoutineCategoryKind.COMPRAS -> R.string.routine_category_kind_shopping
    RoutineCategoryKind.EXERCICIO -> R.string.routine_category_kind_exercise
    else -> R.string.routine_category_kind_simple
}

@Composable
private fun ColorRow(selected: RoutineColor, onSelect: (RoutineColor) -> Unit) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        RoutineColor.entries.forEach { option ->
            val tone = routineTone(option)
            val isSelected = option == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .then(
                        if (isSelected) Modifier.border(2.dp, tone.onFill, CircleShape) else Modifier,
                    )
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) },
                    ),
            ) {
                Dot(color = tone.accent, size = 28.dp, filled = true)
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = tone.onFill,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
