package com.kalendrys.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Percorre os caminhos mais usados do app; o código executado vira o Baseline Profile
 * (app/src/release/generated/baselineProfiles), que o Android compila antecipadamente.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE_NAME, includeInStartupProfile = true) {
        // O diálogo de permissão de notificação cobre o app e trava as jornadas (Android 13+).
        device.executeShellCommand("pm grant $PACKAGE_NAME android.permission.POST_NOTIFICATIONS")
        pressHome()
        startActivityAndWait()
        waitForTasksScreen()
        ensureSampleTasks()
        browseTasks()
        browseRoutines()
        browseLists()
        browseCalendar()
        browseSettings()
    }
}
