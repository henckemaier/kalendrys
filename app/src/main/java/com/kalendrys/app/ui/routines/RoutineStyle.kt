package com.kalendrys.app.ui.routines

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Church
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalLaundryService
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Shower
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.Yard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineIcon

/** As três cores de um bloco: o preenchimento da pílula, o ícone sobre ela e o destaque (círculo, pontos). */
@Immutable
data class RoutineTone(val fill: Color, val onFill: Color, val accent: Color)

/**
 * Paleta fixa das rotinas. No tema escuro os blocos são pastéis sobre o fundo escuro; no claro,
 * tons suaves com o destaque mais forte. Os widgets usam sempre a variante escura.
 */
object RoutinePalette {

    fun light(color: RoutineColor): RoutineTone = when (color) {
        RoutineColor.BLUE -> RoutineTone(Color(0xFFD6E3FF), Color(0xFF1B3A6B), Color(0xFF3F65B0))
        RoutineColor.TEAL -> RoutineTone(Color(0xFFC6F0EA), Color(0xFF00423A), Color(0xFF00897B))
        RoutineColor.GREEN -> RoutineTone(Color(0xFFD5F2C4), Color(0xFF1F4D12), Color(0xFF3C8A2E))
        RoutineColor.YELLOW -> RoutineTone(Color(0xFFFBEBB0), Color(0xFF4A3B00), Color(0xFF9A7600))
        RoutineColor.ORANGE -> RoutineTone(Color(0xFFFFDCC2), Color(0xFF5A2D00), Color(0xFFC2600A))
        RoutineColor.PINK -> RoutineTone(Color(0xFFFFD9E3), Color(0xFF6E1F3D), Color(0xFFC2416F))
        RoutineColor.PURPLE -> RoutineTone(Color(0xFFEADDFF), Color(0xFF3F2470), Color(0xFF7652B8))
        RoutineColor.GRAY -> RoutineTone(Color(0xFFE3E2E6), Color(0xFF3A3B3F), Color(0xFF6E6F74))
    }

    fun dark(color: RoutineColor): RoutineTone = when (color) {
        RoutineColor.BLUE -> RoutineTone(Color(0xFFA8C8FF), Color(0xFF0A2A5C), Color(0xFFA8C8FF))
        RoutineColor.TEAL -> RoutineTone(Color(0xFF8CE0D6), Color(0xFF00382F), Color(0xFF8CE0D6))
        RoutineColor.GREEN -> RoutineTone(Color(0xFFB5E3A1), Color(0xFF11380A), Color(0xFFB5E3A1))
        RoutineColor.YELLOW -> RoutineTone(Color(0xFFF4D97A), Color(0xFF3A2E00), Color(0xFFF4D97A))
        RoutineColor.ORANGE -> RoutineTone(Color(0xFFFFC08F), Color(0xFF4A2300), Color(0xFFFFC08F))
        RoutineColor.PINK -> RoutineTone(Color(0xFFFFB1C8), Color(0xFF5C1131), Color(0xFFFFB1C8))
        RoutineColor.PURPLE -> RoutineTone(Color(0xFFD3BCFF), Color(0xFF36195E), Color(0xFFD3BCFF))
        RoutineColor.GRAY -> RoutineTone(Color(0xFFC8C6CC), Color(0xFF2E2F33), Color(0xFFC8C6CC))
    }
}

@Composable
fun routineTone(color: RoutineColor): RoutineTone =
    if (isSystemInDarkTheme()) RoutinePalette.dark(color) else RoutinePalette.light(color)

val RoutineIcon.imageVector: ImageVector
    get() = when (this) {
        RoutineIcon.STAR -> Icons.Filled.Star
        RoutineIcon.WORKOUT -> Icons.Filled.FitnessCenter
        RoutineIcon.RUN -> Icons.AutoMirrored.Filled.DirectionsRun
        RoutineIcon.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
        RoutineIcon.BIKE -> Icons.AutoMirrored.Filled.DirectionsBike
        RoutineIcon.YOGA -> Icons.Filled.SelfImprovement
        RoutineIcon.READ -> Icons.AutoMirrored.Filled.MenuBook
        RoutineIcon.STUDY -> Icons.Filled.School
        RoutineIcon.WORK -> Icons.Filled.Work
        RoutineIcon.COMPUTER -> Icons.Filled.Laptop
        RoutineIcon.CODE -> Icons.Filled.Code
        RoutineIcon.JOURNAL -> Icons.Filled.EditNote
        RoutineIcon.LANGUAGE -> Icons.Filled.Translate
        RoutineIcon.SKINCARE -> Icons.Filled.Spa
        RoutineIcon.SHOWER -> Icons.Filled.Shower
        RoutineIcon.MEAL -> Icons.Filled.Restaurant
        RoutineIcon.COFFEE -> Icons.Filled.LocalCafe
        RoutineIcon.WATER -> Icons.Filled.WaterDrop
        RoutineIcon.MEDICINE -> Icons.Filled.Medication
        RoutineIcon.SLEEP -> Icons.Filled.Bedtime
        RoutineIcon.MORNING -> Icons.Filled.WbSunny
        RoutineIcon.MUSIC -> Icons.Filled.MusicNote
        RoutineIcon.TV -> Icons.Filled.Tv
        RoutineIcon.MOVIE -> Icons.Filled.Movie
        RoutineIcon.GAME -> Icons.Filled.SportsEsports
        RoutineIcon.PETS -> Icons.Filled.Pets
        RoutineIcon.CLEANING -> Icons.Filled.CleaningServices
        RoutineIcon.LAUNDRY -> Icons.Filled.LocalLaundryService
        RoutineIcon.SHOPPING -> Icons.Filled.ShoppingCart
        RoutineIcon.CAR -> Icons.Filled.DirectionsCar
        RoutineIcon.FAMILY -> Icons.Filled.Groups
        RoutineIcon.PRAYER -> Icons.Filled.Church
        RoutineIcon.SAVINGS -> Icons.Filled.Savings
        RoutineIcon.GARDEN -> Icons.Filled.Yard
        RoutineIcon.CALL -> Icons.Filled.Call
    }
