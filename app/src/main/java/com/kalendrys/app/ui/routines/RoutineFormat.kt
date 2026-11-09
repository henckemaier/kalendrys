package com.kalendrys.app.ui.routines

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kalendrys.app.R
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.domain.DurationFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** "07:05" a partir dos minutos do dia; passa da meia-noite para os blocos que terminam no dia seguinte. */
fun minuteOfDayLabel(minute: Int): String = LocalTime.of(minute % 1440 / 60, minute % 60).format(TIME)

/** "17:20–17:35 (15 min)", ou só "17:20" para um bloco sem duração. */
@Composable
fun routineTimeRange(routine: Routine): String = routineTimeRange(routine.startMinute, routine.durationMinutes)

/** Faixa de horário a partir de um início efetivo (honra uma remarcação) e a duração. */
@Composable
fun routineTimeRange(startMinute: Int, durationMinutes: Int): String =
    if (durationMinutes == 0) {
        minuteOfDayLabel(startMinute)
    } else {
        stringResource(
            R.string.routine_time_range,
            minuteOfDayLabel(startMinute),
            minuteOfDayLabel(startMinute + durationMinutes),
            DurationFormat.format(durationMinutes),
        )
    }
