package com.kalendrys.app.domain

/** Ícones disponíveis para as rotinas, guardados pelo nome (a interface mapeia cada um para um vetor). */
enum class RoutineIcon {
    STAR,
    WORKOUT,
    RUN,
    WALK,
    BIKE,
    YOGA,
    READ,
    STUDY,
    WORK,
    COMPUTER,
    CODE,
    JOURNAL,
    LANGUAGE,
    SKINCARE,
    SHOWER,
    MEAL,
    COFFEE,
    WATER,
    MEDICINE,
    SLEEP,
    MORNING,
    MUSIC,
    TV,
    MOVIE,
    GAME,
    PETS,
    CLEANING,
    LAUNDRY,
    SHOPPING,
    CAR,
    FAMILY,
    PRAYER,
    SAVINGS,
    GARDEN,
    CALL,
    ;

    companion object {
        val DEFAULT = STAR

        fun fromKey(key: String): RoutineIcon = entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}
