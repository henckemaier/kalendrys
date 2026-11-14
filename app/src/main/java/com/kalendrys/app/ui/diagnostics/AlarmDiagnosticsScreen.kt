@file:OptIn(ExperimentalMaterial3Api::class)

package com.kalendrys.app.ui.diagnostics

import android.app.AlarmManager
import android.content.Context
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kalendrys.app.R
import com.kalendrys.app.reminder.canScheduleExactAlarms
import com.kalendrys.app.reminder.ignoreBatteryOptimizationsSettingsIntent
import com.kalendrys.app.reminder.isIgnoringBatteryOptimizations
import com.kalendrys.app.reminder.requestExactAlarmPermissionIntent
import com.kalendrys.app.ui.components.groupedItemShape
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import com.kalendrys.app.domain.AppLocale

/**
 * Tela de diagnóstico: mostra, em um lugar só, por que um lembrete pode não disparar no horário.
 * Três checagens, cada uma com um atalho para a tela do sistema que a corrige:
 *  - próximo alarme que o sistema vai disparar (confirma que há algo agendado);
 *  - permissão de alarme exato ([canScheduleExactAlarms]);
 *  - otimização de bateria ([isIgnoringBatteryOptimizations]).
 *
 * O estado é relido em ON_RESUME, porque o usuário pode ter mudado as permissões na tela do
 * sistema e voltado para cá.
 */
@Composable
fun AlarmDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var nextAlarmMillis by remember { mutableStateOf<Long?>(null) }
    var exactAllowed by remember { mutableStateOf(true) }
    var batteryUnrestricted by remember { mutableStateOf(true) }
    var showBatterySheet by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        nextAlarmMillis = nextAlarmClockTriggerMillis(context)
        exactAllowed = canScheduleExactAlarms(context)
        batteryUnrestricted = isIgnoringBatteryOptimizations(context)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item(key = "header") { SectionHeader(stringResource(R.string.diagnostics_section)) }
            item(key = "next_alarm") {
                val next = nextAlarmMillis
                DiagnosticRow(
                    title = stringResource(R.string.diagnostics_next_alarm_title),
                    status = next?.let(::formatTrigger) ?: stringResource(R.string.diagnostics_next_alarm_none),
                    // Informativo: não ter um próximo alarme é normal quando não há lembretes pendentes.
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
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
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
            .padding(horizontal = 20.dp, vertical = 12.dp),
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
        Spacer(Modifier.width(16.dp))
        if (hasFix) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.diagnostics_fix),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
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
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.battery_flow_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.battery_flow_why),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            StepRow(1, stringResource(R.string.battery_flow_step1))
            StepRow(2, stringResource(R.string.battery_flow_step2))
            StepRow(3, stringResource(R.string.battery_flow_step3))
            Spacer(Modifier.height(24.dp))
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Icon(imageVector = Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.battery_flow_open))
            }
            Spacer(Modifier.height(4.dp))
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
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** O horário do próximo alarme-despertador que o sistema vai disparar (de qualquer app), ou null. */
private fun nextAlarmClockTriggerMillis(context: Context): Long? {
    val manager = context.getSystemService(AlarmManager::class.java) ?: return null
    return manager.nextAlarmClock?.triggerTime
}

private val TRIGGER_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(AppLocale.LOCALE)

private fun formatTrigger(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(TRIGGER_FORMAT)
