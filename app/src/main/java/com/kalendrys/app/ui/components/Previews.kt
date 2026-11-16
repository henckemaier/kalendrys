package com.kalendrys.app.ui.components

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

/**
 * Prévia padrão dos componentes (ADR-0002, 4.2): tema claro, escuro e fonte 2.0.
 *
 * Uso: anote a função de prévia com `@KalendrysPreviews` e envolva o conteúdo em
 * `KalendrysTheme { Surface { ... } }`. O tema padrão segue o sistema, então a prévia "Escuro"
 * (uiMode noturno) sai escura sem parâmetro extra; o `Surface` dá o fundo certo em cada tema.
 */
@Preview(name = "Claro")
@Preview(name = "Escuro", uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(name = "Fonte 2.0", fontScale = 2f)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
annotation class KalendrysPreviews
