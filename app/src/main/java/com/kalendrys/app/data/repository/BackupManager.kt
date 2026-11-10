package com.kalendrys.app.data.repository

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import com.kalendrys.app.BuildConfig
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.ExerciseItem
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCategory
import com.kalendrys.app.data.local.entity.RoutineCompletion
import com.kalendrys.app.data.local.entity.RoutineOverride
import com.kalendrys.app.data.local.entity.ShoppingItem
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.settings.AppSettings
import com.kalendrys.app.data.settings.SettingsRepository
import com.kalendrys.app.domain.RecurrenceUnit
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.RoutineCategoryKind
import com.kalendrys.app.domain.RoutineColor
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
        val routineCategories: Int = 0,
        val routineOverrides: Int = 0,
        val shoppingItems: Int = 0,
        val exerciseItems: Int = 0,
    ) {
        val total: Int
            get() = tasks + completionLogs + routines + routineCompletions +
                routineCategories + routineOverrides + shoppingItems + exerciseItems
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
        lateinit var categories: List<RoutineCategory>
        lateinit var overrides: List<RoutineOverride>
        lateinit var shoppingItems: List<ShoppingItem>
        lateinit var exerciseItems: List<ExerciseItem>
        // Snapshot consistente mesmo com o app gravando no meio da exportação.
        database.withTransaction {
            tasks = database.taskDao().all()
            logs = database.completionLogDao().all()
            routines = database.routineDao().all()
            routineLogs = database.routineCompletionDao().all()
            categories = database.routineCategoryDao().all()
            overrides = database.routineOverrideDao().all()
            shoppingItems = database.shoppingItemDao().all()
            exerciseItems = database.exerciseItemDao().all()
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
            put("routineCategories", categories.toJsonArray(RoutineCategory::toJson))
            put("routineOverrides", overrides.toJsonArray(RoutineOverride::toJson))
            put("shoppingItems", shoppingItems.toJsonArray(ShoppingItem::toJson))
            put("exerciseItems", exerciseItems.toJsonArray(ExerciseItem::toJson))
            put("settings", current.toJson())
        }
        val stats = Stats(
            tasks = tasks.size,
            completionLogs = logs.size,
            routines = routines.size,
            routineCompletions = routineLogs.size,
            routineCategories = categories.size,
            routineOverrides = overrides.size,
            shoppingItems = shoppingItems.size,
            exerciseItems = exerciseItems.size,
        )
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
        // Retrocompatível: um backup v2 (schemaVersion < 3) não tem estas arrays -> vazias.
        val categories = root.optJSONArray("routineCategories").orEmpty().map(JSONObject::toRoutineCategory)
        val overrides = root.optJSONArray("routineOverrides").orEmpty().map(JSONObject::toRoutineOverride)
        // Retrocompatível: um backup v3 (schemaVersion < 4) não tem esta array -> lista vazia.
        val shoppingItems = root.optJSONArray("shoppingItems").orEmpty().map(JSONObject::toShoppingItem)
        // Retrocompatível: um backup v4 (schemaVersion < 5) não tem esta array -> lista vazia.
        val exerciseItems = root.optJSONArray("exerciseItems").orEmpty().map(JSONObject::toExerciseItem)

        // Limpa e reinsere numa transação só: ou o restauro vale inteiro, ou não vale nada.
        database.withTransaction {
            database.taskDao().clear()
            database.completionLogDao().clear()
            database.routineDao().clear()
            database.routineCompletionDao().clear()
            database.routineCategoryDao().clear()
            database.routineOverrideDao().clear()
            database.shoppingItemDao().clear()
            database.exerciseItemDao().clear()
            database.taskDao().insertAll(tasks)
            database.completionLogDao().insertAll(logs)
            database.routineDao().insertAll(routines)
            database.routineCompletionDao().insertAll(routineLogs)
            database.routineCategoryDao().insertAll(categories)
            database.routineOverrideDao().insertAll(overrides)
            database.shoppingItemDao().insertAll(shoppingItems)
            database.exerciseItemDao().insertAll(exerciseItems)
        }

        // Configurações ficam no DataStore (fora da transação do Room).
        root.optJSONObject("settings")?.let { s ->
            settings.setSummaryEnabled(s.optBoolean("summaryEnabled", true))
            settings.setSummaryTime(AppSettings.timeFromMinuteOfDay(s.optInt("summaryMinuteOfDay", DEFAULT_SUMMARY_MINUTE)))
        }

        afterRestore()
        return Stats(
            tasks = tasks.size,
            completionLogs = logs.size,
            routines = routines.size,
            routineCompletions = routineLogs.size,
            routineCategories = categories.size,
            routineOverrides = overrides.size,
            shoppingItems = shoppingItems.size,
            exerciseItems = exerciseItems.size,
        )
    }

    companion object {
        /**
         * Versão do formato do backup (não do schema do Room). Subir ao mudar a estrutura do JSON.
         * v2: campos da recorrência custom na tarefa (intervalEvery/intervalUnit/byWeekdays).
         * v3: colunas novas de rotina (categoryId/pausedAt/weekInterval/startEpochDay/endEpochDay) e
         * as arrays routineCategories/routineOverrides. A leitura é retrocompatível — um backup v1/v2
         * (sem esses campos/arrays) restaura com os defaults e as novas tabelas vazias.
         * v4: array shoppingItems (itens da lista de compras). A leitura segue retrocompatível — um
         * backup v3 (sem a array) restaura com a lista de compras vazia.
         * v5: array exerciseItems (exercícios da rotina). A leitura segue retrocompatível — um
         * backup v4 (sem a array) restaura com a lista de exercícios vazia.
         */
        const val SCHEMA_VERSION = 5

        /** Metadado: versão do schema do Room no momento do export (ver AppDatabase.version). */
        private const val DATABASE_VERSION = 9

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
    put("intervalEvery", intervalEvery)
    intervalUnit?.let { put("intervalUnit", it.name) }
    put("byWeekdays", byWeekdays)
    put("location", location)
}

private fun JSONObject.toTask(): Task {
    // Valor desconhecido (enum mudou entre versões) cai no padrão em vez de derrubar o restauro.
    val rawRepeat = runCatching { ReminderRepeat.valueOf(getString("repeat")) }.getOrDefault(ReminderRepeat.NONE)
    // Retrocompatível: backup v1 não tem estes campos -> defaults (intervalo 1, sem unidade, sem dias).
    val unit = optString("intervalUnit").takeIf { it.isNotEmpty() }
        ?.let { runCatching { RecurrenceUnit.valueOf(it) }.getOrNull() }
    val byWeekdays = optInt("byWeekdays", 0)
    // CUSTOM sem configuração utilizável (backup adulterado/corrompido, ou unidade desconhecida
    // que virou null) é normalizado para NONE: não restaura uma tarefa silenciosamente inerte, que
    // nunca ocorre nem lembra. Mesmo espírito dos outros fallbacks.
    val repeat = if (rawRepeat == ReminderRepeat.CUSTOM && byWeekdays == 0 && unit == null) {
        ReminderRepeat.NONE
    } else {
        rawRepeat
    }
    return Task(
        id = getLong("id"),
        title = getString("title"),
        notes = optString("notes"),
        createdAt = getLong("createdAt"),
        dueDate = nullableLong("dueDate"),
        reminderAt = nullableLong("reminderAt"),
        repeat = repeat,
        intervalEvery = optInt("intervalEvery", 1),
        intervalUnit = unit,
        byWeekdays = byWeekdays,
        location = optString("location"),
    )
}

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
    putNullableLong("categoryId", categoryId)
    putNullableLong("pausedAt", pausedAt)
    put("weekInterval", weekInterval)
    putNullableLong("startEpochDay", startEpochDay)
    putNullableLong("endEpochDay", endEpochDay)
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
    // Retrocompatível: backup v2 não tem estes campos -> defaults (sem categoria/pausa/vigência,
    // intervalo semanal 1).
    categoryId = nullableLong("categoryId"),
    pausedAt = nullableLong("pausedAt"),
    weekInterval = optInt("weekInterval", 1),
    startEpochDay = nullableLong("startEpochDay"),
    endEpochDay = nullableLong("endEpochDay"),
)

private fun RoutineCategory.toJson() = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("color", color)
    put("kind", kind)
}

private fun JSONObject.toRoutineCategory() = RoutineCategory(
    id = getLong("id"),
    name = getString("name"),
    // Ausente/vazio cai no padrão, alinhado com o fallback do kind (defensivo; backup v3 real sempre traz).
    color = optString("color").ifEmpty { RoutineColor.DEFAULT.name },
    // Valor desconhecido (enum mudou entre versões) é normalizado para o padrão SIMPLE na leitura.
    kind = RoutineCategoryKind.fromKey(optString("kind")).name,
)

private fun RoutineOverride.toJson() = JSONObject().apply {
    put("id", id)
    put("routineId", routineId)
    put("epochDay", epochDay)
    put("type", type)
    putNullableInt("newStartMinute", newStartMinute)
    putNullableLong("newEpochDay", newEpochDay)
    put("createdAt", createdAt)
}

private fun JSONObject.toRoutineOverride() = RoutineOverride(
    id = getLong("id"),
    routineId = getLong("routineId"),
    epochDay = getLong("epochDay"),
    type = getString("type"),
    newStartMinute = nullableInt("newStartMinute"),
    newEpochDay = nullableLong("newEpochDay"),
    createdAt = getLong("createdAt"),
)

private fun ShoppingItem.toJson() = JSONObject().apply {
    put("id", id)
    put("routineId", routineId)
    put("name", name)
    put("quantity", quantity)
    putNullableLong("priceCents", priceCents)
    put("picked", picked)
    put("position", position)
    put("createdAt", createdAt)
}

private fun JSONObject.toShoppingItem() = ShoppingItem(
    id = getLong("id"),
    routineId = getLong("routineId"),
    name = getString("name"),
    quantity = optInt("quantity", 1),
    priceCents = nullableLong("priceCents"),
    picked = optBoolean("picked", false),
    position = optInt("position", 0),
    createdAt = getLong("createdAt"),
)

private fun ExerciseItem.toJson() = JSONObject().apply {
    put("id", id)
    put("routineId", routineId)
    put("name", name)
    put("sets", sets)
    put("reps", reps)
    putNullableString("load", load)
    putNullableInt("restSeconds", restSeconds)
    put("position", position)
    put("createdAt", createdAt)
}

private fun JSONObject.toExerciseItem() = ExerciseItem(
    id = getLong("id"),
    routineId = getLong("routineId"),
    name = getString("name"),
    sets = optInt("sets", 0),
    reps = optInt("reps", 0),
    load = nullableString("load"),
    restSeconds = nullableInt("restSeconds"),
    position = optInt("position", 0),
    createdAt = getLong("createdAt"),
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

/** Array ausente (backup de versão anterior) é tratada como vazia. */
private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

private fun JSONObject.putNullableLong(key: String, value: Long?) {
    if (value == null) put(key, JSONObject.NULL) else put(key, value)
}

private fun JSONObject.nullableLong(key: String): Long? = if (isNull(key)) null else getLong(key)

private fun JSONObject.putNullableInt(key: String, value: Int?) {
    if (value == null) put(key, JSONObject.NULL) else put(key, value)
}

private fun JSONObject.nullableInt(key: String): Int? = if (isNull(key)) null else getInt(key)

private fun JSONObject.putNullableString(key: String, value: String?) {
    if (value == null) put(key, JSONObject.NULL) else put(key, value)
}

private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
