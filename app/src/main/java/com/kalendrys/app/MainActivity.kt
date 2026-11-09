package com.kalendrys.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.kalendrys.app.reminder.MissedReminderRecovery
import com.kalendrys.app.ui.navigation.KalendrysNavHost
import com.kalendrys.app.ui.theme.KalendrysTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Pedido de widget/notificação ainda não atendido pela navegação. */
    private var launchRequest by mutableStateOf<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askNotificationPermission()
        // Ao recriar (ex.: rotação) o intent é o mesmo; o pedido já foi atendido.
        if (savedInstanceState == null) launchRequest = AppLinks.parse(intent)
        setContent {
            KalendrysTheme {
                KalendrysNavHost(
                    launchRequest = launchRequest,
                    onLaunchRequestHandled = { launchRequest = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        launchRequest = AppLinks.parse(intent)
    }

    override fun onStart() {
        super.onStart()
        // Voltou ao primeiro plano: recupera lembretes que o aparelho perdeu (desligado/Doze) numa
        // única notificação agregada e avança o last-seen. Idempotente, então rodar a cada foreground
        // não gera spam nem duplica o que já foi entregue.
        lifecycleScope.launch(Dispatchers.IO) {
            MissedReminderRecovery.run(this@MainActivity)
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
