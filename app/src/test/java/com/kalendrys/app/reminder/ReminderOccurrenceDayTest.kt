package com.kalendrys.app.reminder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.AppLinks
import com.kalendrys.app.reminder.ReminderActionReceiver.Companion.occurrenceDay
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A notificação de tarefa sabe de qual ocorrência é (NB-E5-A da v1.8.0): o dia vai nas ações Concluir
 * e Adiar, e o alarme do adiamento o leva até a reexibição. Sem o dia (notificação antiga), vale hoje.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReminderOccurrenceDayTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val yesterday = LocalDate.now().minusDays(1)

    @Test
    fun `Concluir e Adiar levam o dia da ocorrencia`() {
        val complete = ReminderNotifications.actionIntent(
            context, ReminderActionReceiver.ACTION_COMPLETE, 7, AppLinks.reminderComplete(7), yesterday,
        )
        val snooze = ReminderNotifications.actionIntent(
            context, ReminderActionReceiver.ACTION_SNOOZE, 7, AppLinks.reminderSnooze(7), yesterday,
        )

        assertEquals(yesterday, complete.occurrenceDay())
        assertEquals(yesterday, snooze.occurrenceDay())
        assertEquals(7L, complete.getLongExtra(ReminderActionReceiver.EXTRA_TASK_ID, -1))
    }

    @Test
    fun `adiar e o alarme do adiamento mantem o MESMO dia ate a reexibicao`() {
        val snooze = ReminderNotifications.actionIntent(
            context, ReminderActionReceiver.ACTION_SNOOZE, 7, AppLinks.reminderSnooze(7), yesterday,
        )

        // O receiver do Adiar passa o dia lido para o alarme; quando ele toca, a reexibição lê o mesmo dia.
        val fire = ReminderActionReceiver.snoozeFireIntent(context, 7, snooze.occurrenceDay())

        assertEquals(ReminderActionReceiver.ACTION_SNOOZE_FIRE, fire.action)
        assertEquals(yesterday, fire.occurrenceDay())
    }

    @Test
    fun `notificacao sem o dia continua sem o dia`() {
        val complete = ReminderNotifications.actionIntent(
            context, ReminderActionReceiver.ACTION_COMPLETE, 7, AppLinks.reminderComplete(7), null,
        )

        assertNull(complete.occurrenceDay())
        assertNull(ReminderActionReceiver.snoozeFireIntent(context, 7, null).occurrenceDay())
    }
}
