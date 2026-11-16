package com.kalendrys.app.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import com.kalendrys.app.data.settings.ThemeMode

/**
 * Espelho do [ThemeMode] no modo noturno do app guardado pelo sistema (Android 12+), para a janela
 * que o próprio sistema desenha antes do app rodar (splash e fundo da janela) já sair no tema
 * escolhido. Parecer do Esquadro sobre a 12.3: a fonte da verdade é o DataStore; o espelho só serve
 * a essa janela. O tema do Compose não depende dele ([isDark]), e antes do Android 12 nada é feito
 * (lá a janela inicial segue o aparelho; limite aceito).
 *
 * Um único ponto aplica: o coletor da aparência no [com.kalendrys.app.KalendrysApp]. A chamada muda
 * a configuração do app (e recria a Activity), então só acontece quando o valor muda.
 */
class AppNightMode(
    private val context: Context,
    private val setMode: (Int) -> Unit = { mode ->
        // A guarda de [apply] já barra antes do 12; repetida aqui porque o lint não a enxerga no lambda.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(mode)
        }
    },
) {
    private var applied: Int? = null

    fun apply(themeMode: ThemeMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val mode = nightModeFor(themeMode)
        if (mode == applied) return
        setMode(mode)
        applied = mode
    }

    companion object {
        /**
         * [UiModeManager.MODE_NIGHT_AUTO] devolve o app ao modo do aparelho: a documentação não diz
         * isso com todas as letras ("decide pela localização e sensores"), por isso o caso "forçar
         * escuro, voltar para Sistema, trocar o tema do aparelho" é conferido no aceite.
         */
        fun nightModeFor(themeMode: ThemeMode): Int = when (themeMode) {
            ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
        }
    }
}
