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

/** A primeira tela pode demorar mais (partida a frio sem perfil, no StartupBenchmark). */
private const val STARTUP_TIMEOUT_MS = 10_000L
private val SAMPLE_TASKS = listOf("Perfil: revisar e-mails", "Perfil: reunião semanal", "Perfil: pagar contas")
private const val SAMPLE_ROUTINE = "Perfil: treino"

/** Limite de rolagens por busca: cobre listas longas sem prender a jornada numa tela inesperada. */
private const val MAX_SCROLLS = 40

/** Abaixo desta fração da altura o item pode estar sob o botão flutuante; rola um pouco mais. */
private const val SAFE_BOTTOM_FRACTION = 0.7f

/** Mais que a espera de 500 ms do campo de texto (salvar na hora, v1.9.0): a gravação sai antes do Voltar. */
private const val TEXT_DEBOUNCE_WAIT_MS = 700L

/** Voltas no máximo para sair de uma tela interna (a primeira pode só fechar o teclado). */
private const val MAX_BACKS = 3

/** Quantos textos da tela vão na mensagem de falha. */
private const val SUMMARY_ITEMS = 25

/*
 * Jornadas da v1.8.0 (E7): o app abre na Hoje e a aba Rotinas não existe mais (as rotinas ficam na
 * linha do tempo da Hoje e em "Todas as rotinas"). Cada jornada abre a SUA aba e espera um marcador
 * que só aquela tela tem (o rótulo da aba está sempre na barra e não serve de espera), e só cria o
 * exemplo que não achar rolando a lista: rodar de novo, ou com os dados de quem usa o app, não duplica.
 *
 * v1.9.0 (salvar na hora, ADR-0002 15.3 a 15.5): o detalhe da tarefa e o editor de rotina não têm mais
 * "Salvar"; a jornada edita um campo e volta ([editAndLeave]). Criar rotina é pelo "Criar rotina" (no
 * rodapé, ou na barra do topo em altura compacta), e a tela fecha depois. O painel de nova tarefa e o
 * diálogo de nova lista seguem com "Salvar".
 *
 * FALHAR ALTO (v1.9.0, E5): na geração da v1.8.0 e na primeira da v1.9.0, "Todas as rotinas" e o editor
 * de rotina ficaram FORA do perfil porque a jornada saía cedo, calada, e o teste passava. Agora cada
 * passo que precisa de um elemento derruba a geração com o nome do passo e o que estava na tela
 * ([fail]). Só a busca é opcional (rolar a lista atrás de um item, que pode não existir ainda) e o
 * que a tela pode legitimamente não ter (uma lista curta que não rola); o passo que depende da busca
 * confere o resultado.
 */

/**
 * TODO seletor das jornadas passa por aqui (v1.10.0, E0): restrito ao pacote do app. Sem o pacote, um
 * marcador casava com o widget da tela inicial (desc "Nova tarefa", igual ao FAB da aba Tarefas), e o
 * backTo "chegava" depois de o Voltar ter saído do app. Cria um seletor novo a cada chamada (o
 * BySelector acumula critérios e não aceita dois pacotes).
 */
private fun inApp(): BySelector = By.pkg(PACKAGE_NAME)

/** O app está em primeiro plano (a janela ativa é dele). */
private fun MacrobenchmarkScope.appInForeground(): Boolean =
    runCatching { device.currentPackageName }.getOrNull() == PACKAGE_NAME

/**
 * Aperta Voltar SÓ com o app em primeiro plano, e derruba em [step] se o Voltar levar para fora dele:
 * fora do app, um Voltar a mais mexe na tela inicial e o passo seguinte já não diz nada.
 */
private fun MacrobenchmarkScope.pressBackInApp(step: String) {
    if (!appInForeground()) fail("$step: o app não está em primeiro plano; não aperta Voltar fora dele")
    device.pressBack()
    device.waitForIdle()
    if (!appInForeground()) fail("$step: o Voltar saiu do app")
}

/** Abas e o marcador de cada tela (qualquer um deles basta: com e sem conteúdo). */
private enum class Tab(val label: String, val markers: List<BySelector>) {
    TODAY("Hoje", listOf(inApp().text("Progresso do dia"), inApp().text("Nada no seu dia ainda"))),
    TASKS("Tarefas", listOf(inApp().text("Buscar tarefas"), inApp().text("Nenhuma tarefa"))),
    CALENDAR("Calendário", listOf(inApp().desc("Próximo mês"))),
}

/** O detalhe da tarefa aberto (o título da barra). */
private val TASK_DETAIL_MARKER: BySelector = inApp().text("Detalhes da tarefa")

/**
 * O cartão da lista no detalhe da tarefa (T3, v1.10.0): sem itens diz "Lista vazia"; com itens, o
 * progresso ("1 de 2 marcados"). A jornada não adiciona itens, mas os dados do aparelho podem ter.
 */
private val LIST_CARD_MARKERS: List<BySelector> = listOf(inApp().text("Lista vazia"), inApp().textContains(" marcad"))

/** O FAB de "Todas as rotinas" (a descrição; o editor novo tem "Nova rotina" só como TEXTO, no título). */
private val ALL_ROUTINES_MARKER: BySelector = inApp().desc("Nova rotina")

/** O FAB da aba Tarefas (a da Hoje diz também o dia: "Nova tarefa em ..."). */
private val TASKS_FAB: BySelector = inApp().desc("Nova tarefa")

/**
 * Derruba a geração no passo [step], com os textos da tela na mensagem: sem eles, a falha no emulador
 * só diria o passo, não onde a jornada estava.
 */
private fun MacrobenchmarkScope.fail(step: String): Nothing =
    error("$step. Na tela (${runCatching { device.currentPackageName }.getOrNull()}): ${screenSummary()}")

/** Os textos e descrições visíveis do app, na ordem da árvore (sem repetir). */
private fun MacrobenchmarkScope.screenSummary(): String =
    runCatching {
        device.findObjects(inApp())
            .mapNotNull { node -> runCatching { node.text?.takeIf { it.isNotBlank() } ?: node.contentDescription }.getOrNull() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(SUMMARY_ITEMS)
            .joinToString(" | ", prefix = "[", postfix = "]")
    }.getOrDefault("[sem leitura]")

/** Espera [selector] aparecer; senão derruba em [step]. */
private fun MacrobenchmarkScope.requireShown(selector: BySelector, step: String, timeoutMs: Long = TIMEOUT_MS) {
    if (!device.wait(Until.hasObject(selector), timeoutMs)) fail(step)
}

/** Espera [selector] e toca nele (buscando de novo se a tela recompor); senão derruba em [step]. */
private fun MacrobenchmarkScope.requireClick(selector: BySelector, step: String) {
    requireShown(selector, step)
    if (!actOnFresh(selector) { it.click() }) fail(step)
}

/**
 * Volta até a tela com algum dos [markers] (seletores [inApp]: só marcadores do app contam): o primeiro
 * Voltar pode só fechar o teclado (o dumpsys nem sempre diz que ele está aberto), por isso confere e
 * volta de novo; antes do primeiro, confere se já está lá. Derruba em [step] se não chegar e, já no Voltar que tirar o app do primeiro plano,
 * para com erro em vez de seguir apertando Voltar na tela inicial ([pressBackInApp]).
 */
private fun MacrobenchmarkScope.backTo(markers: List<BySelector>, step: String) {
    // Já na tela (um Voltar anterior, como o de fechar o teclado, já saiu): não aperta mais nada.
    if (appInForeground() && waitForAny(markers, timeoutMs = 0)) return
    repeat(MAX_BACKS) {
        pressBackInApp(step)
        if (waitForAny(markers)) return
        if (!appInForeground()) fail("$step: o app saiu do primeiro plano")
    }
    fail(step)
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
 * some: volta com o Voltar até a barra aparecer. Já na aba, não faz nada. Derruba se não chegar.
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
        // O rótulo da aba é o MAIS BAIXO com esse texto (a barra fica no rodapé): desde a T3 (v1.10.0) a
        // aba Tarefas tem a seção "Hoje" (Atrasadas, Hoje, Próximas), e o primeiro achado era ela.
        val label = device.findObjects(inApp().text(tab.label))
            .maxByOrNull { runCatching { it.visibleBounds.top }.getOrDefault(Int.MIN_VALUE) }
        if (label != null) {
            runCatching { label.click() }
        } else {
            pressBackInApp("openTab '${tab.label}'")
        }
        if (waitForAny(tab.markers)) return
    }
    fail("openTab: não chegou à aba '${tab.label}'")
}

/** Voltas no máximo até achar a aba (tela interna dentro de tela interna, painel aberto). */
private const val MAX_TAB_ATTEMPTS = 4

/** Espera a Hoje, a tela inicial (v1.8.0). Derruba se ela não abrir. */
fun MacrobenchmarkScope.waitForToday() {
    if (!waitForAny(Tab.TODAY.markers, STARTUP_TIMEOUT_MS)) fail("waitForToday: a Hoje não abriu")
}

/**
 * A lista principal da tela: a área rolável mais alta. As telas também têm faixas roláveis na
 * horizontal (a semana da Hoje, filtros), que não servem para procurar itens. Null quando o conteúdo
 * cabe na tela (o Compose só marca como rolável o que tem para onde rolar).
 */
private fun MacrobenchmarkScope.mainList(): UiObject2? =
    device.findObjects(inApp().scrollable(true))
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

/** Passa a lista para baixo e para cima, se ela rola (opcional: lista curta não rola). */
private fun MacrobenchmarkScope.flingMainList() {
    val list = mainList() ?: return
    runCatching { list.fling(Direction.DOWN) }
    device.waitForIdle()
    runCatching { list.fling(Direction.UP) }
    device.waitForIdle()
}

/**
 * Procura um item pelo texto rolando a lista principal desde o topo. Com os dados de quem já usa
 * o app, o item de exemplo fica fora da tela; sem rolar, a jornada o criaria de novo a cada vez.
 * Devolve null se a lista acabou sem achar (ou a tela não tem lista). Busca OPCIONAL: quem depende
 * do item confere o retorno.
 */
private fun MacrobenchmarkScope.findInList(text: String): UiObject2? {
    val selector = inApp().text(text)
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

/** O primeiro campo de texto da tela, painel ou diálogo aberto (o título, nos editores). */
private val FIRST_FIELD: BySelector = inApp().clazz("android.widget.EditText")

/** Escreve [text] no primeiro campo de texto da tela; derruba em [step] se não houver campo. */
private fun MacrobenchmarkScope.requireTypeInFirstField(text: String, step: String) {
    requireShown(FIRST_FIELD, "$step: sem campo de texto")
    device.waitForIdle()
    if (!actOnFresh(FIRST_FIELD) { it.text = text }) fail("$step: o campo de texto não recebeu o texto")
    device.waitForIdle()
}

/**
 * Preenche o primeiro campo de texto do painel, diálogo ou editor aberto e toca em [confirm] ("Salvar"
 * no painel de nova tarefa e na nova lista; "Criar rotina" na rotina nova). O painel de nova tarefa
 * (v1.8.0, O-6) recompõe o campo enquanto entra e traz o campo à vista: o objeto achado podia ficar
 * obsoleto antes de receber o texto (derrubava a geração no emulador). Por isso espera assentar e
 * busca de novo a cada tentativa. Com o teclado aberto, fecha-o antes, para o botão de rodapé não
 * ficar atrás dele. Derruba em [step] se faltar o campo ou o botão, ou se a tela não fechar.
 */
private fun MacrobenchmarkScope.typeAndConfirm(text: String, step: String, confirm: String = "Salvar") {
    requireTypeInFirstField(text, step)
    hideKeyboard()
    requireClick(inApp().text(confirm), "$step: sem '$confirm'")
    // Espera o painel, o diálogo ou o editor fechar.
    if (!device.wait(Until.gone(inApp().text(confirm)), TIMEOUT_MS)) fail("$step: '$confirm' não fechou a tela")
}

/**
 * Salvar na hora (v1.9.0): no detalhe ou no editor aberto, regrava o primeiro campo (o título) com o
 * PRÓPRIO texto de exemplo, espera a gravação sair e volta até a tela de [backMarker]. O título não
 * muda, então o exemplo segue achável e nada duplica; o caminho da gravação (campo, fila, repositório)
 * roda do mesmo jeito.
 */
private fun MacrobenchmarkScope.editAndLeave(title: String, backMarker: BySelector, step: String) {
    requireTypeInFirstField(title, step)
    Thread.sleep(TEXT_DEBOUNCE_WAIT_MS)
    hideKeyboard()
    backTo(listOf(backMarker), "$step: o Voltar não saiu da tela")
}

/** Fecha o teclado, se aberto (o Voltar com ele aberto só o fecha; sem ele, sairia da tela). */
private fun MacrobenchmarkScope.hideKeyboard() {
    if (isKeyboardShown()) pressBackInApp("hideKeyboard")
}

/**
 * Garante algumas tarefas para que a lista, o detalhe, a Hoje e o calendário tenham conteúdo, pelo
 * painel de nova tarefa (hospedado no topo da navegação desde a v1.8.0).
 */
fun MacrobenchmarkScope.ensureSampleTasks() {
    openTab(Tab.TASKS)
    SAMPLE_TASKS.forEach { title ->
        if (findInList(title) != null) return@forEach
        requireClick(TASKS_FAB, "ensureSampleTasks: sem o botão 'Nova tarefa'")
        typeAndConfirm(title, "ensureSampleTasks: criar '$title'")
        openTab(Tab.TASKS)
    }
}

/** Na aba Tarefas: rola a lista, filtra pela busca (que é item da lista) e limpa, abre um detalhe e volta. */
fun MacrobenchmarkScope.browseTasks() {
    openTab(Tab.TASKS)
    flingMainList()
    scrollToTop()
    requireClick(inApp().text("Buscar tarefas"), "browseTasks: sem a busca 'Buscar tarefas'")
    requireTypeInFirstField("Perfil", "browseTasks: busca")
    requireClick(inApp().desc("Limpar busca"), "browseTasks: sem 'Limpar busca'")
    device.waitForIdle()
    // Fecha o teclado sem sair da aba: depois do Limpar a dica volta e o foco continua no campo, então
    // a dica não diz nada do teclado (NB-E7-B). Voltar só com o teclado aberto; sem ele, o Voltar
    // sairia da aba.
    hideKeyboard()
    // ensureSampleTasks acabou de garantir a tarefa: não achá-la é falha, não caminho alternativo.
    if (!clickInList(SAMPLE_TASKS.first())) fail("browseTasks: sem a tarefa '${SAMPLE_TASKS.first()}' na lista")
    requireShown(inApp().text("Detalhes da tarefa"), "browseTasks: o detalhe da tarefa não abriu")
    // Detalhe sem "Salvar" (v1.9.0): edita o título (com o mesmo texto) e volta; a gravação é na hora.
    editAndLeave(SAMPLE_TASKS.first(), TASKS_FAB, "browseTasks: detalhe")
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
    device.findObjects(inApp().scrollable(true))
        .filter { runCatching { it.visibleBounds.width() > it.visibleBounds.height() }.getOrDefault(false) }
        .minByOrNull { runCatching { it.visibleBounds.height() }.getOrDefault(Int.MAX_VALUE) }
        ?.takeIf { it != mainList() }

/**
 * Na Hoje: rola a linha do tempo e as seções e troca de semana pela faixa (ida e volta). A rolagem e
 * a faixa são opcionais: um dia curto não rola, e a Hoje vazia não mostra a faixa.
 */
fun MacrobenchmarkScope.browseToday() {
    openTab(Tab.TODAY)
    flingMainList()
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
 * "Todas as rotinas", pelo menu da Hoje, e o editor de rotina nos DOIS modos, com os dados que o
 * aparelho tiver (sem pm clear; a rotina de exemplo pode ou não existir):
 * - CRIAÇÃO: abre "Nova rotina". Sem a rotina de exemplo, digita o título e cria pelo "Criar rotina"
 *   (o editor fecha e volta à lista); com ela, troca o Tipo para Exercício e de volta para Nenhum
 *   (v1.10.0, 16.10.5: compõe a seção Exercícios com "Dos salvos") e sai pelo Voltar sem criar, para
 *   não duplicar a cada iteração. O rascunho volta a ser o da abertura, então sai sem "Descartar?".
 * - EDIÇÃO: abre a rotina de exemplo (garantida acima), abre e recolhe "Mais opções", regrava o título
 *   com o mesmo texto e volta.
 * O bloco vale todos os dias, então aparece também na linha do tempo da Hoje.
 */
fun MacrobenchmarkScope.browseRoutines() {
    openTab(Tab.TODAY)
    openAllRoutines()

    // CRIAÇÃO
    val sampleExists = findInList(SAMPLE_ROUTINE) != null
    requireClick(ALL_ROUTINES_MARKER, "browseRoutines: sem o botão 'Nova rotina' em 'Todas as rotinas'")
    // Marcador do editor novo: o "Criar rotina" (rodapé ou barra do topo); o título "Nova rotina" é
    // também o texto do FAB da lista.
    requireShown(inApp().text("Criar rotina"), "browseRoutines: o editor de rotina nova não abriu")
    if (sampleExists) {
        hideKeyboard()
        toggleKindToExerciseAndBack()
        backTo(listOf(ALL_ROUTINES_MARKER), "browseRoutines: o Voltar do editor novo não voltou a 'Todas as rotinas'")
    } else {
        typeAndConfirm(SAMPLE_ROUTINE, "browseRoutines: criar '$SAMPLE_ROUTINE'", confirm = "Criar rotina")
        requireShown(ALL_ROUTINES_MARKER, "browseRoutines: 'Criar rotina' não voltou a 'Todas as rotinas'")
    }

    // EDIÇÃO
    if (!clickInList(SAMPLE_ROUTINE)) fail("browseRoutines: sem a rotina '$SAMPLE_ROUTINE' na lista")
    requireShown(inApp().text("Editar rotina"), "browseRoutines: o editor da rotina não abriu")
    toggleMoreOptions()
    editAndLeave(SAMPLE_ROUTINE, ALL_ROUTINES_MARKER, "browseRoutines: editor")

    backTo(Tab.TODAY.markers, "browseRoutines: o Voltar de 'Todas as rotinas' não voltou à Hoje")
    openTab(Tab.TODAY)
}

/**
 * No editor de rotina NOVA (v1.10.0, 16.10.5): toca em "Exercício" na seção Tipo, espera a seção
 * Exercícios ("Dos salvos"), e volta ao "Nenhum" da abertura, sem deixar mudança no rascunho. Termina
 * com a lista de volta ao topo.
 */
private fun MacrobenchmarkScope.toggleKindToExerciseAndBack() {
    val step = "browseRoutines: Tipo no editor novo"
    findInList("Exercício") ?: fail("$step: sem a opção 'Exercício'")
    if (!actOnFresh(inApp().text("Exercício")) { it.click() }) fail("$step: não tocou em 'Exercício'")
    device.waitForIdle()
    findInList("Dos salvos") ?: fail("$step: o tipo Exercício não mostrou a seção Exercícios")
    // O painel dos exercícios salvos (v1.10.0, T2): abre e fecha pelo Voltar, sem aplicar nada.
    if (!actOnFresh(inApp().text("Dos salvos")) { it.click() }) fail("$step: não tocou em 'Dos salvos'")
    requireShown(inApp().text("Exercícios salvos"), "$step: o painel 'Exercícios salvos' não abriu")
    pressBackInApp("$step: fechar o painel dos salvos")
    if (!device.wait(Until.gone(inApp().text("Exercícios salvos")), TIMEOUT_MS)) fail("$step: o painel dos salvos não fechou")
    findInList("Nenhum") ?: fail("$step: sem a opção 'Nenhum'")
    if (!actOnFresh(inApp().text("Nenhum")) { it.click() }) fail("$step: não tocou em 'Nenhum'")
    device.waitForIdle()
    scrollToTop()
}

/**
 * Da Hoje a "Todas as rotinas" pelo menu "Mais opções" (a DESCRIÇÃO do botão da barra). Se o toque
 * cair numa recomposição e o menu não abrir, tenta mais uma vez.
 */
private fun MacrobenchmarkScope.openAllRoutines() {
    val item = inApp().text("Todas as rotinas")
    var menuOpen = false
    for (attempt in 1..2) {
        requireClick(inApp().desc("Mais opções"), "browseRoutines: sem o menu 'Mais opções' na Hoje")
        menuOpen = device.wait(Until.hasObject(item), TIMEOUT_MS)
        if (menuOpen) break
    }
    if (!menuOpen) fail("browseRoutines: o menu da Hoje não mostrou 'Todas as rotinas'")
    requireClick(item, "browseRoutines: sem 'Todas as rotinas' no menu")
    requireShown(ALL_ROUTINES_MARKER, "browseRoutines: 'Todas as rotinas' não abriu")
}

/**
 * No editor de rotina: "Mais opções" começa recolhido (v1.9.0; abre já aberto se a rotina tem vigência
 * ou observações). Rola até o cabeçalho da seção (o TEXTO; o menu da barra usa a mesma frase como
 * descrição, então não se confundem), toca duas vezes, abrindo e recolhendo (ou o contrário), sem
 * mudar o estado de antes. Termina com a lista de volta ao topo (o título).
 */
private fun MacrobenchmarkScope.toggleMoreOptions() {
    val header = inApp().text("Mais opções")
    findInList("Mais opções") ?: fail("browseRoutines: sem a seção 'Mais opções' no editor")
    repeat(2) {
        if (!actOnFresh(header) { it.click() }) fail("browseRoutines: não tocou em 'Mais opções'")
        device.waitForIdle()
    }
    scrollToTop()
}

/**
 * A lista de compras é da tarefa (T3, v1.10.0: sem a aba Listas). Abre a segunda tarefa de exemplo,
 * cria a lista dela pelo "Adicionar lista" só se o cartão ainda não existe (rodar de novo não cria
 * outra: o app recusa a segunda lista, A1), abre a lista pelo cartão e volta ao detalhe e à aba
 * Tarefas. Perfila o cartão da lista no detalhe e a tela de uso da lista. Não adiciona itens.
 */
fun MacrobenchmarkScope.browseLists() {
    val task = SAMPLE_TASKS[1]
    openTab(Tab.TASKS)
    if (!clickInList(task)) fail("browseLists: sem a tarefa '$task' na lista")
    requireShown(TASK_DETAIL_MARKER, "browseLists: o detalhe da tarefa não abriu")
    findInList("Lista de compras") ?: fail("browseLists: o detalhe não mostrou a seção 'Lista de compras'")
    if (findListCard() == null) {
        findInList("Adicionar lista") ?: fail("browseLists: sem o cartão da lista e sem 'Adicionar lista'")
        if (!actOnFresh(inApp().text("Adicionar lista")) { it.click() }) fail("browseLists: não tocou em 'Adicionar lista'")
        device.waitForIdle()
        if (!waitForAny(LIST_CARD_MARKERS)) fail("browseLists: 'Adicionar lista' não mostrou o cartão")
    }
    findListCard() ?: fail("browseLists: o cartão da lista não está na tela")
    val card = LIST_CARD_MARKERS.firstOrNull { device.hasObject(it) } ?: fail("browseLists: o cartão da lista sumiu")
    if (!actOnFresh(card) { it.click() }) fail("browseLists: não tocou no cartão da lista")
    // Tela da lista (v1.7.0): o campo de adição rápida no rodapé, com a dica "Adicionar item".
    requireShown(inApp().text("Adicionar item"), "browseLists: a tela da lista não abriu")
    backTo(listOf(TASK_DETAIL_MARKER), "browseLists: o Voltar da lista não voltou ao detalhe")
    backTo(Tab.TASKS.markers, "browseLists: o Voltar do detalhe não voltou à aba Tarefas")
    openTab(Tab.TASKS)
}

/** O cartão da lista no detalhe, rolando até ele (vazio pelo texto; com itens, pelo progresso). */
private fun MacrobenchmarkScope.findListCard(): UiObject2? =
    findInList("Lista vazia") ?: device.findObject(LIST_CARD_MARKERS[1])

/** Abre o calendário e troca de mês para frente e para trás. */
fun MacrobenchmarkScope.browseCalendar() {
    openTab(Tab.CALENDAR)
    repeat(2) {
        requireClick(inApp().desc("Próximo mês"), "browseCalendar: sem 'Próximo mês'")
        device.waitForIdle()
    }
    repeat(2) {
        requireClick(inApp().desc("Mês anterior"), "browseCalendar: sem 'Mês anterior'")
        device.waitForIdle()
    }
}

/** Abre as configurações pela Hoje e volta. */
fun MacrobenchmarkScope.browseSettings() {
    openTab(Tab.TODAY)
    requireClick(inApp().desc("Configurações"), "browseSettings: sem o botão 'Configurações' na Hoje")
    // O título da tela (TEXTO; na Hoje é só a descrição do ícone) ou a primeira seção.
    if (!waitForAny(listOf(inApp().text("Configurações"), inApp().text("Resumo diário")))) fail("browseSettings: as configurações não abriram")
    backTo(Tab.TODAY.markers, "browseSettings: o Voltar não voltou à Hoje")
    openTab(Tab.TODAY)
}
