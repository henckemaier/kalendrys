package com.kalendrys.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regras de código da v1.7.1 conferidas no fonte de `ui/` (o Gradle roda os testes unitários na
 * pasta do módulo `app`):
 * - E4: nenhum aviso com ação (Desfazer e afins) usa a duração curta. Todo `showSnackbar` com
 *   `actionLabel` passa `SnackbarDuration.Long` (o Desfazer vai pelo `showUndo`, que já é longo).
 * - E1: o Calendário não depende da aba Tarefas, a não ser pelo painel compartilhado (`NewTaskSheet`).
 *   O rascunho e a lista escolhida moram em `domain` e no `NewTaskDraftStore`.
 */
class UiSourceRulesTest {

    private val uiRoot = File("src/main/java/com/kalendrys/app/ui")

    private fun kotlinFiles(dir: File): List<File> {
        assertTrue("pasta de fonte não encontrada: ${dir.absolutePath}", dir.isDirectory)
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** Trecho de cada chamada `showSnackbar(...)`, do nome até o parêntese que a fecha. */
    private fun snackbarCalls(source: String): List<String> {
        val calls = mutableListOf<String>()
        var from = 0
        while (true) {
            val start = source.indexOf("showSnackbar(", from).takeIf { it >= 0 } ?: break
            var depth = 0
            var end = start + "showSnackbar".length
            while (end < source.length) {
                when (source[end]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) break
                }
                end++
            }
            calls += source.substring(start, minOf(end + 1, source.length))
            from = end
        }
        return calls
    }

    @Test
    fun `nenhum aviso com acao em ui usa duracao curta`() {
        val files = kotlinFiles(uiRoot)
        val short = files.filter { "SnackbarDuration.Short" in it.readText() }.map { it.name }
        assertEquals("SnackbarDuration.Short em ui/", emptyList<String>(), short)

        val offenders = files.flatMap { file ->
            snackbarCalls(file.readText())
                .filter { "actionLabel" in it && "SnackbarDuration.Long" !in it }
                .map { "${file.name}: ${it.lineSequence().first().trim()}" }
        }
        assertEquals("showSnackbar com ação sem duração longa", emptyList<String>(), offenders)
    }

    @Test
    fun `ui calendar so importa o painel de nova tarefa de ui tasks`() {
        val imports = kotlinFiles(File(uiRoot, "calendar")).flatMap { file ->
            file.readLines()
                .map { it.trim() }
                .filter { it.startsWith("import com.kalendrys.app.ui.tasks.") }
                .map { "${file.name}: ${it.removePrefix("import ")}" }
        }
        val others = imports.filterNot { it.endsWith(": com.kalendrys.app.ui.tasks.NewTaskSheet") }
        assertEquals("imports de ui/tasks em ui/calendar além do painel", emptyList<String>(), others)
    }
}
