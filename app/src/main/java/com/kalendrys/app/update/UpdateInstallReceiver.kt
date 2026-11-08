package com.kalendrys.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/**
 * Recebe o status da sessão do [PackageInstaller] (callback do commit).
 *
 * Em [PackageInstaller.STATUS_PENDING_USER_ACTION] — que chega com o processo ainda vivo, antes de
 * qualquer troca do APK — dispara a tela de confirmação do sistema. Os estados terminais (sucesso/
 * falha) não precisam de limpeza: os bytes foram transmitidos direto para a sessão, sem APK solto
 * no armazenamento, então não há arquivo órfão para apagar.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(confirm)
        }
    }

    companion object {
        const val ACTION = "com.kalendrys.app.update.INSTALL_STATUS"
    }
}
