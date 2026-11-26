package com.kalendrys.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.NewTaskPanel
import com.kalendrys.app.LaunchRequest
import com.kalendrys.app.R
import com.kalendrys.app.UndoOffers
import com.kalendrys.app.data.repository.today
import com.kalendrys.app.ui.calendar.CalendarScreen
import com.kalendrys.app.ui.components.LocalAppClock
import com.kalendrys.app.ui.components.LocalAppSnackbarHostState
import com.kalendrys.app.ui.diagnostics.AlarmDiagnosticsScreen
import com.kalendrys.app.ui.routineeditor.ROUTINE_EDITOR_ROUTE
import com.kalendrys.app.ui.routineeditor.ROUTINE_ID_ARG
import com.kalendrys.app.ui.routineeditor.RoutineEditorScreen
import com.kalendrys.app.ui.routineeditor.routineEditorRoute
import com.kalendrys.app.ui.routineexception.ROUTINE_EXCEPTION_DAY_ARG
import com.kalendrys.app.ui.routineexception.ROUTINE_EXCEPTION_ROUTE
import com.kalendrys.app.ui.routineexception.ROUTINE_EXCEPTION_ROUTINE_ID_ARG
import com.kalendrys.app.ui.routineexception.RoutineExceptionScreen
import com.kalendrys.app.ui.routineexception.routineExceptionRoute
import com.kalendrys.app.ui.settings.SettingsScreen
import com.kalendrys.app.ui.shopping.NO_TASK_ID
import com.kalendrys.app.ui.shopping.SHOPPING_LIST_ID_ARG
import com.kalendrys.app.ui.shopping.SHOPPING_LIST_ROUTE
import com.kalendrys.app.ui.shopping.SHOPPING_LIST_TASK_ID_ARG
import com.kalendrys.app.ui.shopping.ShoppingListScreen
import com.kalendrys.app.ui.shopping.shoppingListRoute
import com.kalendrys.app.ui.taskdetail.TASK_DETAIL_ROUTE
import com.kalendrys.app.ui.taskdetail.TASK_ID_ARG
import com.kalendrys.app.ui.taskdetail.TaskDetailScreen
import com.kalendrys.app.ui.taskdetail.taskDetailRoute
import com.kalendrys.app.ui.tasks.TaskListScreen
import com.kalendrys.app.ui.today.TodayScreen
import com.kalendrys.app.ui.routines.ALL_ROUTINES_ROUTE
import com.kalendrys.app.ui.routines.AllRoutinesScreen
import java.time.LocalDate

enum class AppTab(
    val route: String,
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    /**
     * Hoje (v1.8.0, ADR-0002 14.4): substitui a aba Rotinas e é o destino inicial. A rota continua
     * "routines" DE PROPÓSITO: o estado salvo pela v1.7.1 com a aba Rotinas aberta (pilha com essa
     * rota) é restaurado direto na Hoje; com outra rota o Navigation não acharia o destino ao
     * restaurar e o app quebraria ao abrir por cima da versão anterior.
     */
    Today("routines", R.string.tab_today, Icons.Filled.Today, Icons.Outlined.Today),
    Tasks("tasks", R.string.tab_tasks, Icons.Filled.TaskAlt, Icons.Outlined.TaskAlt),
    Calendar("calendar", R.string.tab_calendar, Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
}

/**
 * Rota da antiga aba Listas (até a v1.9.x). A aba saiu na v1.10.0 (ADR-0002 16.10.1), mas a rota fica
 * registrada: um estado salvo com ela aberta (atualizar com a aba Listas na frente) é restaurado aqui e
 * vai para a aba Tarefas, em vez de derrubar o app (o mesmo cuidado da rota "routines" na v1.8.0).
 */
private const val LEGACY_LISTS_ROUTE = "lists"


@Composable
fun KalendrysNavHost(
    launchRequest: LaunchRequest? = null,
    onLaunchRequestHandled: () -> Unit = {},
    undoOffers: UndoOffers = (LocalContext.current.applicationContext as KalendrysApp).undoOffers,
    newTaskPanel: NewTaskPanel = (LocalContext.current.applicationContext as KalendrysApp).newTaskPanel,
) {
    val navController = rememberNavController()
    val clock = LocalAppClock.current
    // Mensagens do app (Desfazer das exclusões de tarefa e rotina): o único coletor; quem desenha é o
    // AppScaffold da tela à frente, que lê o estado pelo LocalAppSnackbarHostState.
    val appSnackbar = remember { SnackbarHostState() }
    UndoOfferEffect(undoOffers, appSnackbar)
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute == null || AppTab.entries.any { it.route == currentRoute }
    val openTask: (Long) -> Unit = { taskId -> navController.navigate(taskDetailRoute(taskId)) }
    val openSettings: () -> Unit = { navController.navigate(SETTINGS_ROUTE) { launchSingleTop = true } }
    val openDiagnostics: () -> Unit = { navController.navigate(ALARM_DIAGNOSTICS_ROUTE) { launchSingleTop = true } }
    val openRoutine: (Long) -> Unit = { routineId -> navController.navigate(routineEditorRoute(routineId)) }
    val app = LocalContext.current.applicationContext as KalendrysApp
    val openRoutineException: (Long, Long) -> Unit = { routineId, epochDay ->
        navController.navigate(routineExceptionRoute(routineId, epochDay))
    }
    // Painel de nova tarefa (E6, 14.7): ABERTO ou FECHADO é estado de tela, salvo aqui no topo (o dia
    // sugerido, em epochDay; null = fechado). Sobrevive a girar e à morte do processo (aí reabre com o
    // rascunho vazio, perda aceita) e some quando a Activity termina. Rascunho e operações são do app.
    var newTaskEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val openNewTask: (LocalDate) -> Unit = { date -> newTaskEpochDay = date.toEpochDay() }
    // Dia pedido por uma notificação ou widget de rotina (kalendrys://routines/{data}), entregue à Hoje uma única vez.
    var routinesDateRequested by rememberSaveable { mutableStateOf<LocalDate?>(null) }

    CompositionLocalProvider(LocalAppSnackbarHostState provides appSnackbar) {
        Scaffold(
            // Cada tela tem a própria barra de título, que desenha atrás da barra de status.
            // Sem snackbarHost aqui: a mensagem do app fica no slot da tela (acima do FAB e da mensagem dela).
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                // A tela de detalhe ocupa a tela inteira; a barra de abas só aparece nas abas.
                AnimatedVisibility(
                    visible = showBottomBar,
                    enter = slideInVertically { it },
                    exit = slideOutVertically { it },
                ) {
                    NavigationBar {
                        AppTab.entries.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navController.openTab(tab) },
                                icon = {
                                    Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null)
                                },
                                label = { TabLabel(stringResource(tab.labelRes)) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = AppTab.Today.route,
                // Sem consumir, o Scaffold de cada tela aplicaria de novo o inset da barra de navegação.
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
            ) {
                composable(AppTab.Tasks.route) {
                    TaskListScreen(
                        onOpenTask = openTask,
                        onOpenSettings = openSettings,
                        onNewTask = openNewTask,
                    )
                }
                composable(AppTab.Today.route) {
                    TodayScreen(
                        onOpenRoutine = openRoutine,
                        onNewRoutine = { navController.navigate(routineEditorRoute()) },
                        onOpenAllRoutines = { navController.navigate(ALL_ROUTINES_ROUTE) { launchSingleTop = true } },
                        onOpenSettings = openSettings,
                        onOpenTask = openTask,
                        onOpenException = openRoutineException,
                        onNewTask = openNewTask,
                        requestedDate = routinesDateRequested,
                        onRequestedDateHandled = { routinesDateRequested = null },
                    )
                }
                composable(AppTab.Calendar.route) {
                    CalendarScreen(
                        onOpenTask = openTask,
                        onOpenRoutine = openRoutine,
                        onOpenSettings = openSettings,
                        onNewTask = openNewTask,
                    )
                }
                composable(LEGACY_LISTS_ROUTE) {
                    LaunchedEffect(Unit) { navController.openTab(AppTab.Tasks) }
                }
                // Tela de uso da lista (ADR-0001, 4.5); a barra de abas some por não ser rota de aba.
                composable(
                    route = SHOPPING_LIST_ROUTE,
                    arguments = listOf(
                        navArgument(SHOPPING_LIST_ID_ARG) { type = NavType.LongType },
                        navArgument(SHOPPING_LIST_TASK_ID_ARG) {
                            type = NavType.LongType
                            defaultValue = NO_TASK_ID
                        },
                    ),
                    enterTransition = { slideIntoContainer(SlideDirection.Start) },
                    popExitTransition = { slideOutOfContainer(SlideDirection.End) },
                ) {
                    ShoppingListScreen(onBack = { navController.popIfCurrent(SHOPPING_LIST_ROUTE) })
                }
                // "Todas as rotinas" (14.4): gestão das rotinas, fora do retrato do dia; a barra de abas some.
                composable(
                    route = ALL_ROUTINES_ROUTE,
                    enterTransition = { slideIntoContainer(SlideDirection.Start) },
                    popExitTransition = { slideOutOfContainer(SlideDirection.End) },
                ) {
                    AllRoutinesScreen(
                        onBack = { navController.popIfCurrent(ALL_ROUTINES_ROUTE) },
                        onOpenRoutine = openRoutine,
                        onNewRoutine = { navController.navigate(routineEditorRoute()) },
                    )
                }
                composable(
                    route = ROUTINE_EDITOR_ROUTE,
                    arguments = listOf(navArgument(ROUTINE_ID_ARG) { type = NavType.LongType }),
                    enterTransition = { slideIntoContainer(SlideDirection.Start) },
                    popExitTransition = { slideOutOfContainer(SlideDirection.End) },
                ) {
                    RoutineEditorScreen(
                        onBack = { navController.popIfCurrent(ROUTINE_EDITOR_ROUTE) },
                    )
                }
                // Exceção pontual de uma ocorrência. O gatilho na timeline (long-press/menu) é plugado
                // pelo hub de rotinas; aqui fica só a rota + a tela (contrato de navegação).
                composable(
                    route = ROUTINE_EXCEPTION_ROUTE,
                    arguments = listOf(
                        navArgument(ROUTINE_EXCEPTION_ROUTINE_ID_ARG) { type = NavType.LongType },
                        navArgument(ROUTINE_EXCEPTION_DAY_ARG) { type = NavType.LongType },
                    ),
                    enterTransition = { slideIntoContainer(SlideDirection.Start) },
                    popExitTransition = { slideOutOfContainer(SlideDirection.End) },
                ) {
                    RoutineExceptionScreen(onBack = { navController.popIfCurrent(ROUTINE_EXCEPTION_ROUTE) })
                }
                composable(
                    route = TASK_DETAIL_ROUTE,
                    arguments = listOf(navArgument(TASK_ID_ARG) { type = NavType.LongType }),
                    enterTransition = { slideIntoContainer(SlideDirection.Start) },
                    popExitTransition = { slideOutOfContainer(SlideDirection.End) },
                ) {
                    TaskDetailScreen(
                        onBack = { navController.popIfCurrent(TASK_DETAIL_ROUTE) },
                        // Abre a lista com a tarefa como origem (oferta "Concluir tarefa", P3).
                        onOpenShoppingList = { listId, taskId -> navController.navigate(shoppingListRoute(listId, taskId)) },
                    )
                }
                composable(
                    route = SETTINGS_ROUTE,
                    enterTransition = { slideIntoContainer(SlideDirection.Start) },
                    popExitTransition = { slideOutOfContainer(SlideDirection.End) },
                ) {
                    SettingsScreen(
                        onBack = { navController.popIfCurrent(SETTINGS_ROUTE) },
                        onOpenDiagnostics = openDiagnostics,
                    )
                }
                composable(
                    route = ALARM_DIAGNOSTICS_ROUTE,
                    enterTransition = { slideIntoContainer(SlideDirection.Start) },
                    popExitTransition = { slideOutOfContainer(SlideDirection.End) },
                ) {
                    AlarmDiagnosticsScreen(onBack = { navController.popIfCurrent(ALARM_DIAGNOSTICS_ROUTE) })
                }
            }
        }
    }

    // Por cima de qualquer destino: o painel é janela própria e o voltar fecha primeiro ele.
    NewTaskHost(newTaskPanel, newTaskEpochDay?.let(LocalDate::ofEpochDay), onClose = { newTaskEpochDay = null })

    // Depois do NavHost, para o grafo já estar montado quando a navegação acontecer.
    LaunchedEffect(launchRequest) {
        when (launchRequest) {
            null -> return@LaunchedEffect
            is LaunchRequest.OpenTask -> openTask(launchRequest.taskId)
            // Widget e atalho: o painel abre POR CIMA do que estiver na tela, sem trocar de aba (O-2 da
            // v1.7.0); com o app frio, sobre a aba inicial (Hoje).
            LaunchRequest.NewTask -> openNewTask(clock.today())
            // Se o editor de rotina estava aberto, a aba volta com ele (restoreState) e o dia
            // pedido só vale ao sair do editor.
            LaunchRequest.OpenCalendar -> navController.openTab(AppTab.Calendar)
            is LaunchRequest.OpenRoutines -> {
                navController.openTab(AppTab.Today)
                routinesDateRequested = launchRequest.date ?: clock.today()
            }
            // v1.10.0 (ADR-0002 16.10.4): a lista abre sobre o detalhe da tarefa DONA, e esse sobre a aba
            // Tarefas; voltar nunca sai do app. A dona vem do banco (o taskId do link é só a origem antiga).
            // O openTab restaura a pilha salva da aba: desempilha até ela antes de abrir. Lista que não
            // existe mais (ou sem dona) fica na aba Tarefas.
            is LaunchRequest.OpenList -> {
                navController.openTab(AppTab.Tasks)
                navController.popBackStack(AppTab.Tasks.route, inclusive = false)
                val owner = app.repository.ownerOfList(launchRequest.listId)
                listLinkStack(launchRequest.listId, owner?.id).forEach { navController.navigate(it) }
            }
            // Endereço da antiga aba Listas (atalho fixado, widget ou notificação antigos): a aba Tarefas.
            LaunchRequest.OpenLists -> {
                navController.openTab(AppTab.Tasks)
                navController.popBackStack(AppTab.Tasks.route, inclusive = false)
            }
        }
        onLaunchRequestHandled()
    }
}

/**
 * O que abre, por cima da aba Tarefas, para o link da lista [listId] (v1.10.0, ADR-0002 16.10.4): o
 * detalhe da tarefa dona e, sobre ele, a lista. Sem dona ([ownerTaskId] nulo: lista que não existe mais
 * ou sem tarefa), nada: fica a aba Tarefas. Voltar desempilha lista, detalhe e cai na aba, nunca fora
 * do app.
 */
internal fun listLinkStack(listId: Long, ownerTaskId: Long?): List<String> =
    if (ownerTaskId == null) emptyList() else listOf(taskDetailRoute(ownerTaskId), shoppingListRoute(listId, ownerTaskId))

private const val SETTINGS_ROUTE = "settings"
private const val ALARM_DIAGNOSTICS_ROUTE = "alarm-diagnostics"

/** Evita desempilhar duas vezes com toques repetidos em "voltar" durante a animação. */
private fun NavHostController.popIfCurrent(route: String) {
    if (currentDestination?.route == route) popBackStack()
}

private fun NavHostController.openTab(tab: AppTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Rótulo da aba sempre numa linha e inteiro. Na fonte do sistema 1.0 todos cabem e nada muda; com
 * fonte grande, um rótulo mais largo que o espaço da aba (ex.: "Calendário" em 2.0) é reduzido só
 * o bastante para caber, em vez de quebrar em duas linhas ou ser cortado.
 */
@Composable
private fun TabLabel(text: String) {
    BoxWithConstraints {
        val style = LocalTextStyle.current
        val measurer = rememberTextMeasurer(cacheSize = 1)
        val density = LocalDensity.current
        val maxWidthPx = constraints.maxWidth
        val fitted = remember(text, style, density, maxWidthPx) {
            val width = measurer.measure(text, style, maxLines = 1, softWrap = false).size.width
            if (constraints.hasBoundedWidth && width > maxWidthPx && width > 0) {
                style.copy(fontSize = style.fontSize * (maxWidthPx.toFloat() / width) * 0.98f)
            } else {
                style
            }
        }
        Text(text = text, style = fitted, maxLines = 1, softWrap = false)
    }
}
