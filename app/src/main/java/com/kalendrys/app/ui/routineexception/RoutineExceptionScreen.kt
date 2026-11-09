@file:OptIn(ExperimentalMaterial3Api::class)

package com.kalendrys.app.ui.routineexception

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kalendrys.app.R
import com.kalendrys.app.domain.RoutineOverrideType
import com.kalendrys.app.ui.components.TimePickerDialog
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.collectLatest

@Composable
fun RoutineExceptionScreen(
    onBack: () -> Unit,
    viewModel: RoutineExceptionViewModel = viewModel(factory = RoutineExceptionViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnBack by rememberUpdatedState(onBack)
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var showDayPicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                RoutineExceptionViewModel.Event.Closed -> currentOnBack()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.routine_exception_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val routine = state.routine
            Text(
                text = routine?.title?.ifBlank { stringResource(R.string.routine_new_title) }
                    ?: stringResource(R.string.routine_not_found_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
            Text(
                text = formatFullDate(state.date),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            if (state.currentType != null) {
                Text(
                    text = when (state.currentType) {
                        RoutineOverrideType.SKIP -> stringResource(R.string.routine_exception_current_skip)
                        else -> stringResource(R.string.routine_exception_current_reschedule)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
            }

            ActionRow(
                icon = { Icon(Icons.Outlined.EventBusy, contentDescription = null) },
                title = stringResource(R.string.routine_exception_skip),
                subtitle = stringResource(R.string.routine_exception_skip_desc),
                enabled = routine != null,
                onClick = viewModel::skip,
            )
            ActionRow(
                icon = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
                title = stringResource(R.string.routine_exception_reschedule_time),
                subtitle = null,
                enabled = routine != null,
                onClick = { showTimePicker = true },
            )
            ActionRow(
                icon = { Icon(Icons.Outlined.Today, contentDescription = null) },
                title = stringResource(R.string.routine_exception_move_day),
                subtitle = null,
                enabled = routine != null,
                onClick = { showDayPicker = true },
            )
            if (state.hasException) {
                ActionRow(
                    icon = { Icon(Icons.Outlined.RestartAlt, contentDescription = null) },
                    title = stringResource(R.string.routine_exception_clear),
                    subtitle = null,
                    enabled = true,
                    onClick = viewModel::clearException,
                )
            }
        }
    }

    if (showTimePicker) {
        TimePickerDialog(
            title = stringResource(R.string.routine_exception_pick_time),
            initialTime = LocalTime.of(state.startMinute / 60, state.startMinute % 60),
            onConfirm = {
                viewModel.rescheduleTime(it.hour * 60 + it.minute)
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false },
        )
    }
    if (showDayPicker) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = state.date.toEpochDay() * MILLIS_PER_DAY)
        DatePickerDialog(
            onDismissRequest = { showDayPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        picker.selectedDateMillis?.let { viewModel.moveToDay(it / MILLIS_PER_DAY) }
                        showDayPicker = false
                    },
                    enabled = picker.selectedDateMillis != null,
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDayPicker = false }) { Text(stringResource(R.string.cancel)) }
            },
        ) {
            DatePicker(state = picker)
        }
    }
}

@Composable
private fun ActionRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            icon()
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private const val MILLIS_PER_DAY = 86_400_000L
private val fullDateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)

private fun formatFullDate(date: LocalDate): String = date.format(fullDateFormatter)
