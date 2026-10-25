package com.kalendrys.app.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

private val Outer = 20.dp
private val Inner = 6.dp

// Só existem quatro combinações; criadas uma vez em vez de a cada linha recomposta.
private val Single = RoundedCornerShape(Outer)
private val First = RoundedCornerShape(topStart = Outer, topEnd = Outer, bottomStart = Inner, bottomEnd = Inner)
private val Middle = RoundedCornerShape(Inner)
private val Last = RoundedCornerShape(topStart = Inner, topEnd = Inner, bottomStart = Outer, bottomEnd = Outer)

/** Lista agrupada: cantos grandes nas pontas do grupo, pequenos entre itens. */
fun groupedItemShape(index: Int, count: Int): Shape = when {
    count == 1 -> Single
    index == 0 -> First
    index == count - 1 -> Last
    else -> Middle
}
