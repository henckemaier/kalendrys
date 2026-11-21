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

/*
 * Jornadas da v1.8.0 (E7): o app abre na Hoje e a aba Rotinas não existe mais (as rotinas ficam na
 * linha do tempo da Hoje e em "Todas as rotinas"). Cada jornada abre a SUA aba e espera um marcador
 * que só aquela tela tem (o rótulo da aba está sempre na barra e não serve de espera), e só cria o
 * exemplo que não achar rolando a lista: rodar de novo, ou com os dados de quem usa o app, não duplica.
 */

/** Abas e o marcador de cada tela (qualquer um deles basta: com e sem conteúdo). */
private enum class Tab(val label: String, val markers: List<BySelector>) {
    TODAY("Hoje", listOf(By.text("Progresso do dia"), By.text("Nada no seu dia ainda"))),
    TASKS("Tarefas", listOf(By.text("Buscar tarefas"), By.text("Nenhuma tarefa"))),
    CALENDAR("Calendário", listOf(By.desc("Próximo mês"))),
    LISTS("Listas", listOf(By.desc("Nova lista"))),
}

/** Espera qualquer um dos [selectors]; true se algum apareceu. */
private fun MacrobenchmarkScope.waitForAny(selectors: List<BySelector>, timeoutMs: Long = TIMEOUT_MS): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (selectors.any { device.hasObject(it) }) return true
        device.waitForIdle()
        Thread.sleep(100)
    }
    return selectors.any { device.hasObject(it) }
}

/**
 * Toca na aba e espera a tela dela. Numa tela interna (detalhe, editor, lista aberta) a barra de abas
 * some: volta com o Voltar até a barra aparecer. Já na aba, não faz nada.
 *
 * Os marcadores são ITENS da lista (a busca de Tarefas, o progresso da Hoje): com a lista rolada eles
 * saem da tela, e tocar na aba já selecionada não volta ao topo. Por isso, antes de concluir que não
 * está na aba, rola a lista ao topo (NB-E7-A da revisão do Lince).
 */
private fun MacrobenchmarkScope.openTab(tab: Tab) {
    repeat(MAX_TAB_ATTEMPTS) {
        if (waitForAny(tab.markers, timeoutMs = 0)) return
        scrollToTop()
        if (waitForAny(tab.markers, timeoutMs = 0)) return
        val label = device.findObject(By.text(tab.label))
        if (label != null) {
            runCatching { label.click() }
        } else {
            device.pressBack()
        }
        if (waitForAny(tab.markers)) return
    }
}

/** Voltas no máximo até achar a aba (tela interna dentro de tela interna, painel aberto). */
private const val MAX_TAB_ATTEMPTS = 4

/** Espera a Hoje, a tela inicial (v1.8.0). */
fun MacrobenchmarkScope.waitForToday() {
    waitForAny(Tab.TODAY.markers)
}

/**
 * A lista principal da tela: a área rolável mais alta. As telas também têm faixas roláveis na
 * horizontal (a semana da Hoje, filtros), que não servem para procurar itens.
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
        device.findObject(selector)?.let { found -> clearOfFab(found, selector)?.let { return it } }
        if (!scrollMainList(Direction.DOWN, 0.8f)) {
            return device.findObject(selector)?.let { clearOfFab(it, selector) }
        }
    }
    return device.findObject(selector)?.let { clearOfFab(it, selector) }
}

/**
 * Se o item achado está no rodapé (sob o botão flutuante), rola mais um pouco e o procura DE NOVO.
 * Não devolve o objeto de antes da rolagem: ele pode ter saído da tela ou ter sido recomposto
 * (objeto obsoleto, o toque cairia em outro lugar). Null se não o achou depois de rolar.
 */
private fun MacrobenchmarkScope.clearOfFab(found: UiObject2, selector: BySelector): UiObject2? {
    val bottom = runCatching { found.visibleBounds.bottom }.getOrNull() ?: return device.findObject(selector)
    if (bottom < device.displayHeight * SAFE_BOTTOM_FRACTION) return found
    scrollMainList(Direction.DOWN, 0.3f)
    return device.findObject(selector)
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

/**
 * Roda [action] sobre um objeto buscado de novo a cada tentativa: se a tela recompõe entre achar e
 * agir (StaleObjectException), espera assentar e busca outra vez. False se não achou ou não conseguiu.
 */
private fun MacrobenchmarkScope.actOnFresh(selector: BySelector, action: (UiObject2) -> Unit): Boolean {
    repeat(3) {
        val target = device.findObject(selector) ?: return false
        try {
            action(target)
            return true
        } catch (_: StaleObjectException) {
            device.waitForIdle()
        }
    }
    return false
}

/**
 * Preenche o primeiro campo de texto do painel ou editor aberto e salva. O painel de nova tarefa
 * (v1.8.0, O-6) recompõe o campo enquanto entra e traz o campo à vista: o objeto achado podia ficar
 * obsoleto antes de receber o texto (derrubava a geração no emulador). Por isso espera assentar e
 * busca de novo a cada tentativa.
 */
private fun MacrobenchmarkScope.typeAndSave(text: String) {
    val field = By.clazz("android.widget.EditText")
    device.wait(Until.hasObject(field), TIMEOUT_MS)
    device.waitForIdle()
    actOnFresh(field) { it.text = text }
    device.waitForIdle()
    actOnFresh(By.text("Salvar")) { it.click() }
    // Espera o painel ou o editor fechar.
    device.wait(Until.gone(By.text("Salvar")), TIMEOUT_MS)
}

/**
 * Garante algumas tarefas para que a lista, o detalhe, a Hoje e o calendário tenham conteúdo, pelo
 * painel de nova tarefa (hospedado no topo da navegação desde a v1.8.0).
 */
fun MacrobenchmarkScope.ensureSampleTasks() {
    openTab(Tab.TASKS)
    SAMPLE_TASKS.forEach { title ->
        if (findInList(title) != null) return@forEach
        device.findObject(By.desc("Nova tarefa"))?.click() ?: return
        typeAndSave(title)
        openTab(Tab.TASKS)
    }
}

/** Na aba Tarefas: rola a lista, filtra pela busca (que é item da lista) e limpa, abre um detalhe e volta. */
fun MacrobenchmarkScope.browseTasks() {
    openTab(Tab.TASKS)
    mainList()?.let { list ->
        list.fling(Direction.DOWN)
        device.waitForIdle()
        list.fling(Direction.UP)
        device.waitForIdle()
    }
    scrollToTop()
    device.findObject(By.text("Buscar tarefas"))?.let { search ->
        search.click()
        device.findObject(By.clazz("android.widget.EditText"))?.text = "Perfil"
        device.waitForIdle()
        device.findObject(By.desc("Limpar busca"))?.click()
        device.waitForIdle()
        // Fecha o teclado sem sair da aba: depois do Limpar a dica volta e o foco continua no campo, então
        // a dica não diz nada do teclado (NB-E7-B). Voltar só com o teclado aberto; sem ele, o Voltar
        // sairia da aba.
        if (isKeyboardShown()) {
            device.pressBack()
            device.waitForIdle()
        }
    }
    if (clickInList(SAMPLE_TASKS.first())) {
        device.wait(Until.hasObject(By.text("Detalhes da tarefa")), TIMEOUT_MS)
        device.pressBack()
    }
    openTab(Tab.TASKS)
}

/**
 * O teclado está na tela, pelo estado do serviço de entrada (o UiAutomator não o vê como objeto).
 * Na dúvida (dump sem o campo esperado), diz que não está, e a jornada não aperta Voltar.
 */
private fun MacrobenchmarkScope.isKeyboardShown(): Boolean {
    val dump = runCatching { device.executeShellCommand("dumpsys input_method") }.getOrDefault("")
    return KEYBOARD_SHOWN_MARKERS.any { it in dump }
}

/** Como o dumpsys do input_method diz que o teclado está aberto, conforme a versão do Android. */
private val KEYBOARD_SHOWN_MARKERS = listOf("mInputShown=true", "isInputViewShown=true", "mIsInputViewShown=true")

/** A faixa da semana da Hoje: a área rolável mais baixa (a lista principal é a mais alta). */
private fun MacrobenchmarkScope.weekStrip(): UiObject2? =
    device.findObjects(By.scrollable(true))
        .filter { runCatching { it.visibleBounds.width() > it.visibleBounds.height() }.getOrDefault(false) }
        .minByOrNull { runCatching { it.visibleBounds.height() }.getOrDefault(Int.MAX_VALUE) }
        ?.takeIf { it != mainList() }

/** Na Hoje: rola a linha do tempo e as seções e troca de semana pela faixa (ida e volta). */
fun MacrobenchmarkScope.browseToday() {
    openTab(Tab.TODAY)
    mainList()?.let { list ->
        list.fling(Direction.DOWN)
        device.waitForIdle()
        list.fling(Direction.UP)
        device.waitForIdle()
    }
    // A faixa rola junto com a lista (E3): no topo ela está visível.
    scrollToTop()
    weekStrip()?.let { strip ->
        val bounds = runCatching { strip.visibleBounds }.getOrNull() ?: return@let
        val y = bounds.centerY()
        device.swipe(bounds.right - bounds.width() / 8, y, bounds.left + bounds.width() / 8, y, 20)
        device.waitForIdle()
        device.swipe(bounds.left + bounds.width() / 8, y, bounds.right - bounds.width() / 8, y, 20)
        device.waitForIdle()
    }
    openTab(Tab.TODAY)
}

/**
 * "Todas as rotinas", pelo menu da Hoje: garante um bloco (criado pelo editor, para perfilar também o
 * editor e, na Hoje, a linha do tempo com o bloco), abre a rotina no editor e volta. O bloco vale
 * todos os dias, então aparece na linha do tempo da Hoje.
 */
fun MacrobenchmarkScope.browseRoutines() {
    openTab(Tab.TODAY)
    device.findObject(By.desc("Mais opções"))?.click() ?: return
    device.wait(Until.hasObject(By.text("Todas as rotinas")), TIMEOUT_MS)
    device.findObject(By.text("Todas as rotinas"))?.click() ?: return
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
    device.pressBack()
    openTab(Tab.TODAY)
}

/**
 * Abre a aba Listas, garante uma lista (criada pela própria aba, só se não achar rolando), abre a
 * tela da lista e volta. Perfila a aba e a tela de uso da lista (v1.5.0).
 */
fun MacrobenchmarkScope.browseLists() {
    openTab(Tab.LISTS)
    if (!clickInList(SAMPLE_LIST)) {
        // Criar pela aba já abre a lista nova.
        device.findObject(By.desc("Nova lista"))?.click()
        typeAndSave(SAMPLE_LIST)
    }
    // Tela da lista (v1.7.0): o campo de adição rápida no rodapé, com a dica "Adicionar item", no
    // lugar do FAB. Não adiciona nada: a jornada não pode criar itens a cada rodada.
    device.wait(Until.hasObject(By.text("Adicionar item")), TIMEOUT_MS)
    device.pressBack()
    openTab(Tab.LISTS)
}

/** Abre o calendário e troca de mês para frente e para trás. */
fun MacrobenchmarkScope.browseCalendar() {
    openTab(Tab.CALENDAR)
    repeat(2) {
        device.findObject(By.desc("Próximo mês"))?.click()
        device.waitForIdle()
    }
    repeat(2) {
        device.findObject(By.desc("Mês anterior"))?.click()
        device.waitForIdle()
    }
}

/** Abre as configurações pela Hoje e volta. */
fun MacrobenchmarkScope.browseSettings() {
    openTab(Tab.TODAY)
    device.findObject(By.desc("Configurações"))?.click()
    device.wait(Until.hasObject(By.text("Resumo diário")), TIMEOUT_MS)
    device.pressBack()
    openTab(Tab.TODAY)
}
