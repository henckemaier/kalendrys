package com.kalendrys.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

const val PACKAGE_NAME = "com.kalendrys.app"

private const val TIMEOUT_MS = 5_000L
private val SAMPLE_TASKS = listOf("Perfil: revisar e-mails", "Perfil: reunião semanal", "Perfil: pagar contas")
private const val SAMPLE_ROUTINE = "Perfil: treino"
private const val SAMPLE_LIST = "Perfil: mercado"

/** Limite de rolagens por busca: cobre listas longas sem prender a jornada numa tela inesperada. */
private const val MAX_SCROLLS = 40

/** Abaixo desta fração da altura o item pode estar sob o botão flutuante; rola um pouco mais. */
private const val SAFE_BOTTOM_FRACTION = 0.7f

/** Espera a tela de tarefas: lista ou estado vazio. */
fun MacrobenchmarkScope.waitForTasksScreen() {
    device.wait(Until.hasObject(By.text("Tarefas")), TIMEOUT_MS)
}

/**
 * A lista principal da tela: a área rolável mais alta. As telas também têm faixas roláveis na
 * horizontal (filtros de categoria das rotinas), que não servem para procurar itens.
 */
private fun MacrobenchmarkScope.mainList(): UiObject2? =
    device.findObjects(By.scrollable(true))
        .maxByOrNull { runCatching { it.visibleBounds.height() }.getOrDefault(0) }
        ?.also { it.setGestureMargin(device.displayWidth / 5) }

/** Rola a lista principal uma vez; true se ela ainda pode rolar nessa direção. */
private fun MacrobenchmarkScope.scrollMainList(direction: Direction, percent: Float): Boolean {
    val list = mainList() ?: return false
    val canScroll = try {
        list.scroll(direction, percent)
    } catch (_: StaleObjectException) {
        true // a lista recompôs no meio do gesto; a próxima volta a acha de novo
    }
    device.waitForIdle()
    return canScroll
}

/** Volta a lista principal ao topo (a busca anterior ou o estado salvo a deixa no meio). */
private fun MacrobenchmarkScope.scrollToTop() {
    repeat(MAX_SCROLLS) {
        if (!scrollMainList(Direction.UP, 1f)) return
    }
}

/**
 * Procura um item pelo texto rolando a lista principal desde o topo. Com os dados de quem já usa
 * o app, o item de exemplo fica fora da tela; sem rolar, a jornada o criaria de novo a cada vez.
 * Devolve null se a lista acabou sem achar (ou a tela não tem lista).
 */
private fun MacrobenchmarkScope.findInList(text: String): UiObject2? {
    val selector = By.text(text)
    scrollToTop()
    repeat(MAX_SCROLLS) {
        device.findObject(selector)?.let { return clearOfFab(it, selector) }
        if (!scrollMainList(Direction.DOWN, 0.8f)) {
            return device.findObject(selector)?.let { clearOfFab(it, selector) }
        }
    }
    return device.findObject(selector)
}

/** Se o item achado está no rodapé (sob o botão flutuante), rola mais um pouco e o procura de novo. */
private fun MacrobenchmarkScope.clearOfFab(found: UiObject2, selector: BySelector): UiObject2 {
    val bottom = runCatching { found.visibleBounds.bottom }.getOrDefault(0)
    if (bottom < device.displayHeight * SAFE_BOTTOM_FRACTION) return found
    scrollMainList(Direction.DOWN, 0.3f)
    return device.findObject(selector) ?: found
}

/**
 * Clica no item achado rolando a lista, buscando-o de novo se a lista recompor entre achar e
 * clicar (StaleObjectException, que derrubava a geração com muitas rotinas). False se não achou.
 */
private fun MacrobenchmarkScope.clickInList(text: String): Boolean {
    repeat(3) {
        val item = findInList(text) ?: return false
        try {
            item.click()
            return true
        } catch (_: StaleObjectException) {
            device.waitForIdle()
        }
    }
    return false
}

/** Preenche o primeiro campo de texto do editor aberto e salva. */
private fun MacrobenchmarkScope.typeAndSave(text: String) {
    device.wait(Until.hasObject(By.clazz("android.widget.EditText")), TIMEOUT_MS)
    device.findObject(By.clazz("android.widget.EditText"))?.text = text
    device.findObject(By.text("Salvar"))?.click()
    // Espera o editor fechar: o botão de criar continua visível atrás da folha da tarefa nova.
    device.wait(Until.gone(By.text("Salvar")), TIMEOUT_MS)
}

/**
 * Garante algumas tarefas para que a lista, o detalhe e o calendário tenham conteúdo. Só cria a
 * que não achar rolando a lista: rodar de novo, ou com os dados de quem usa o app, não duplica.
 */
fun MacrobenchmarkScope.ensureSampleTasks() {
    SAMPLE_TASKS.forEach { title ->
        if (findInList(title) != null) return@forEach
        device.findObject(By.desc("Nova tarefa"))?.click() ?: return
        typeAndSave(title)
        device.wait(Until.hasObject(By.desc("Nova tarefa")), TIMEOUT_MS)
    }
}

/** Rola a lista, abre o detalhe de uma tarefa e volta. */
fun MacrobenchmarkScope.browseTasks() {
    mainList()?.let { list ->
        list.fling(Direction.DOWN)
        device.waitForIdle()
        list.fling(Direction.UP)
        device.waitForIdle()
    }
    if (clickInList(SAMPLE_TASKS.first())) {
        device.wait(Until.hasObject(By.text("Detalhes da tarefa")), TIMEOUT_MS)
        device.pressBack()
    }
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
 * tempo e o editor), troca de semana pela faixa e volta às tarefas. O bloco vale todos os dias,
 * então aparece no dia mostrado: é procurado rolando a linha do tempo e só criado se não existe.
 */
fun MacrobenchmarkScope.browseRoutines() {
    device.findObject(By.text("Rotinas"))?.click()
    device.wait(Until.hasObject(By.desc("Nova rotina")), TIMEOUT_MS)
    if (findInList(SAMPLE_ROUTINE) == null) {
        device.findObject(By.desc("Nova rotina"))?.click()
        typeAndSave(SAMPLE_ROUTINE)
        device.wait(Until.hasObject(By.desc("Nova rotina")), TIMEOUT_MS)
    }
    if (clickInList(SAMPLE_ROUTINE)) {
        device.wait(Until.hasObject(By.text("Editar rotina")), TIMEOUT_MS)
        device.pressBack()
        device.wait(Until.hasObject(By.desc("Nova rotina")), TIMEOUT_MS)
    }
    // No topo a barra volta a se expandir e a faixa da semana fica onde o gesto abaixo espera.
    scrollToTop()
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
 * Abre a aba Listas, garante uma lista (criada pela própria aba, só se não achar rolando), abre a
 * tela da lista e volta às tarefas. Perfila a 4ª aba e a tela de uso da lista (v1.5.0).
 */
fun MacrobenchmarkScope.browseLists() {
    device.findObject(By.text("Listas"))?.click()
    device.wait(Until.hasObject(By.desc("Nova lista")), TIMEOUT_MS)
    if (!clickInList(SAMPLE_LIST)) {
        // Criar pela aba já abre a lista nova.
        device.findObject(By.desc("Nova lista"))?.click()
        typeAndSave(SAMPLE_LIST)
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
