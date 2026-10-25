package com.kalendrys.app.domain

/** "15 min", "1h", "1h 20min": usado nos blocos, nos intervalos e no editor de rotinas. */
object DurationFormat {

    fun format(minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> "$rest min"
            rest == 0 -> "${hours}h"
            else -> "${hours}h ${rest}min"
        }
    }
}
