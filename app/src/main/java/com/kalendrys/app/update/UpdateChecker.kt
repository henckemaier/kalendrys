package com.kalendrys.app.update

import com.kalendrys.app.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** Resultado de uma checagem manual de atualização. */
sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck
    data class Available(val versionName: String, val apkUrl: String) : UpdateCheck
    data object Failed : UpdateCheck
}

/**
 * Consulta a GitHub Releases API (release mais recente) e decide se há versão nova.
 *
 * Comparação: a convenção é tag = "v" + versionName (SemVer). A API do GitHub NÃO expõe o
 * versionCode do Android, então comparamos pelo versionName (SemVer do tag_name) contra o
 * [BuildConfig.VERSION_NAME] local — é o sinal confiável que a API oferece. Só roda no toque do
 * botão (sem auto-check) e sempre fora da main thread (Dispatchers.IO), respeitando o StrictMode.
 */
class UpdateChecker(
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val releasesUrl: String = LATEST_RELEASE_URL,
) {

    suspend fun check(): UpdateCheck = withContext(Dispatchers.IO) {
        val json = try {
            fetch(releasesUrl)
        } catch (e: IOException) {
            return@withContext UpdateCheck.Failed
        }
        evaluate(json, currentVersionName)
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/henckemaier/kalendrys/releases/latest"
        const val USER_AGENT = "Kalendrys-Updater"
        private const val TIMEOUT_MS = 15_000

        /**
         * Lógica pura (testável): a partir do JSON do release, decide se é mais novo que a versão
         * local e extrai a URL do asset .apk. [UpdateCheck.Failed] se faltar tag SemVer válida ou
         * um asset .apk.
         */
        internal fun evaluate(json: String, currentVersionName: String): UpdateCheck {
            val release = try {
                JSONObject(json)
            } catch (e: JSONException) {
                return UpdateCheck.Failed
            }
            val tag = release.optString("tag_name").ifBlank { return UpdateCheck.Failed }
            val apkUrl = apkAssetUrl(release) ?: return UpdateCheck.Failed
            val remote = Semver.parse(tag) ?: return UpdateCheck.Failed
            val local = Semver.parse(currentVersionName) ?: return UpdateCheck.Failed
            return if (remote > local) {
                UpdateCheck.Available(versionName = tag.trim().removePrefix("v").removePrefix("V"), apkUrl = apkUrl)
            } else {
                UpdateCheck.UpToDate
            }
        }

        /** Primeiro asset cujo download termina em .apk. */
        private fun apkAssetUrl(release: JSONObject): String? {
            val assets = release.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val url = assets.optJSONObject(i)?.optString("browser_download_url").orEmpty()
                if (url.endsWith(".apk", ignoreCase = true)) return url
            }
            return null
        }
    }
}

/**
 * SemVer mínimo para comparar versões como "1.2.3" ou "v1.2". Compara campo a campo
 * numericamente (faltando campos valem 0) e ignora sufixo de pré-release/build ("-rc1", "+meta").
 */
internal data class Semver(val parts: List<Int>) : Comparable<Semver> {
    override fun compareTo(other: Semver): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val a = parts.getOrElse(i) { 0 }
            val b = other.parts.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    companion object {
        fun parse(raw: String): Semver? {
            val cleaned = raw.trim().removePrefix("v").removePrefix("V")
            val core = cleaned.takeWhile { it.isDigit() || it == '.' }.trim('.')
            if (core.isEmpty()) return null
            val parts = core.split(".").map { it.toIntOrNull() ?: return null }
            return Semver(parts)
        }
    }
}
