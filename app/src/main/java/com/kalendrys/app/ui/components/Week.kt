package com.kalendrys.app.ui.components

import android.content.res.Resources
import androidx.annotation.ArrayRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kalendrys.app.R
import com.kalendrys.app.domain.AppLocale
import java.time.DayOfWeek

/** Primeiro dia da semana do app (domingo), usado pelo calendário, pela semana das rotinas e pelos seletores de dia. */
fun firstDayOfWeek(): DayOfWeek = AppLocale.FIRST_DAY_OF_WEEK

/**
 * Nomes dos dias da semana em pt-BR, de uma tabela própria em `strings.xml` (arrays indexados a
 * partir do DOMINGO), em vez de `DayOfWeek.getDisplayName`, que depende dos dados de idioma do
 * aparelho. Três tamanhos:
 * - [WeekdayName.SHORT] "Dom", "Seg", … "Sáb": seletores de dias e faixa da semana;
 * - [WeekdayName.NARROW] "D", "S", "T", …: cabeçalhos do calendário e do widget (colunas estreitas);
 * - [WeekdayName.FULL] "domingo", "segunda-feira", …: descrição para o leitor de tela.
 */
enum class WeekdayName(@ArrayRes val arrayRes: Int) {
    SHORT(R.array.weekday_short),
    NARROW(R.array.weekday_narrow),
    FULL(R.array.weekday_full),
}

/** Posição de [day] nas tabelas de `strings.xml`, que começam no domingo. */
internal fun weekdayIndex(day: DayOfWeek): Int = day.value % 7

fun Resources.weekdayName(day: DayOfWeek, style: WeekdayName): String =
    getStringArray(style.arrayRes)[weekdayIndex(day)]

@Composable
fun weekdayName(day: DayOfWeek, style: WeekdayName): String =
    stringArrayResource(style.arrayRes)[weekdayIndex(day)]

/**
 * Seletor de dias da semana (rotina e recorrência personalizada da tarefa): sete botões a partir do
 * domingo, com o nome curto ("Dom" … "Sáb") e o nome completo para o leitor de tela. Em fonte normal
 * são círculos de 40dp numa linha só; com fonte grande o botão cresce em pílula e a linha quebra
 * (FlowRow) em vez de cortar o texto.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeekdayToggleRow(
    isSelected: (DayOfWeek) -> Boolean,
    onToggle: (DayOfWeek) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstDay = firstDayOfWeek()
    val colors = MaterialTheme.colorScheme
    FlowRow(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        for (offset in 0L until 7L) {
            val day = firstDay.plus(offset)
            val selected = isSelected(day)
            val fullName = weekdayName(day, WeekdayName.FULL)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.primary else colors.surfaceContainerHigh)
                    .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle(day) })
                    .semantics { contentDescription = fullName }
                    .padding(horizontal = 6.dp),
            ) {
                Text(
                    text = weekdayName(day, WeekdayName.SHORT),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) colors.onPrimary else colors.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}
