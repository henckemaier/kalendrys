@file:OptIn(ExperimentalMaterial3Api::class)

package com.kalendrys.app.ui.diagnostics

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kalendrys.app.R
import com.kalendrys.app.domain.AppAlarmKind
import com.kalendrys.app.reminder.canScheduleExactAlarms
import com.kalendrys.app.reminder.ignoreBatteryOptimizationsSettingsIntent
import com.kalendrys.app.reminder.isIgnoringBatteryOptimizations
import com.kalendrys.app.reminder.requestExactAlarmPermissionIntent
import com.kalendrys.app.ui.components.AppScaffold
import com.kalendrys.app.ui.components.AppTopBar
import com.kalendrys.app.ui.components.SectionHeader
import com.kalendrys.app.ui.components.groupedItemShape
import com.kalendrys.app.ui.components.plus
import com.kalendrys.app.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import com.kalendrys.app.domain.AppLocale

/**
 * Tela de diagnóstico: mostra, em um lugar só, por que um lembrete pode não disparar no horário.
 * Três checagens, cada uma com um atalho para a tela do sistema que a corrige:
 *  - próximo aviso do Kalendrys, calculado dos dados do app ([AlarmDiagnosticsViewModel]), não o
 *    próximo despertador do sistema (que pode ser de outro app, como o Relógio);
 *  - permissão de alarme exato ([canScheduleExactAlarms]);
 *  - otimização de bateria ([isIgnoringBatteryOptimizations]).
 *
 * O estado é relido em ON_RESUME, porque o usuário pode ter mudado as permissões na tela do
 * sistema e voltado para cá.
 */
@Composable
fun AlarmDiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: AlarmDiagnosticsViewModel = viewModel(factory = AlarmDiagnosticsViewModel.Factory),
) {
    val context = LocalContext.current

    val nextAlarm by viewModel.nextAlarm.collectAsStateWithLifecycle()
    var exactAllowed by remember { mutableStateOf(true) }
    var batteryUnrestricted by remember { mutableStateOf(true) }
    var showBatterySheet by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refresh()
        exactAllowed = canScheduleExactAlarms(context)
        batteryUnrestricted = isIgnoringBatteryOptimizations(context)
    }

    AppScaffold(
        topBar = { AppTopBar(title = stringResource(R.string.diagnostics_title), onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding.plus(horizontal = Spacing.l, bottom = Spacing.xl),
        ) {
            item(key = "header") {
                SectionHeader(stringResource(R.string.diagnostics_section), contentPadding = SectionPadding)
            }
            item(key = "next_alarm") {
                DiagnosticRow(
                    title = stringResource(R.string.diagnostics_next_alarm_title),
                    status = nextAlarmStatus(nextAlarm, exactAllowed),
                    // Informativo: não ter um próximo aviso é normal quando não há lembretes pendentes.
                    level = DiagStatus.Neutral,
                    shape = groupedItemShape(0, ROW_COUNT),
                )
            }
            item(key = "exact_alarm") {
                DiagnosticRow(
                    title = stringResource(R.string.diagnostics_exact_title),
                    status = stringResource(
                        if (exactAllowed) R.string.diagnostics_exact_ok else R.string.diagnostics_exact_denied,
                    ),
                    level = if (exactAllowed) DiagStatus.Ok else DiagStatus.Warn,
                    shape = groupedItemShape(1, ROW_COUNT),
                    onFix = if (exactAllowed) {
                        null
                    } else {
                        // runCatching: aparelho raro sem a tela de Settings lança ActivityNotFoundException
                        // no startActivity; o rodapé já avisa que nem todo aparelho mostra tudo.
                        { runCatching { context.startActivity(requestExactAlarmPermissionIntent(context)) } }
                    },
                )
            }
            item(key = "battery") {
                DiagnosticRow(
                    title = stringResource(R.string.diagnostics_battery_title),
                    status = stringResource(
                        if (batteryUnrestricted) R.string.diagnostics_battery_ok else R.string.diagnostics_battery_restricted,
                    ),
                    level = if (batteryUnrestricted) DiagStatus.Ok else DiagStatus.Warn,
                    shape = groupedItemShape(2, ROW_COUNT),
                    onFix = if (batteryUnrestricted) {
                        null
                    } else {
                        // T15: em vez de abrir a lista do sistema direto, mostra o passo guiado.
                        { showBatterySheet = true }
                    },
                )
            }
            item(key = "footnote") {
                Text(
                    text = stringResource(R.string.diagnostics_footnote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = Spacing.s),
                )
            }
        }
    }

    if (showBatterySheet) {
        BatteryExemptionSheet(
            onOpenSettings = {
                // runCatching: evita crash se o aparelho não tiver a tela de Settings do deep-link.
                runCatching { context.startActivity(ignoreBatteryOptimizationsSettingsIntent()) }
                showBatterySheet = false
            },
            onDismiss = { showBatterySheet = false },
        )
    }
}

private const val ROW_COUNT = 3

private enum class DiagStatus { Ok, Warn, Neutral }

/** A lista já tem a margem lateral da tela: o título de seção só ganha o espaço acima e abaixo. */
private val SectionPadding = PaddingValues(top = Spacing.xl, bottom = Spacing.s)

/**
 * Linha de status. Quando [onFix] existe, a linha inteira é clicável e leva à tela do sistema
 * (ícone "abrir fora"); caso contrário, mostra só o ícone de status.
 */
@Composable
private fun DiagnosticRow(
    title: String,
    status: String,
    level: DiagStatus,
    shape: Shape,
    onFix: (() -> Unit)? = null,
) {
    val color = MaterialTheme.colorScheme.surfaceContainer
    val rowModifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 2.dp)
    if (onFix != null) {
        Surface(onClick = onFix, shape = shape, color = color, modifier = rowModifier) {
            DiagnosticRowContent(title, status, level, hasFix = true)
        }
    } else {
        Surface(shape = shape, color = color, modifier = rowModifier) {
            DiagnosticRowContent(title, status, level, hasFix = false)
        }
    }
}

@Composable
private fun DiagnosticRowContent(title: String, status: String, level: DiagStatus, hasFix: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = 72.dp)
            .padding(horizontal = 20.dp, vertical = Spacing.m),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (level == DiagStatus.Warn) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.width(Spacing.l))
        if (hasFix) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.diagnostics_fix),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(Spacing.s))
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            StatusIcon(level)
        }
    }
}

@Composable
private fun StatusIcon(level: DiagStatus) {
    when (level) {
        DiagStatus.Ok -> Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = stringResource(R.string.diagnostics_status_ok),
            tint = MaterialTheme.colorScheme.primary,
        )
        DiagStatus.Warn -> Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = stringResource(R.string.diagnostics_status_warn),
            tint = MaterialTheme.colorScheme.error,
        )
        DiagStatus.Neutral -> Icon(
            imageVector = Icons.Outlined.Schedule,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Passo guiado da isenção de bateria (T15). Explica POR QUE isentar melhora a confiabilidade dos
 * lembretes e orienta a achar o Kalendrys na lista do sistema e desativar a otimização. O botão
 * dispara só o deep-link [ignoreBatteryOptimizationsSettingsIntent] (a lista geral do sistema) —
 * sem a permissão REQUEST_IGNORE_BATTERY_OPTIMIZATIONS. O status em si é a linha do diagnóstico;
 * esta folha é só a orientação.
 */
@Composable
private fun BatteryExemptionSheet(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xl),
        ) {
            Text(
                text = stringResource(R.string.battery_flow_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(Spacing.m))
            Text(
                text = stringResource(R.string.battery_flow_why),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            StepRow(1, stringResource(R.string.battery_flow_step1))
            StepRow(2, stringResource(R.string.battery_flow_step2))
            StepRow(3, stringResource(R.string.battery_flow_step3))
            Spacer(Modifier.height(Spacing.xl))
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Icon(imageVector = Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.battery_flow_open))
            }
            Spacer(Modifier.height(Spacing.xs))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.battery_flow_dismiss))
            }
        }
    }
}

@Composable
private fun StepRow(number: Int, text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(vertical = Spacing.xs),
    ) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(Spacing.m))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Texto da linha do próximo aviso: horário e tipo, ou "nenhum". Sem permissão de alarme exato o
 * horário continua (o app agenda inexato) com o aviso de que pode atrasar.
 */
@Composable
private fun nextAlarmStatus(state: AlarmDiagnosticsViewModel.NextAlarmState, exactAllowed: Boolean): String =
    when (state) {
        AlarmDiagnosticsViewModel.NextAlarmState.Loading -> stringResource(R.string.diagnostics_next_alarm_loading)
        is AlarmDiagnosticsViewModel.NextAlarmState.Loaded -> {
            val next = state.next
            if (next == null) {
                stringResource(R.string.diagnostics_next_alarm_none)
            } else {
                val line = stringResource(
                    R.string.diagnostics_next_alarm_at,
                    formatTrigger(next.atMillis),
                    stringResource(next.kind.labelRes),
                )
                if (exactAllowed) line else line + "\n" + stringResource(R.string.diagnostics_next_alarm_may_delay)
            }
        }
    }

private val AppAlarmKind.labelRes: Int
    get() = when (this) {
        AppAlarmKind.TASK -> R.string.diagnostics_next_alarm_kind_task
        AppAlarmKind.ROUTINE -> R.string.diagnostics_next_alarm_kind_routine
        AppAlarmKind.SUMMARY -> R.string.diagnostics_next_alarm_kind_summary
    }

private val TRIGGER_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(AppLocale.LOCALE)

private fun formatTrigger(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(TRIGGER_FORMAT)
