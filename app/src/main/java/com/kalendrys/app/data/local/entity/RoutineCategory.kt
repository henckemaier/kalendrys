package com.kalendrys.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kalendrys.app.domain.RoutineCategoryKind
import com.kalendrys.app.domain.RoutineColor

/**
 * Categoria que agrupa rotinas (ex.: "Academia", "Mercado"). O [kind] decide qual módulo de item
 * a categoria usa mais tarde (T22/T23); por ora só [RoutineCategoryKind.SIMPLE] é usado.
 *
 * [kind] fica como texto, não como enum do Room: um valor removido numa versão futura cai no padrão
 * ([RoutineCategoryKind.fromKey]) em vez de derrubar a leitura do banco — como o [Routine.icon].
 */
@Entity(tableName = "routine_category")
data class RoutineCategory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: String = RoutineColor.DEFAULT.name,
    @ColumnInfo(defaultValue = "SIMPLE") val kind: String = RoutineCategoryKind.DEFAULT.name,
)
