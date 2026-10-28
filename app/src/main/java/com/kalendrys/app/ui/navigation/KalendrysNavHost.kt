package com.kalendrys.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kalendrys.app.LaunchRequest
import com.kalendrys.app.R
import com.kalendrys.app.ui.calendar.CalendarScreen
import com.kalendrys.app.ui.routineeditor.ROUTINE_EDITOR_ROUTE
import com.kalendrys.app.ui.routineeditor.ROUTINE_ID_ARG
import com.kalendrys.app.ui.routineeditor.RoutineEditorScreen
import com.kalendrys.app.ui.routineeditor.routineEditorRoute
import com.kalendrys.app.ui.routines.RoutinesScreen
import com.kalendrys.app.ui.settings.SettingsScreen
import com.kalendrys.app.ui.taskdetail.TASK_DETAIL_ROUTE
import com.kalendrys.app.ui.taskdetail.TASK_ID_ARG
import com.kalendrys.app.ui.taskdetail.TaskDetailScreen
import com.kalendrys.app.ui.taskdetail.taskDetailRoute
import com.kalendrys.app.ui.tasks.TaskListScreen
import java.time.LocalDate

enum class AppTab(
    val route: String,
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    Tasks("tasks", R.string.tab_tasks, Icons.Filled.TaskAlt, Icons.Outlined.TaskAlt),
    Routines("routines", R.string.tab_routines, Icons.Filled.EventRepeat, Icons.Outlined.EventRepeat),
    Calendar("calendar", R.string.tab_calendar, Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
}

@Composable
fun KalendrysNavHost(
    launchRequest: LaunchRequest? = null,
    onLaunchRequestHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute == null || AppTab.entries.any { it.route == currentRoute }
    val openTask: (Long) -> Unit = { taskId -> navController.navigate(taskDetailRoute(taskId)) }
    val openSettings: () -> Unit = { navController.navigate(SETTINGS_ROUTE) { launchSingleTop = true } }
    val openRoutine: (Long) -> Unit = { routineId -> navController.navigate(routineEditorRoute(routineId)) }
    // Pedido de "nova tarefa" vindo do widget, entregue à tela de tarefas uma única vez.
    var newTaskRequested by rememberSaveable { mutableStateOf(false) }
    // Dia pedido por uma notificação ou widget de rotina, entregue à aba de rotinas uma única vez.
    var routinesDateRequested by rememberSaveable { mutableStateOf<LocalDate?>(null) }

    Scaffold(
        // Cada tela tem a própria barra de título, que desenha atrás da barra de status.
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
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppTab.Tasks.route,
            // Sem consumir, o Scaffold de cada tela aplicaria de novo o inset da barra de navegação.
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            composable(AppTab.Tasks.route) {
                TaskListScreen(
                    onOpenTask = openTask,
                    onOpenSettings = openSettings,
                    openNewTask = newTaskRequested,
                    onNewTaskOpened = { newTaskRequested = false },
                )
            }
            composable(AppTab.Routines.route) {
                RoutinesScreen(
                    onOpenRoutine = openRoutine,
                    onNewRoutine = { navController.navigate(routineEditorRoute()) },
                    onOpenSettings = openSettings,
                    requestedDate = routinesDateRequested,
                    onRequestedDateHandled = { routinesDateRequested = null },
                )
            }
            composable(AppTab.Calendar.route) {
                CalendarScreen(onOpenTask = openTask, onOpenRoutine = openRoutine, onOpenSettings = openSettings)
            }
            composable(
                route = ROUTINE_EDITOR_ROUTE,
                arguments = listOf(navArgument(ROUTINE_ID_ARG) { type = NavType.LongType }),
                enterTransition = { slideIntoContainer(SlideDirection.Start) },
                popExitTransition = { slideOutOfContainer(SlideDirection.End) },
            ) {
                RoutineEditorScreen(onBack = { navController.popIfCurrent(ROUTINE_EDITOR_ROUTE) })
            }
            composable(
                route = TASK_DETAIL_ROUTE,
                arguments = listOf(navArgument(TASK_ID_ARG) { type = NavType.LongType }),
                enterTransition = { slideIntoContainer(SlideDirection.Start) },
                popExitTransition = { slideOutOfContainer(SlideDirection.End) },
            ) {
                TaskDetailScreen(onBack = { navController.popIfCurrent(TASK_DETAIL_ROUTE) })
            }
            composable(
                route = SETTINGS_ROUTE,
                enterTransition = { slideIntoContainer(SlideDirection.Start) },
                popExitTransition = { slideOutOfContainer(SlideDirection.End) },
            ) {
                SettingsScreen(onBack = { navController.popIfCurrent(SETTINGS_ROUTE) })
            }
        }
    }

    // Depois do NavHost, para o grafo já estar montado quando a navegação acontecer.
    LaunchedEffect(launchRequest) {
        when (launchRequest) {
            null -> return@LaunchedEffect
            is LaunchRequest.OpenTask -> openTask(launchRequest.taskId)
            LaunchRequest.NewTask -> {
                navController.openTab(AppTab.Tasks)
                newTaskRequested = true
            }
            // Se o editor de rotina estava aberto, a aba volta com ele (restoreState) e o dia
            // pedido só vale ao sair do editor.
            LaunchRequest.OpenCalendar -> navController.openTab(AppTab.Calendar)
            is LaunchRequest.OpenRoutines -> {
                navController.openTab(AppTab.Routines)
                routinesDateRequested = launchRequest.date
            }
        }
        onLaunchRequestHandled()
    }
}

private const val SETTINGS_ROUTE = "settings"

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
