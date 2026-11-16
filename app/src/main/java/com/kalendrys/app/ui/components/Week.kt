package com.kalendrys.app.ui.components

import android.content.res.Resources
import androidx.annotation.ArrayRes
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
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
 * domingo, com o nome curto ("Dom" … "Sáb") e o nome completo para o leitor de tela.
 *
 * Alvo de toque (v1.7.1, E2): cada dia é uma célula de [WEEKDAY_CELL_HEIGHT] de altura, e as
 * células de uma linha dividem a largura em partes iguais, sem vão entre vizinhos; o círculo visual
 * de [WEEKDAY_CIRCLE_SIZE] fica centrado na célula, com o efeito de toque nele. Em fonte normal são
 * sete por linha (1/7 da linha; em 360dp uns 44dp de largura, limite aceito). Com fonte grande o
 * círculo cresce em pílula: a largura da pílula mais larga decide quantos dias cabem por linha e o
 * seletor quebra em mais linhas em vez de cortar o texto. As linhas seguintes mantêm a mesma
 * largura de célula, alinhadas à primeira.
 */
@Composable
fun WeekdayToggleRow(
    isSelected: (DayOfWeek) -> Boolean,
    onToggle: (DayOfWeek) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstDay = firstDayOfWeek()
    val days = remember(firstDay) { (0L until 7L).map { firstDay.plus(it) } }
    val shortNames = days.map { weekdayName(it, WeekdayName.SHORT) }
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Largura da pílula mais larga: texto mais o respiro de cada lado, nunca menos que o círculo.
    val pillWidth = remember(shortNames, style, density) {
        val textPx = shortNames.maxOf { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width }
        with(density) { textPx.toDp() } + WEEKDAY_PILL_PADDING * 2
    }.coerceAtLeast(WEEKDAY_CIRCLE_SIZE)

    BoxWithConstraints(modifier.fillMaxWidth()) {
        // 1dp de folga para o arredondamento em px não deixar a célula 1px menor que a pílula.
        val perRow = if (constraints.hasBoundedWidth) (maxWidth / (pillWidth + 1.dp)).toInt().coerceIn(1, 7) else 7
        Column {
            days.chunked(perRow).forEach { rowDays ->
                Row(Modifier.fillMaxWidth()) {
                    rowDays.forEach { day ->
                        key(day) {
                            WeekdayCell(
                                day = day,
                                selected = isSelected(day),
                                onToggle = { onToggle(day) },
                                style = style,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    // Linha incompleta: o resto fica vazio para as células manterem a mesma largura.
                    if (rowDays.size < perRow) Spacer(Modifier.weight((perRow - rowDays.size).toFloat()))
                }
            }
        }
    }
}

@Composable
private fun WeekdayCell(
    day: DayOfWeek,
    selected: Boolean,
    onToggle: () -> Unit,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val fullName = weekdayName(day, WeekdayName.FULL)
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = WEEKDAY_CELL_HEIGHT)
            .toggleable(
                value = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .semantics { contentDescription = fullName },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .defaultMinSize(minWidth = WEEKDAY_CIRCLE_SIZE, minHeight = WEEKDAY_CIRCLE_SIZE)
                .clip(CircleShape)
                .background(if (selected) colors.primary else colors.surfaceContainerHigh)
                .indication(interaction, ripple())
                .padding(horizontal = WEEKDAY_PILL_PADDING),
        ) {
            Text(
                text = weekdayName(day, WeekdayName.SHORT),
                style = style,
                color = if (selected) colors.onPrimary else colors.onSurface,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/** Altura mínima da célula de toque de cada dia (alvo de 48dp). */
internal val WEEKDAY_CELL_HEIGHT = 48.dp

/** Círculo visual do dia, centrado na célula. */
internal val WEEKDAY_CIRCLE_SIZE = 40.dp

/** Respiro de cada lado do nome curto dentro da pílula. */
private val WEEKDAY_PILL_PADDING = 6.dp
