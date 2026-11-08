package com.kalendrys.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Desfecho de [UpdateInstaller.downloadAndInstall]. */
sealed interface InstallOutcome {
    /** APK entregue ao instalador do sistema; o Android pede a confirmação final. */
    data object HandedToSystem : InstallOutcome
    /** Falta o consentimento "instalar apps desconhecidos" (Android 8+). */
    data object NeedsPermission : InstallOutcome
    data object Failed : InstallOutcome
}

/**
 * Baixa e instala o APK da atualização.
 *
 * Abordagem: **PackageInstaller (Session API)**, não ACTION_VIEW + FileProvider. Motivos:
 * - os bytes do download vão direto para a sessão de instalação, então NÃO sobra APK órfão no
 *   armazenamento do app (requisito de robustez);
 * - não precisa de FileProvider nem de conceder permissão de URI de conteúdo;
 * - caminho moderno e não depreciado (ACTION_INSTALL_PACKAGE está depreciado), uniforme no
 *   minSdk 26+, com a confirmação final sempre a cargo do sistema;
 * - dá status explícito via [UpdateInstallReceiver] (ex.: pedir a tela de confirmação).
 *
 * Fluxo de permissão: no Android 8+ é preciso REQUEST_INSTALL_PACKAGES (manifest) e o consentimento
 * "instalar apps desconhecidos" ([canInstall]); sem ele, [requestInstallPermission] leva o usuário à
 * tela do sistema. A instalação nunca começa sozinha — só a partir do botão em Configurações.
 *
 * ESCOPO (assinatura): atualizar in-place via PackageInstaller exige que o APK novo tenha a MESMA
 * chave do app já instalado. Ou seja, o atualizador funciona para quem instalou o próprio APK do
 * GitHub (assinado com a chave "upload"). Uma cópia instalada pela Play Store (Play App Signing,
 * chave diferente) ou um build de debug local NÃO atualizam in-place: o sistema recusa com
 * STATUS_FAILURE por divergência de assinatura — sem crash, é o comportamento esperado.
 */
class UpdateInstaller(private val context: Context) {

    /** Android 8+ exige consentimento por app para instalar de fontes desconhecidas. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Abre a tela do sistema onde o usuário autoriza este app a instalar pacotes. */
    fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    suspend fun downloadAndInstall(apkUrl: String, onProgress: (Int) -> Unit): InstallOutcome =
        withContext(Dispatchers.IO) {
            if (!canInstall()) return@withContext InstallOutcome.NeedsPermission
            // Caminho de instalação: só aceita https. Blinda o cast para HttpsURLConnection (uma URL
            // não-https lançaria ClassCastException, que não é IOException e quebraria o coroutine).
            if (!apkUrl.startsWith("https://", ignoreCase = true)) return@withContext InstallOutcome.Failed
            try {
                streamApkIntoSession(apkUrl, onProgress)
                InstallOutcome.HandedToSystem
            } catch (e: CancellationException) {
                throw e // nunca engolir cancelamento de coroutine
            } catch (e: Exception) {
                // Reforço: IOException (rede/arquivo), mas também qualquer falha inesperada do
                // PackageInstaller vira Failed em vez de derrubar o app.
                InstallOutcome.Failed
            }
        }

    /** Baixa o .apk transmitindo os bytes direto para a sessão e faz o commit (sem arquivo solto). */
    private fun streamApkIntoSession(apkUrl: String, onProgress: (Int) -> Unit) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        var committed = false
        try {
            val connection = (URL(apkUrl).openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = true // GitHub redireciona o asset para o storage (mesmo https)
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", UpdateChecker.USER_AGENT)
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP ${connection.responseCode}")
                }
                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    session.openWrite(SESSION_NAME, 0, if (total > 0) total else -1).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) onProgress(((written * 100) / total).toInt().coerceIn(0, 100))
                        }
                        session.fsync(output)
                    }
                }
            } finally {
                connection.disconnect()
            }
            session.commit(statusIntentSender(sessionId))
            committed = true
        } finally {
            if (!committed) session.abandon()
            session.close()
        }
    }

    private fun statusIntentSender(sessionId: Int): IntentSender {
        val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(UpdateInstallReceiver.ACTION)
        // FLAG_MUTABLE (S+): o sistema preenche o status/EXTRA_INTENT no PendingIntent de callback.
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
    }

    private companion object {
        const val TIMEOUT_MS = 30_000
        const val SESSION_NAME = "kalendrys-update"
    }
}
