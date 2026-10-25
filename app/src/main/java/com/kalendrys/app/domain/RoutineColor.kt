package com.kalendrys.app.domain

/** Cores das rotinas, guardadas pelo nome; os tons claro e escuro ficam na interface. */
enum class RoutineColor {
    BLUE,
    TEAL,
    GREEN,
    YELLOW,
    ORANGE,
    PINK,
    PURPLE,
    GRAY,
    ;

    companion object {
        val DEFAULT = BLUE

        fun fromKey(key: String): RoutineColor = entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}
