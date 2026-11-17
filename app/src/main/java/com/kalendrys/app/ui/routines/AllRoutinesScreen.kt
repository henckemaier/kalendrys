package com.kalendrys.app.ui.routines

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kalendrys.app.R
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCategory
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineDays
import com.kalendrys.app.domain.RoutineIcon
import com.kalendrys.app.ui.components.AppScaffold
import com.kalendrys.app.ui.components.AppTopBar
import com.kalendrys.app.ui.components.CollapsingFab
import com.kalendrys.app.ui.components.Dot
import com.kalendrys.app.ui.components.EmptyState
import com.kalendrys.app.ui.components.GroupedCard
import com.kalendrys.app.ui.components.ListRow
import com.kalendrys.app.ui.components.SectionHeader
import com.kalendrys.app.ui.components.WeekdayName
import com.kalendrys.app.ui.components.firstDayOfWeek
import com.kalendrys.app.ui.components.plus
import com.kalendrys.app.ui.components.rememberFabExpanded
import com.kalendrys.app.ui.components.showUndo
import com.kalendrys.app.ui.components.weekdayName
import com.kalendrys.app.ui.theme.Spacing
import kotlinx.coroutines.flow.collectLatest

/**
 * "Todas as rotinas" (v1.8.0, E2; ADR-0002, 14.4): ativas e pausadas, com o filtro por categoria
 * (P3) e o FAB "Nova rotina" (P4). Tocar na linha edita; pausar, retomar e arquivar ficam no menu de
 * três pontos e, para o leitor de tela, como ações da própria linha (um nó por linha, 14.9).
 * Arquivar oferece Desfazer no aviso da própria tela.
 */
@Composable
fun AllRoutinesScreen(
    onBack: () -> Unit,
    onOpenRoutine: (Long) -> Unit,
    onNewRoutine: () -> Unit,
    viewModel: AllRoutinesViewModel = viewModel(factory = AllRoutinesViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val fabExpanded by rememberFabExpanded(listState)
    val snackbarHostState = remember { SnackbarHostState() }
    val archivedMessage = stringResource(R.string.all_routines_archived)
    val undoLabel = stringResource(R.string.undo)

    LaunchedEffect(viewModel) {
        // collectLatest: um arquivamento novo substitui o aviso do anterior, que não é desfeito.
        viewModel.archived.collectLatest { event ->
            if (snackbarHostState.showUndo(archivedMessage, undoLabel)) viewModel.undoArchive(event.routineId)
        }
    }

    AppScaffold(
        topBar = { AppTopBar(title = stringResource(R.string.all_routines_title), onBack = onBack) },
        floatingActionButton = {
            CollapsingFab(text = stringResource(R.string.new_routine), expanded = fabExpanded, onClick = onNewRoutine)
        },
        hasFab = true,
        snackbarHostState = snackbarHostState,
    ) { padding ->
        when {
            !state.isLoaded -> Unit
            !state.hasAny -> EmptyState(
                icon = Icons.Outlined.EventRepeat,
                title = stringResource(R.string.routines_empty_title),
                message = stringResource(R.string.routines_empty_message),
                actionLabel = stringResource(R.string.new_routine),
                onAction = onNewRoutine,
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                state = listState,
                contentPadding = padding.plus(horizontal = Spacing.s, top = Spacing.xs),
            ) {
                if (state.categories.isNotEmpty()) {
                    item(key = "filter") {
                        CategoryFilterRow(
                            categories = state.categories,
                            selectedId = state.selectedCategoryId,
                            onSelect = viewModel::selectCategory,
                        )
                    }
                }
                val actions = RowActions(
                    onEdit = onOpenRoutine,
                    onPause = viewModel::pause,
                    onResume = viewModel::resume,
                    onArchive = viewModel::archive,
                )
                section(key = "active", titleRes = R.string.all_routines_section_active, rows = state.active, actions = actions)
                section(key = "paused", titleRes = R.string.all_routines_section_paused, rows = state.paused, actions = actions)
                if (state.active.isEmpty() && state.paused.isEmpty()) {
                    item(key = "empty_filter") {
                        Text(
                            text = stringResource(R.string.all_routines_empty_filter),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.l),
                        )
                    }
                }
            }
        }
    }
}

/** O que cada linha pode fazer; a mesma instância para todas as linhas. */
internal class RowActions(
    val onEdit: (Long) -> Unit,
    val onPause: (Routine) -> Unit,
    val onResume: (Routine) -> Unit,
    val onArchive: (Routine) -> Unit,
)

private fun LazyListScope.section(key: String, titleRes: Int, rows: List<AllRoutinesViewModel.Row>, actions: RowActions) {
    if (rows.isEmpty()) return
    item(key = "header_$key") {
        SectionHeader(title = stringResource(titleRes), count = rows.size)
    }
    itemsIndexed(rows, key = { _, row -> row.routine.id }) { index, row ->
        GroupedCard(index = index, count = rows.size) {
            AllRoutineRow(row = row, actions = actions)
        }
    }
}

/**
 * Uma rotina: ícone na cor dela, título, horário, dias e categoria; "Pausada" quando pausada.
 * Um nó só para o leitor de tela: o toque edita, o estado vai em `stateDescription` e pausar,
 * retomar e arquivar são ações da linha. O menu de três pontos é o mesmo conjunto de ações para
 * quem enxerga, por isso fica fora da árvore de acessibilidade (não vira um segundo nó).
 */
@Composable
internal fun AllRoutineRow(row: AllRoutinesViewModel.Row, actions: RowActions) {
    val routine = row.routine
    val pausedLabel = stringResource(R.string.all_routines_paused_state)
    val pauseLabel = stringResource(R.string.all_routines_pause)
    val resumeLabel = stringResource(R.string.all_routines_resume)
    val archiveLabel = stringResource(R.string.all_routines_archive)
    val tone = routineTone(RoutineColor.fromKey(routine.color))
    val supporting = listOfNotNull(routineTimeRange(routine), routineDaysLabel(routine), row.category?.name)
        .joinToString(SEPARATOR)
    ListRow(
        title = routine.title,
        supporting = supporting,
        modifier = Modifier
            .testTag("routine_row_${routine.id}")
            .semantics {
                role = Role.Button
                if (row.isPaused) stateDescription = pausedLabel
                customActions = listOf(
                    if (row.isPaused) {
                        CustomAccessibilityAction(resumeLabel) { actions.onResume(routine); true }
                    } else {
                        CustomAccessibilityAction(pauseLabel) { actions.onPause(routine); true }
                    },
                    CustomAccessibilityAction(archiveLabel) { actions.onArchive(routine); true },
                )
            },
        below = if (row.isPaused) {
            {
                // Lido pelo stateDescription da linha; aqui só o texto visível.
                Text(
                    text = pausedLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        } else {
            null
        },
        leading = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(tone.fill),
            ) {
                Icon(RoutineIcon.fromKey(routine.icon).imageVector, contentDescription = null, tint = tone.onFill)
            }
        },
        trailing = {
            RowMenu(
                title = routine.title,
                paused = row.isPaused,
                onPause = { actions.onPause(routine) },
                onResume = { actions.onResume(routine) },
                onArchive = { actions.onArchive(routine) },
            )
        },
        onClick = { actions.onEdit(routine.id) },
        onClickLabel = stringResource(R.string.all_routines_edit, routine.title),
    )
}

@Composable
private fun RowMenu(
    title: String,
    paused: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onArchive: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    // Fora da árvore de acessibilidade: as mesmas ações estão na linha (customActions).
    Box(modifier = Modifier.testTag(MENU_TAG).clearAndSetSemantics {}) {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.all_routines_more, title))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (paused) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.all_routines_resume)) },
                    leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                    onClick = {
                        open = false
                        onResume()
                    },
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.all_routines_pause)) },
                    leadingIcon = { Icon(Icons.Outlined.Pause, contentDescription = null) },
                    onClick = {
                        open = false
                        onPause()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.all_routines_archive)) },
                leadingIcon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
                onClick = {
                    open = false
                    onArchive()
                },
            )
        }
    }
}

/** "Todo dia", "Dias úteis", "Seg, Qua, Sex"…, com "a cada N semanas" quando houver intervalo. */
@Composable
private fun routineDaysLabel(routine: Routine): String {
    val days = when (val pattern = RoutineDays.pattern(routine.daysOfWeek, firstDayOfWeek())) {
        RoutineDays.Pattern.Everyday -> stringResource(R.string.routine_days_everyday)
        RoutineDays.Pattern.Weekdays -> stringResource(R.string.routine_days_weekdays)
        RoutineDays.Pattern.Weekend -> stringResource(R.string.routine_days_weekend)
        RoutineDays.Pattern.None -> stringResource(R.string.all_routines_days_none)
        is RoutineDays.Pattern.Custom -> pattern.days.map { weekdayName(it, WeekdayName.SHORT) }.joinToString(", ")
    }
    if (routine.weekInterval <= 1) return days
    return days + SEPARATOR + stringResource(R.string.routine_week_interval_every, routine.weekInterval)
}

/** O filtro por categoria (P3), vindo da aba Rotinas: "Todas" e um chip por categoria. */
@Composable
private fun CategoryFilterRow(
    categories: List<RoutineCategory>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        item(key = "category_all") {
            FilterChip(
                selected = selectedId == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.routine_category_filter_all), maxLines = 1, softWrap = false) },
            )
        }
        items(categories, key = { it.id }) { category ->
            FilterChip(
                selected = selectedId == category.id,
                onClick = { onSelect(category.id) },
                // Uma linha só: o chip cresce na largura e a LazyRow rola (nada corta com fonte grande).
                label = { Text(category.name, maxLines = 1, softWrap = false) },
                leadingIcon = {
                    Dot(color = routineTone(RoutineColor.fromKey(category.color)).accent, size = 10.dp, filled = true)
                },
            )
        }
    }
}

private const val SEPARATOR = " · "

/** Tag do menu de três pontos de cada linha (fora da árvore de acessibilidade; para os testes). */
internal const val MENU_TAG = "all_routines_menu"
