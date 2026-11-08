@file:OptIn(ExperimentalMaterial3Api::class)

package com.kalendrys.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kalendrys.app.BuildConfig
import com.kalendrys.app.R
import com.kalendrys.app.reminder.canScheduleExactAlarms
import com.kalendrys.app.reminder.requestExactAlarmPermissionIntent
import com.kalendrys.app.ui.components.TimePickerDialog
import com.kalendrys.app.ui.components.groupedItemShape
import com.kalendrys.app.ui.settings.SettingsViewModel.ExportEvent
import com.kalendrys.app.ui.settings.SettingsViewModel.UpdateUi
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.collectLatest

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val DISABLED_ALPHA = 0.38f

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val updateState by viewModel.update.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    // Relidos ao voltar das configurações do sistema, onde o usuário pode ter mudado as permissões.
    var notificationsEnabled by remember { mutableStateOf(true) }
    var exactAlarmsAllowed by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        exactAlarmsAllowed = canScheduleExactAlarms(context)
    }

    // Seletor de arquivo do sistema (Downloads, Drive...): dispensa permissão de armazenamento.
    val snackbarHostState = remember { SnackbarHostState() }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.exportTo(uri)
    }
    val res = context.resources
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.export.collectLatest { event ->
            when (event) {
                is ExportEvent.ChooseFile -> createFile.launch(event.suggestedName)
                is ExportEvent.Exported -> snackbarHostState.showSnackbar(
                    res.getQuantityString(R.plurals.settings_export_done, event.count, event.count),
                )
                ExportEvent.NothingToExport -> snackbarHostState.showSnackbar(res.getString(R.string.settings_export_empty))
                ExportEvent.Failed -> snackbarHostState.showSnackbar(res.getString(R.string.settings_export_failed))
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val current = settings ?: return@Scaffold
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item(key = "summary_header") { SectionHeader(stringResource(R.string.settings_section_summary)) }
            item(key = "summary_enabled") {
                SwitchRow(
                    title = stringResource(R.string.settings_summary_enabled),
                    supporting = stringResource(R.string.settings_summary_enabled_description),
                    checked = current.summaryEnabled,
                    onCheckedChange = viewModel::setSummaryEnabled,
                    shape = groupedItemShape(0, 2),
                )
            }
            item(key = "summary_time") {
                SettingsRow(
                    title = stringResource(R.string.settings_summary_time),
                    supporting = stringResource(R.string.settings_summary_time_description),
                    enabled = current.summaryEnabled,
                    onClick = { showTimePicker = true },
                    shape = groupedItemShape(1, 2),
                    trailing = {
                        Text(
                            text = current.summaryTime.format(TIME),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
            }

            item(key = "notifications_header") { SectionHeader(stringResource(R.string.settings_section_notifications)) }
            // O aviso de alarme exato, quando aparece, fecha o grupo da seção.
            val notificationsCount = if (exactAlarmsAllowed) 1 else 2
            item(key = "notifications_system") {
                SettingsRow(
                    title = stringResource(R.string.settings_notifications_system),
                    supporting = stringResource(
                        if (notificationsEnabled) {
                            R.string.settings_notifications_system_description
                        } else {
                            R.string.settings_notifications_off
                        },
                    ),
                    supportingIsError = !notificationsEnabled,
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    },
                    shape = groupedItemShape(0, notificationsCount),
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
            if (!exactAlarmsAllowed) {
                item(key = "exact_alarms_warning") {
                    ExactAlarmsWarning(
                        onAllow = { context.startActivity(requestExactAlarmPermissionIntent(context)) },
                        shape = groupedItemShape(1, notificationsCount),
                    )
                }
            }

            item(key = "data_header") { SectionHeader(stringResource(R.string.settings_section_data)) }
            item(key = "export") {
                SettingsRow(
                    title = stringResource(R.string.settings_export),
                    supporting = stringResource(R.string.settings_export_description),
                    onClick = viewModel::requestExport,
                    shape = groupedItemShape(0, 1),
                    trailing = {
                        Icon(
                            imageVector = Icons.Outlined.FileDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }

            item(key = "about_header") { SectionHeader(stringResource(R.string.settings_section_about)) }
            item(key = "update") {
                UpdateRow(
                    state = updateState,
                    shape = groupedItemShape(0, 2),
                    onCheck = viewModel::checkForUpdates,
                    onDownload = viewModel::downloadUpdate,
                    onGrantPermission = viewModel::grantInstallPermission,
                )
            }
            item(key = "version") {
                SettingsRow(
                    title = stringResource(R.string.settings_version),
                    supporting = BuildConfig.VERSION_NAME,
                    shape = groupedItemShape(1, 2),
                )
            }
        }
    }

    val current = settings
    if (showTimePicker && current != null) {
        TimePickerDialog(
            title = stringResource(R.string.settings_summary_time_pick),
            initialTime = current.summaryTime,
            onConfirm = { time ->
                viewModel.setSummaryTime(time)
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

/**
 * Linha "Buscar atualizações": o texto de apoio e o toque mudam conforme o estado do atualizador.
 * Toque pede checagem (ou baixa a nova versão / abre a permissão), e nunca age sozinha — sem o
 * toque, nada acontece. Enquanto verifica ou baixa, a linha fica sem clique.
 */
@Composable
private fun UpdateRow(
    state: UpdateUi,
    shape: Shape,
    onCheck: () -> Unit,
    onDownload: (String) -> Unit,
    onGrantPermission: () -> Unit,
) {
    val supporting = when (state) {
        UpdateUi.Idle -> stringResource(R.string.settings_update_description)
        UpdateUi.Checking -> stringResource(R.string.settings_update_checking)
        UpdateUi.UpToDate -> stringResource(R.string.settings_update_uptodate)
        is UpdateUi.Available -> stringResource(R.string.settings_update_available, state.versionName)
        is UpdateUi.Downloading -> stringResource(R.string.settings_update_downloading, state.percent)
        UpdateUi.AwaitingInstall -> stringResource(R.string.settings_update_installing)
        UpdateUi.NeedsPermission -> stringResource(R.string.settings_update_needs_permission)
        UpdateUi.Failed -> stringResource(R.string.settings_update_failed)
    }
    val onClick: (() -> Unit)? = when (state) {
        UpdateUi.Checking, is UpdateUi.Downloading -> null
        is UpdateUi.Available -> ({ onDownload(state.apkUrl) })
        UpdateUi.NeedsPermission -> onGrantPermission
        else -> onCheck
    }
    SettingsRow(
        title = stringResource(R.string.settings_update),
        supporting = supporting,
        supportingIsError = state == UpdateUi.Failed,
        onClick = onClick,
        shape = shape,
        trailing = {
            Icon(
                imageVector = if (state is UpdateUi.Available) Icons.Outlined.FileDownload else Icons.Outlined.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/**
 * Aviso de que a permissão "Alarmes e lembretes" está desativada. Tom sóbrio, com um atalho
 * direto para a tela do sistema onde o usuário concede a permissão.
 */
@Composable
private fun ExactAlarmsWarning(onAllow: () -> Unit, shape: Shape) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.settings_exact_alarms_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_exact_alarms_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAllow, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.settings_exact_alarms_action))
            }
        }
    }
}

/** Linha de configuração; clicável quando [onClick] é informado. */
@Composable
private fun SettingsRow(
    title: String,
    supporting: String,
    shape: Shape,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingIsError: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val color = MaterialTheme.colorScheme.surfaceContainer
    val rowModifier = modifier
        .fillMaxWidth()
        .padding(bottom = 2.dp)
    if (onClick != null) {
        Surface(onClick = onClick, enabled = enabled, shape = shape, color = color, modifier = rowModifier) {
            RowContent(title, supporting, enabled, supportingIsError, trailing)
        }
    } else {
        Surface(shape = shape, color = color, modifier = rowModifier) {
            RowContent(title, supporting, enabled, supportingIsError, trailing)
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: Shape,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
    ) {
        // A linha inteira alterna o switch; o Switch em si fica sem clique próprio (onCheckedChange = null).
        Row(
            modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowContent(title, supporting, enabled = true, supportingIsError = false) {
                Switch(checked = checked, onCheckedChange = null)
            }
        }
    }
}

@Composable
private fun RowContent(
    title: String,
    supporting: String,
    enabled: Boolean,
    supportingIsError: Boolean,
    trailing: @Composable (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = 72.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = if (supportingIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            trailing()
        }
    }
}
