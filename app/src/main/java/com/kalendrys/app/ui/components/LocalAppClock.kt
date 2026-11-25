package com.kalendrys.app.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.kalendrys.app.data.repository.AppClock
import com.kalendrys.app.data.repository.SystemAppClock

/**
 * O relógio do app para as telas (v1.10.0, ADR-0002 16.3), provido na MainActivity com o
 * [com.kalendrys.app.KalendrysApp.clock]. Regra: o composable só lê o relógio para valor que é da
 * PRÓPRIA tela (data inicial de um seletor, o minuto corrente, um rótulo "Hoje"/"Ontem"); o que
 * decide o que aparece numa lista (hoje, atrasada, concluída hoje) vem do estado do ViewModel.
 *
 * O padrão é o relógio do sistema, para teste de composição e preview não precisarem prover; teste
 * que afirma algo dependente de data PROVÊ um relógio falso.
 */
val LocalAppClock = staticCompositionLocalOf<AppClock> { SystemAppClock }
