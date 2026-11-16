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
import org.xmlpull.v1.XmlPullParser

/**
 * Endereços `kalendrys://` do [AppLinks]: link da lista (ADR-0002, E-1,
 * `kalendrys://list/{listId}?taskId={id}`) e os dos atalhos do app (12.6).
 */
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

    @Test
    fun `enderecos dos atalhos viram NewTask e OpenLists`() {
        assertEquals(LaunchRequest.NewTask, parse("kalendrys://new-task"))
        assertEquals(LaunchRequest.OpenLists, parse("kalendrys://lists"))

        val lists = AppLinks.openLists(context)
        assertEquals("kalendrys://lists", lists.data.toString())
        assertEquals(LaunchRequest.OpenLists, AppLinks.parse(lists))
    }

    @Test
    fun `enderecos invalidos continuam null`() {
        assertNull(parse("kalendrys://lista"))
        assertNull(parse("kalendrys://new_task"))
        assertNull(parse("kalendrys://"))
        assertNull(parse("outro://lists"))
        assertNull(parse("https://kalendrys/new-task"))
        assertNull(AppLinks.parse(Intent(Intent.ACTION_VIEW)))
        assertNull(AppLinks.parse(null))
    }

    /**
     * A MainActivity é exportada (lançador e atalhos): qualquer app pode mandar um intent explícito
     * com um `kalendrys://`. Os endereços das ações de notificação (concluir, pular, adiar) existem só
     * para dar unicidade aos PendingIntents e nunca podem virar pedido de navegação, muito menos de
     * alteração de dados.
     */
    @Test
    fun `enderecos das acoes de notificacao nunca viram pedido de abertura`() {
        val actions = listOf(
            AppLinks.reminderComplete(7),
            AppLinks.reminderSnooze(7),
            AppLinks.reminderSnoozeFire(7),
            AppLinks.routineOccurrence(3, 20_000),
            AppLinks.routineSkip(3, 20_000),
            AppLinks.routineSnooze(3, 20_000),
            AppLinks.routineSnoozeFire(3, 20_000),
        )

        actions.forEach { uri ->
            assertNull("$uri não pode abrir nada", AppLinks.parse(Intent(Intent.ACTION_VIEW, uri)))
            assertNull("$uri pela MainActivity", AppLinks.parse(Intent(context, MainActivity::class.java).setData(uri)))
        }
    }

    /**
     * Lê o `res/xml/shortcuts.xml` de verdade: cada atalho aponta para a MainActivity deste pacote
     * com um endereço que o [AppLinks.parse] reconhece. Pega erro de digitação no XML (que o
     * build não confere) e classe renomeada sem atualizar o targetClass.
     */
    @Test
    fun `atalhos do shortcuts xml abrem a MainActivity no destino certo`() {
        val shortcuts = mutableMapOf<String, Intent>()
        var shortcutId: String? = null
        val xml = context.resources.getXml(R.xml.shortcuts)
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            if (xml.eventType != XmlPullParser.START_TAG) continue
            when (xml.name) {
                "shortcut" -> shortcutId = xml.getAttributeValue(ANDROID_NS, "shortcutId")
                "intent" -> shortcuts[checkNotNull(shortcutId)] = Intent(xml.getAttributeValue(ANDROID_NS, "action"))
                    .setData(Uri.parse(xml.getAttributeValue(ANDROID_NS, "data")))
                    .setClassName(
                        xml.getAttributeValue(ANDROID_NS, "targetPackage"),
                        xml.getAttributeValue(ANDROID_NS, "targetClass"),
                    )
            }
        }
        xml.close()

        assertEquals(setOf("new_task", "lists"), shortcuts.keys)
        shortcuts.values.forEach { intent ->
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(context.packageName, intent.component?.packageName)
            assertEquals(MainActivity::class.java.name, intent.component?.className)
        }
        assertEquals(LaunchRequest.NewTask, AppLinks.parse(shortcuts.getValue("new_task")))
        assertEquals(LaunchRequest.OpenLists, AppLinks.parse(shortcuts.getValue("lists")))
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
