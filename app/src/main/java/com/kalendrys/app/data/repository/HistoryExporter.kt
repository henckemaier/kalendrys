package com.kalendrys.app.data.repository

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.domain.HistoryCsv
import java.io.IOException
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Grava o histórico de conclusões (tarefas e rotinas) no arquivo que o usuário escolheu. */
class HistoryExporter(
    private val database: AppDatabase,
    private val contentResolver: ContentResolver,
) {

    /** Quantas conclusões há para exportar (0 = nada, nem vale abrir o seletor de arquivo). */
    suspend fun count(): Int =
        database.completionLogDao().count() + database.routineCompletionDao().count()

    /** Devolve quantas linhas (conclusões) foram gravadas; lança [IOException] se o arquivo não abrir. */
    suspend fun exportTo(uri: Uri): Int {
        // Uma transação só: tarefas, rotinas e conclusões do mesmo instante, mesmo com o app gravando.
        val rows = database.withTransaction {
            HistoryCsv.rows(
                tasks = database.taskDao().all(),
                taskLogs = database.completionLogDao().all(),
                routines = database.routineDao().all(),
                routineLogs = database.routineCompletionDao().all(),
                zone = ZoneId.systemDefault(),
            )
        }
        withContext(Dispatchers.IO) {
            // "wt": sobrescreve por inteiro se o usuário escolher um arquivo que já existe.
            val stream = contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Sem acesso a $uri")
            stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(HistoryCsv.BOM)
                HistoryCsv.write(rows, writer)
            }
        }
        return rows.size
    }
}
