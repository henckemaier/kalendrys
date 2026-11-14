package com.kalendrys.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

const val PACKAGE_NAME = "com.kalendrys.app"

private const val TIMEOUT_MS = 5_000L
private val SAMPLE_TASKS = listOf("Perfil: revisar e-mails", "Perfil: reunião semanal", "Perfil: pagar contas")
private const val SAMPLE_ROUTINE = "Perfil: treino"
private const val SAMPLE_LIST = "Perfil: mercado"

/** Espera a tela de tarefas: lista ou estado vazio. */
fun MacrobenchmarkScope.waitForTasksScreen() {
    device.wait(Until.hasObject(By.text("Tarefas")), TIMEOUT_MS)
}

/** Garante algumas tarefas para que a lista, o detalhe e o calendário tenham conteúdo. */
fun MacrobenchmarkScope.ensureSampleTasks() {
    SAMPLE_TASKS.forEach { title ->
        if (device.hasObject(By.text(title))) return@forEach
        device.findObject(By.desc("Nova tarefa"))?.click() ?: return
        device.wait(Until.hasObject(By.clazz("android.widget.EditText")), TIMEOUT_MS)
        device.findObject(By.clazz("android.widget.EditText"))?.text = title
        device.findObject(By.text("Salvar"))?.click()
        device.wait(Until.hasObject(By.text(title)), TIMEOUT_MS)
    }
}

/** Rola a lista, abre o detalhe de uma tarefa e volta. */
fun MacrobenchmarkScope.browseTasks() {
    device.findObject(By.scrollable(true))?.let { list ->
        list.setGestureMargin(device.displayWidth / 5)
        list.fling(Direction.DOWN)
        device.waitForIdle()
        list.fling(Direction.UP)
        device.waitForIdle()
    }
    device.findObject(By.text(SAMPLE_TASKS.first()))?.click()
    device.wait(Until.hasObject(By.text("Detalhes da tarefa")), TIMEOUT_MS)
    device.pressBack()
    waitForTasksScreen()
}

/** Abre o calendário, troca de mês para frente e para trás e volta às tarefas. */
fun MacrobenchmarkScope.browseCalendar() {
    device.findObject(By.text("Calendário"))?.click()
    device.wait(Until.hasObject(By.desc("Próximo mês")), TIMEOUT_MS)
    repeat(2) {
        device.findObject(By.desc("Próximo mês"))?.click()
        device.waitForIdle()
    }
    repeat(2) {
        device.findObject(By.desc("Mês anterior"))?.click()
        device.waitForIdle()
    }
    device.findObject(By.text("Tarefas"))?.click()
    waitForTasksScreen()
}

/**
 * Abre a aba de rotinas, garante um bloco (criado pelo editor, para perfilar também a linha do
 * tempo e o editor), troca de semana pela faixa e volta às tarefas.
 */
fun MacrobenchmarkScope.browseRoutines() {
    device.findObject(By.text("Rotinas"))?.click()
    device.wait(Until.hasObject(By.desc("Nova rotina")), TIMEOUT_MS)
    if (!device.hasObject(By.text(SAMPLE_ROUTINE))) {
        device.findObject(By.desc("Nova rotina"))?.click()
        device.wait(Until.hasObject(By.clazz("android.widget.EditText")), TIMEOUT_MS)
        device.findObject(By.clazz("android.widget.EditText"))?.text = SAMPLE_ROUTINE
        device.findObject(By.text("Salvar"))?.click()
        device.wait(Until.hasObject(By.text(SAMPLE_ROUTINE)), TIMEOUT_MS)
    }
    device.findObject(By.text(SAMPLE_ROUTINE))?.click()
    device.wait(Until.hasObject(By.text("Editar rotina")), TIMEOUT_MS)
    device.pressBack()
    device.wait(Until.hasObject(By.desc("Nova rotina")), TIMEOUT_MS)
    // Desliza a faixa da semana (logo abaixo do título) para a próxima semana e de volta.
    val y = device.displayHeight / 4
    device.swipe(device.displayWidth * 4 / 5, y, device.displayWidth / 5, y, 20)
    device.waitForIdle()
    device.swipe(device.displayWidth / 5, y, device.displayWidth * 4 / 5, y, 20)
    device.waitForIdle()
    device.findObject(By.text("Tarefas"))?.click()
    waitForTasksScreen()
}

/**
 * Abre a aba Listas, garante uma lista (criada pela própria aba), abre a tela da lista e volta às
 * tarefas. Perfila a 4ª aba e a tela de uso da lista (v1.5.0).
 */
fun MacrobenchmarkScope.browseLists() {
    device.findObject(By.text("Listas"))?.click()
    device.wait(Until.hasObject(By.desc("Nova lista")), TIMEOUT_MS)
    if (device.hasObject(By.text(SAMPLE_LIST))) {
        device.findObject(By.text(SAMPLE_LIST))?.click()
    } else {
        // Criar pela aba já abre a lista nova.
        device.findObject(By.desc("Nova lista"))?.click()
        device.wait(Until.hasObject(By.clazz("android.widget.EditText")), TIMEOUT_MS)
        device.findObject(By.clazz("android.widget.EditText"))?.text = SAMPLE_LIST
        device.findObject(By.text("Salvar"))?.click()
    }
    device.wait(Until.hasObject(By.desc("Adicionar item")), TIMEOUT_MS)
    device.pressBack()
    device.wait(Until.hasObject(By.desc("Nova lista")), TIMEOUT_MS)
    device.findObject(By.text("Tarefas"))?.click()
    waitForTasksScreen()
}

/** Abre as configurações e volta. */
fun MacrobenchmarkScope.browseSettings() {
    device.findObject(By.desc("Configurações"))?.click()
    device.wait(Until.hasObject(By.text("Resumo diário")), TIMEOUT_MS)
    device.pressBack()
    waitForTasksScreen()
}
