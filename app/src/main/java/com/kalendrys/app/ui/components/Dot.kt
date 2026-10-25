package com.kalendrys.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Marcador redondo dos dias (calendário, semana das rotinas): cheio, ou só o contorno quando [filled] é falso. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 5.dp, filled: Boolean = true) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .then(if (filled) Modifier.background(color) else Modifier.border(1.dp, color, CircleShape)),
    )
}
