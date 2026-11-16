package com.kalendrys.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Formas do app (ADR-0002, 12.3): 12dp nos elementos pequenos (chips, campos destacados), 16dp nos
 * cartões e 20dp nos grupos e painéis. Os extremos (extraSmall, extraLarge) ficam no padrão do
 * Material 3.
 */
val KalendrysShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
)
