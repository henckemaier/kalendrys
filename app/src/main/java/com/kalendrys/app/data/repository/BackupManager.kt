package com.kalendrys.app.data.repository

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import com.kalendrys.app.BuildConfig
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCompletion
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.settings.AppSettings
import com.kalendrys.app.data.settings.SettingsRepository
import com.kalendrys.app.domain.ReminderRepeat
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Backup completo do banco (tarefas, conclusões, rotinas, conclusões de rotina) e das
 * configurações, para troca de aparelho. Um único arquivo JSON com um campo de versão de schema
 * do próprio backup ([SCHEMA_VERSION]), para poder evoluir o formato no futuro.
 *
 * Política de restauro: **SUBSTITUI** tudo (limpa as tabelas e reinsere), pensado para restaurar
 * num aparelho novo/limpo. É destrutivo, então a UI pede confirmação explícita antes. Os ids
 * originais são preservados, para manter a ligação tarefa ↔ [CompletionLog] e rotina ↔
 * [RoutineCompletion]. Usa o seletor de arquivos do sistema (SAF) via [ContentResolver], sem
 * permissão de armazenamento, como o [HistoryExporter].
 *
 * @param afterRestore roda depois de restaurar os dados: reagenda alarmes e atualiza os widgets
 *   (injetado pelo KalendrysApp para não acoplar este repositório aos schedulers; vazio nos testes).
 */
class BackupManager(
    private val database: AppDatabase,
    private val contentResolver: ContentResolver,
    private val settings: SettingsRepository,
    private val afterRestore: suspend () -> Unit = {},
) {

    /** Quantos itens cada tabela tinha no backup exportado/restaurado. */
    data class Stats(
        val tasks: Int,
        val completionLogs: Int,
        val routines: Int,
        val routineCompletions: Int,
    ) {
        val total: Int get() = tasks + completionLogs + routines + routineCompletions
    }

    /** Grava o backup no arquivo escolhido; devolve o que foi exportado. Lança [IOException] se não abrir. */
    suspend fun exportTo(uri: Uri): Stats {
        val (json, stats) = serialize()
        withContext(Dispatchers.IO) {
            // "wt": sobrescreve por inteiro se o arquivo já existir.
            val stream = contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Sem acesso a $uri")
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
        }
        return stats
    }

    /**
     * Restaura a partir do arquivo escolhido: substitui TODOS os dados atuais pelos do backup e,
     * no fim, reagenda alarmes e atualiza widgets ([afterRestore]). Lança [IOException] (arquivo
     * ilegível, formato inválido ou versão incompatível).
     */
    suspend fun importFrom(uri: Uri): Stats {
        val text = withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw IOException("Sem acesso a $uri")
        }
        return restore(text)
    }

    /** Lê o banco e as configurações num JSON (visível para o teste de round-trip). */
    internal suspend fun serialize(): Pair<String, Stats> {
        val current: AppSettings = settings.settings.first()
        lateinit var tasks: List<Task>
        lateinit var logs: List<CompletionLog>
        lateinit var routines: List<Routine>
        lateinit var routineLogs: List<RoutineCompletion>
        // Snapshot consistente mesmo com o app gravando no meio da exportação.
        database.withTransaction {
            tasks = database.taskDao().all()
            logs = database.completionLogDao().all()
            routines = database.routineDao().all()
            routineLogs = database.routineCompletionDao().all()
        }

        val root = JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("databaseVersion", DATABASE_VERSION)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("exportedAt", System.currentTimeMillis())
            put("tasks", tasks.toJsonArray(Task::toJson))
            put("completionLogs", logs.toJsonArray(CompletionLog::toJson))
            put("routines", routines.toJsonArray(Routine::toJson))
            put("routineCompletions", routineLogs.toJsonArray(RoutineCompletion::toJson))
            put("settings", current.toJson())
        }
        val stats = Stats(tasks.size, logs.size, routines.size, routineLogs.size)
        return root.toString(2) to stats
    }

    /** Lê o JSON e substitui o banco e as configurações (visível para o teste de round-trip). */
    internal suspend fun restore(text: String): Stats {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw IOException("Arquivo de backup inválido", e)
        }
        val version = root.optInt("schemaVersion", 0)
        if (version !in 1..SCHEMA_VERSION) {
            throw IOException("Backup incompatível (schemaVersion=$version)")
        }

        val tasks = root.getJSONArray("tasks").map(JSONObject::toTask)
        val logs = root.getJSONArray("completionLogs").map(JSONObject::toCompletionLog)
        val routines = root.getJSONArray("routines").map(JSONObject::toRoutine)
        val routineLogs = root.getJSONArray("routineCompletions").map(JSONObject::toRoutineCompletion)

        // Limpa e reinsere numa transação só: ou o restauro vale inteiro, ou não vale nada.
        database.withTransaction {
            database.taskDao().clear()
            database.completionLogDao().clear()
            database.routineDao().clear()
            database.routineCompletionDao().clear()
            database.taskDao().insertAll(tasks)
            database.completionLogDao().insertAll(logs)
            database.routineDao().insertAll(routines)
            database.routineCompletionDao().insertAll(routineLogs)
        }

        // Configurações ficam no DataStore (fora da transação do Room).
        root.optJSONObject("settings")?.let { s ->
            settings.setSummaryEnabled(s.optBoolean("summaryEnabled", true))
            settings.setSummaryTime(AppSettings.timeFromMinuteOfDay(s.optInt("summaryMinuteOfDay", DEFAULT_SUMMARY_MINUTE)))
        }

        afterRestore()
        return Stats(tasks.size, logs.size, routines.size, routineLogs.size)
    }

    companion object {
        /** Versão do formato do backup (não do schema do Room). Subir ao mudar a estrutura do JSON. */
        const val SCHEMA_VERSION = 1

        /** Metadado: versão do schema do Room no momento do export (ver AppDatabase.version). */
        private const val DATABASE_VERSION = 5

        private val DEFAULT_SUMMARY_MINUTE = AppSettings.minuteOfDay(AppSettings.DEFAULT_SUMMARY_TIME)
    }
}

// --- Serialização por entidade ---------------------------------------------------------------

private fun Task.toJson() = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("notes", notes)
    put("createdAt", createdAt)
    putNullableLong("dueDate", dueDate)
    putNullableLong("reminderAt", reminderAt)
    put("repeat", repeat.name)
    put("location", location)
}

private fun JSONObject.toTask() = Task(
    id = getLong("id"),
    title = getString("title"),
    notes = optString("notes"),
    createdAt = getLong("createdAt"),
    dueDate = nullableLong("dueDate"),
    reminderAt = nullableLong("reminderAt"),
    // Valor desconhecido (enum mudou entre versões) cai no padrão em vez de derrubar o restauro.
    repeat = runCatching { ReminderRepeat.valueOf(getString("repeat")) }.getOrDefault(ReminderRepeat.NONE),
    location = optString("location"),
)

private fun CompletionLog.toJson() = JSONObject().apply {
    put("id", id)
    put("taskId", taskId)
    put("completedAt", completedAt)
}

private fun JSONObject.toCompletionLog() = CompletionLog(
    id = getLong("id"),
    taskId = getLong("taskId"),
    completedAt = getLong("completedAt"),
)

private fun Routine.toJson() = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("notes", notes)
    put("icon", icon)
    put("color", color)
    put("startMinute", startMinute)
    put("durationMinutes", durationMinutes)
    put("daysOfWeek", daysOfWeek)
    put("remind", remind)
    put("createdAt", createdAt)
    putNullableLong("archivedAt", archivedAt)
}

private fun JSONObject.toRoutine() = Routine(
    id = getLong("id"),
    title = getString("title"),
    notes = optString("notes"),
    icon = optString("icon"),
    color = optString("color"),
    startMinute = getInt("startMinute"),
    durationMinutes = getInt("durationMinutes"),
    daysOfWeek = getInt("daysOfWeek"),
    remind = getBoolean("remind"),
    createdAt = getLong("createdAt"),
    archivedAt = nullableLong("archivedAt"),
)

private fun RoutineCompletion.toJson() = JSONObject().apply {
    put("id", id)
    put("routineId", routineId)
    put("epochDay", epochDay)
    put("completedAt", completedAt)
}

private fun JSONObject.toRoutineCompletion() = RoutineCompletion(
    id = getLong("id"),
    routineId = getLong("routineId"),
    epochDay = getLong("epochDay"),
    completedAt = getLong("completedAt"),
)

private fun AppSettings.toJson() = JSONObject().apply {
    put("summaryEnabled", summaryEnabled)
    put("summaryMinuteOfDay", AppSettings.minuteOfDay(summaryTime))
}

// --- Utilitários de JSON ---------------------------------------------------------------------

private fun <T> List<T>.toJsonArray(toJson: (T) -> JSONObject): JSONArray =
    JSONArray().also { array -> forEach { array.put(toJson(it)) } }

private fun <T> JSONArray.map(fromJson: JSONObject.() -> T): List<T> =
    (0 until length()).map { getJSONObject(it).fromJson() }

private fun JSONObject.putNullableLong(key: String, value: Long?) {
    if (value == null) put(key, JSONObject.NULL) else put(key, value)
}

private fun JSONObject.nullableLong(key: String): Long? = if (isNull(key)) null else getLong(key)
