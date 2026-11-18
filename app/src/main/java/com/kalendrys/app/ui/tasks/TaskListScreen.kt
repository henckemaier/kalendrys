@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.kalendrys.app.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kalendrys.app.R
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.ShoppingProgress
import com.kalendrys.app.domain.TaskSchedule
import com.kalendrys.app.ui.components.AppScaffold
import com.kalendrys.app.ui.components.AppTopBar
import com.kalendrys.app.ui.components.CollapsingFab
import com.kalendrys.app.ui.components.EmptyState
import com.kalendrys.app.ui.components.HapticEvent
import com.kalendrys.app.ui.components.SwipeAction
import com.kalendrys.app.ui.components.SwipeRow
import com.kalendrys.app.ui.components.TopBarAction
import com.kalendrys.app.ui.components.groupedItemShape
import com.kalendrys.app.ui.components.rememberFabExpanded
import com.kalendrys.app.ui.components.plus
import com.kalendrys.app.ui.components.weekdayDateLabel
import com.kalendrys.app.ui.shopping.ShoppingProgressTag
import com.kalendrys.app.ui.theme.Spacing
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Lista de Tarefas. O Desfazer de excluir (deslize) e de concluir (círculo) é a oferta do app
 * ([com.kalendrys.app.UndoOffers], v1.8.0 E5): a tela só pede a ação e a mensagem vem do coletor do
 * `KalendrysNavHost`, desenhada pelo [AppScaffold]; por isso sobrevive a girar a tela. A tela não tem
 * mensagem própria.
 *
 * Busca e chips são ITENS da lista (E5, 14.6): o campo de busca rolado para fora da tela perde o
 * foco (o item sai da composição), mas o texto digitado fica, porque mora no ViewModel.
 */
@Composable
fun TaskListScreen(
    onOpenTask: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    /** Pede o painel de nova tarefa (hospedado no topo da navegação, E6) sugerindo hoje. */
    onNewTask: (LocalDate) -> Unit = {},
    viewModel: TaskViewModel = viewModel(factory = TaskViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val fabExpanded by rememberFabExpanded(listState)

    val settingsLabel = stringResource(R.string.settings_title)
    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.tab_tasks),
                actions = listOf(TopBarAction(settingsLabel, Icons.Outlined.Settings, onClick = onOpenSettings)),
            )
        },
        floatingActionButton = {
            CollapsingFab(
                text = stringResource(R.string.new_task),
                expanded = fabExpanded,
                onClick = { onNewTask(LocalDate.now()) },
            )
        },
        hasFab = true,
    ) { padding ->
        when {
            !state.isLoaded -> Unit
            // Sem nenhuma tarefa criada: vazio "comece por aqui" (sem busca nem filtros).
            !state.hasTasks -> EmptyState(
                icon = Icons.Outlined.TaskAlt,
                title = stringResource(R.string.empty_tasks_title),
                message = stringResource(R.string.empty_tasks_message),
                actionLabel = stringResource(R.string.new_task),
                onAction = { onNewTask(LocalDate.now()) },
                modifier = Modifier.padding(padding),
            )
            // E5 (14.6): busca, chips e o vazio da busca são ITENS da lista e rolam junto com as tarefas
            // (antes ficavam fixos acima dela, e em paisagem com fonte 2.0 não sobrava tarefa visível
            // no 1º quadro). O preenchimento da casca vai inteiro para o contentPadding: topo, laterais
            // (com a margem da tela) e fundo (insets, largura máxima e folga do FAB). O teclado já
            // encolhe a casca (adjustResize, B-1): o campo focado no topo da lista fica acima dele.
            else -> {
                val today = state.today
                val todayLabel = remember(today) { weekdayDateLabel(today) }
                val sections = listOf(
                    Triple("overdue", R.string.section_overdue, state.overdue),
                    Triple("today", R.string.section_today, state.dueToday),
                    Triple("upcoming", R.string.section_upcoming, state.upcoming),
                    Triple("no_date", R.string.section_no_date, state.noDate),
                    Triple("done", R.string.section_done_today, state.doneToday),
                )
                val titles = sections.associate { (key, titleRes, _) -> key to stringResource(titleRes) }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = padding.plus(horizontal = Spacing.l),
                ) {
                    // Chaves estáveis: digitar filtra a lista abaixo sem recriar o campo (o foco e o
                    // teclado continuam).
                    item(key = TASK_SEARCH_KEY, contentType = "search") {
                        TaskSearchBar(
                            query = query,
                            onQueryChange = viewModel::setQuery,
                            modifier = Modifier.padding(top = Spacing.s),
                        )
                    }
                    item(key = TASK_FILTERS_KEY, contentType = "filters") {
                        TaskFilterChips(
                            selected = filters,
                            onToggle = viewModel::toggleFilter,
                            modifier = Modifier.padding(top = Spacing.s, bottom = Spacing.s),
                        )
                    }
                    if (state.isEmpty) {
                        // Há tarefas, mas a busca/filtros não deixaram nenhuma: vazio distinto, como item
                        // (a lista rola até a mensagem com fonte grande).
                        item(key = "empty_search", contentType = "empty_search") {
                            EmptyState(
                                icon = Icons.Outlined.SearchOff,
                                title = stringResource(R.string.empty_search_title),
                                message = stringResource(R.string.empty_search_message),
                                modifier = Modifier.padding(top = Spacing.xl),
                            )
                        }
                    } else {
                        item(key = "today_label", contentType = "today") {
                            Text(
                                text = todayLabel,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        sections.forEach { (key, _, items) ->
                            taskSection(
                                key = key,
                                title = titles.getValue(key),
                                items = items,
                                done = key == "done",
                                overdue = key == "overdue",
                                // Na seção "Hoje" a data é óbvia; nas outras ela aparece na linha.
                                showDate = key == "overdue" || key == "upcoming",
                                today = today,
                                onOpen = { onOpenTask(it.id) },
                                onComplete = viewModel::complete,
                                onDelete = viewModel::delete,
                            )
                        }
                    }
                }
            }
        }
    }

}

/** Campo de busca filled, no estilo dos cards da lista; o X aparece quando há texto. */
@Composable
private fun TaskSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.task_search_hint)) },
        leadingIcon = {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.task_search_clear))
                }
            }
        },
        shape = MaterialTheme.shapes.medium,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

/** Chips de seção (seleção múltipla). Nenhum ligado = mostra tudo; ligados = só aquelas seções. */
@Composable
private fun TaskFilterChips(
    selected: Set<TaskViewModel.Filter>,
    onToggle: (TaskViewModel.Filter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chips = listOf(
        TaskViewModel.Filter.OVERDUE to R.string.section_overdue,
        TaskViewModel.Filter.TODAY to R.string.section_today,
        TaskViewModel.Filter.UPCOMING to R.string.section_upcoming,
        TaskViewModel.Filter.NO_DATE to R.string.section_no_date,
    )
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        chips.forEach { (filter, label) ->
            val isSelected = filter in selected
            FilterChip(
                selected = isSelected,
                onClick = { onToggle(filter) },
                label = { Text(stringResource(label)) },
                leadingIcon = if (isSelected) {
                    {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}

private fun LazyListScope.taskSection(
    key: String,
    title: String,
    items: List<TaskViewModel.Item>,
    done: Boolean,
    overdue: Boolean,
    showDate: Boolean,
    today: LocalDate,
    onOpen: (Task) -> Unit,
    onComplete: (Task) -> Unit,
    onDelete: (Task) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "header_$key", contentType = "header") {
        TaskSectionHeader(title = title, count = items.size, modifier = Modifier.animateItem())
    }
    itemsIndexed(items, key = { _, item -> item.task.id }, contentType = { _, _ -> "task" }) { index, item ->
        SwipeableTaskRow(
            item = item,
            done = done,
            overdue = overdue,
            showDate = showDate,
            today = today,
            shape = groupedItemShape(index, items.size),
            onOpen = { onOpen(item.task) },
            onComplete = { onComplete(item.task) },
            onDelete = { onDelete(item.task) },
            modifier = Modifier
                .animateItem()
                .padding(bottom = 2.dp),
        )
    }
}

/**
 * Exceção ao SectionHeader de ui/components (parecer do Esquadro, NB-1 da E3): aqui a contagem fica
 * na ponta direita e o espaço acima é maior; trocar mudaria o miolo da lista, que é da v1.8.0.
 */
@Composable
private fun TaskSectionHeader(title: String, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.xl, bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwipeableTaskRow(
    item: TaskViewModel.Item,
    done: Boolean,
    overdue: Boolean,
    showDate: Boolean,
    today: LocalDate,
    shape: Shape,
    onOpen: () -> Unit,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Só o lado de excluir (deslize para a esquerda), com o Desfazer do app (E5), como antes do
    // SwipeRow: a linha fica fora da tela até o item sair da lista (removesRow). O tátil é do SwipeRow.
    val deleteAction = SwipeAction(
        label = stringResource(R.string.delete_task),
        icon = Icons.Outlined.Delete,
        event = HapticEvent.Delete,
        removesRow = true,
        onAction = onDelete,
    )
    SwipeRow(
        modifier = modifier,
        endAction = deleteAction,
        shape = shape,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { actionsModifier ->
        TaskRow(
            item = item,
            done = done,
            overdue = overdue,
            showDate = showDate,
            today = today,
            shape = shape,
            onOpen = onOpen,
            onComplete = onComplete,
            modifier = actionsModifier,
        )
    }
}

@Composable
private fun TaskRow(
    item: TaskViewModel.Item,
    done: Boolean,
    overdue: Boolean,
    showDate: Boolean,
    today: LocalDate,
    shape: Shape,
    onOpen: () -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val task = item.task
    // [modifier] traz a ação "Excluir tarefa" do SwipeRow, no mesmo nó mesclado do toque.
    Surface(
        onClick = onOpen,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 64.dp)
                .padding(start = Spacing.xs, end = Spacing.l, top = Spacing.s, bottom = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Uma que se repete e não acontece hoje (em "Próximas") não tem o que concluir agora.
            val canComplete = !done && !(task.repeat != ReminderRepeat.NONE && item.date != null && item.date != today)
            IconButton(onClick = onComplete, enabled = canComplete) {
                Icon(
                    imageVector = if (done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = stringResource(if (done) R.string.done_today else R.string.complete_task),
                    tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (task.notes.isNotBlank()) {
                    Text(
                        text = task.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TaskMeta(
                    task = task,
                    date = item.date.takeIf { showDate },
                    reminderTime = item.reminderTime,
                    overdue = overdue,
                    today = today,
                    listProgress = item.listProgress,
                )
            }
        }
    }
}

/** Linha de detalhes: data (fora da seção "Hoje"), horário do lembrete, repetição, local e lista (3/8). */
@Composable
private fun TaskMeta(
    task: Task,
    date: LocalDate?,
    reminderTime: LocalTime?,
    overdue: Boolean,
    today: LocalDate,
    listProgress: ShoppingProgress?,
) {
    // Data em que a repetição se apoia (só existe se a tarefa se repete).
    val repeatFrom = TaskSchedule.dueDate(task)?.takeIf { task.repeat != ReminderRepeat.NONE }
    if (date == null && reminderTime == null && repeatFrom == null && task.location.isBlank() && listProgress == null) return
    val accent = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (date != null) {
            MetaItem(Icons.Outlined.Event, dateLabel(date, today), accent)
        }
        if (reminderTime != null) {
            MetaItem(Icons.Outlined.Notifications, reminderTime.format(TIME), accent)
        }
        if (repeatFrom != null) {
            MetaItem(Icons.Outlined.Repeat, repeatLabel(task.repeat, repeatFrom), muted)
        }
        if (task.location.isNotBlank()) {
            MetaItem(Icons.Outlined.Place, task.location, muted)
        }
        if (listProgress != null) {
            ShoppingProgressTag(progress = listProgress, color = muted)
        }
    }
}

@Composable
private fun MetaItem(icon: ImageVector, text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Chaves estáveis da busca e dos chips na lista (E5): o campo não é recriado quando a lista filtra. */
internal const val TASK_SEARCH_KEY = "search"
internal const val TASK_FILTERS_KEY = "filters"
