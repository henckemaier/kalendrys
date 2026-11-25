package com.kalendrys.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.kalendrys.app.domain.AppLocale
import com.kalendrys.app.ui.components.LocalAppClock
import com.kalendrys.app.ui.navigation.KalendrysNavHost
import com.kalendrys.app.ui.theme.Appearance
import com.kalendrys.app.ui.theme.FirstFrameHold
import com.kalendrys.app.ui.theme.KalendrysTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Pedido de widget/notificação ainda não atendido pela navegação. */
    private var launchRequest by mutableStateOf<LaunchRequest?>(null)

    /** A composição já foi aplicada com a aparência lida do DataStore (só na thread principal). */
    private var themedContentReady = false

    /**
     * O app é só pt-BR ([AppLocale]): a configuração da Activity recebe o idioma do app, para os
     * componentes que leem o idioma da configuração (DatePicker do Material 3, `stringResource`) saírem
     * em português mesmo com o aparelho em outro idioma. Não usa `Locale.setDefault` (global).
     * O resto da configuração (tema escuro, escala de fonte, orientação) vem intacto do sistema.
     *
     * ATENÇÃO: isto copia a `Configuration` INTEIRA do momento da criação para um contexto fixo. Só é
     * seguro porque a Activity NÃO declara `android:configChanges` no manifesto: toda mudança (rotação,
     * tema escuro, fonte) recria a Activity e passa por aqui de novo. Se alguém declarar
     * `configChanges`, a Activity passaria a viver com a cópia antiga (tema/fonte desatualizados);
     * nesse caso, sobrepor só o idioma (ex.: `applyOverrideConfiguration` com uma `Configuration`
     * vazia contendo apenas o locale) em vez de copiar tudo.
     */
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration).apply { setLocale(AppLocale.LOCALE) }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askNotificationPermission()
        // Ao recriar (ex.: rotação) o intent é o mesmo; o pedido já foi atendido.
        if (savedInstanceState == null) launchRequest = AppLinks.parse(intent)
        val appearance = (application as KalendrysApp).appearance
        // Já lida (o caso comum): a primeira composição sai no tema certo e nada precisa esperar.
        if (appearance.value == null) holdFirstFrameUntil { themedContentReady }
        setContent {
            // Antes da primeira leitura nada é desenhado (ver holdFirstFrameUntil); o padrão só
            // preenche o tipo.
            val current by appearance.collectAsState()
            val theme = current ?: Appearance()
            if (current != null) SideEffect { themedContentReady = true }
            KalendrysTheme(themeMode = theme.themeMode, dynamicColor = theme.dynamicColor) {
                CompositionLocalProvider(LocalAppClock provides (application as KalendrysApp).clock) {
                    KalendrysNavHost(
                        launchRequest = launchRequest,
                        onLaunchRequestHandled = { launchRequest = null },
                    )
                }
            }
        }
    }

    /**
     * Segura o primeiro quadro até a composição sair com a aparência lida do DataStore (ADR-0002,
     * 12.3): sem isso o app abriria no tema padrão e trocaria para o escolhido. Espera a composição
     * aplicada, não só o valor: entre o valor chegar e o Compose recompor ainda caberia um quadro
     * velho. Enquanto segura, fica a janela inicial (no Android 12+ já no tema escolhido, pelo espelho
     * do KalendrysApp). Sem runBlocking: a leitura começou no início do processo.
     *
     * Teto de [FIRST_FRAME_TIMEOUT_MS] (parecer do Esquadro): se o DataStore travar, o app abre com
     * a aparência padrão e troca quando o valor chegar, em vez de ficar parado na janela inicial.
     */
    private fun holdFirstFrameUntil(isReady: () -> Boolean) {
        val content = findViewById<View>(android.R.id.content)
        val hold = FirstFrameHold(isReady, now = SystemClock::uptimeMillis, timeoutMs = FIRST_FRAME_TIMEOUT_MS)
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (!hold.canDraw()) return false
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        launchRequest = AppLinks.parse(intent)
    }

    override fun onStart() {
        super.onStart()
        // Voltou ao primeiro plano: os três catch-ups (ADR-0003): tarefas, avisos de bloco e resumo.
        // Cada um entrega o que venceu e reagenda; quem chegar depois acha a janela vazia.
        lifecycleScope.launch(Dispatchers.IO) {
            val app = application as KalendrysApp
            app.reminderCatchUp.run()
            app.routineRepository.catchUp()
            app.summaryCatchUp.run()
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        const val FIRST_FRAME_TIMEOUT_MS = 1_000L
    }
}
