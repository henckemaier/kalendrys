package com.kalendrys.app.ui.routineeditor

import androidx.annotation.StringRes
import com.kalendrys.app.R
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineIcon

/**
 * Modelos prontos que pré-preenchem o editor de uma rotina nova. São só rascunhos em código —
 * nada vai ao banco sem salvar, e o usuário ajusta o que quiser depois de aplicar. O título é
 * resolvido na tela (string localizada) e aplicado ao rascunho.
 */
enum class RoutineTemplate(
    @StringRes val titleRes: Int,
    val icon: RoutineIcon,
    val color: RoutineColor,
    val startMinute: Int,
    val durationMinutes: Int,
) {
    WORKOUT(R.string.routine_template_workout, RoutineIcon.WORKOUT, RoutineColor.GREEN, 7 * 60, 60),
    LUNCH_BREAK(R.string.routine_template_lunch, RoutineIcon.MEAL, RoutineColor.ORANGE, 12 * 60, 60),
    READING(R.string.routine_template_reading, RoutineIcon.READ, RoutineColor.BLUE, 20 * 60, 30);

    /** Rascunho do modelo com o [title] já localizado. */
    fun toDraft(title: String): RoutineDraft = RoutineDraft(
        title = title,
        icon = icon,
        color = color,
        startMinute = startMinute,
        durationMinutes = durationMinutes,
    )
}
