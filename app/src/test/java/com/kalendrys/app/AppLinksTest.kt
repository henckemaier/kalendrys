package com.kalendrys.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Link interno para a lista (ADR-0002, E-1): `kalendrys://list/{listId}?taskId={id}`. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppLinksTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun parse(uri: String) = AppLinks.parse(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))

    @Test
    fun `openList sem tarefa vira OpenList sem taskId`() {
        val intent = AppLinks.openList(context, listId = 7)
        assertEquals("kalendrys://list/7", intent.data.toString())
        assertEquals(LaunchRequest.OpenList(listId = 7, taskId = null), AppLinks.parse(intent))
    }

    @Test
    fun `openList com tarefa leva o taskId de origem`() {
        val intent = AppLinks.openList(context, listId = 7, taskId = 3)
        assertEquals("kalendrys://list/7?taskId=3", intent.data.toString())
        assertEquals(LaunchRequest.OpenList(listId = 7, taskId = 3), AppLinks.parse(intent))
    }

    @Test
    fun `listId invalido nao abre nada e taskId invalido e ignorado`() {
        assertNull(parse("kalendrys://list/abc"))
        assertNull(parse("kalendrys://list"))
        assertEquals(LaunchRequest.OpenList(listId = 7, taskId = null), parse("kalendrys://list/7?taskId=x"))
    }

    @Test
    fun `links antigos continuam iguais`() {
        assertEquals(LaunchRequest.OpenTask(5), AppLinks.parse(AppLinks.openTask(context, 5)))
        assertEquals(LaunchRequest.NewTask, AppLinks.parse(AppLinks.newTask(context)))
        assertEquals(LaunchRequest.OpenCalendar, AppLinks.parse(AppLinks.openCalendar(context)))
        assertNull(parse("outro://list/7"))
    }
}
