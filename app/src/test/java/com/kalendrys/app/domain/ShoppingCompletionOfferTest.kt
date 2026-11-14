package com.kalendrys.app.domain

import com.kalendrys.app.domain.ShoppingCompletionOffer.LinkedTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShoppingCompletionOfferTest {

    private val complete = ShoppingProgress(picked = 3, total = 3)

    @Test
    fun `tarefa de origem pendente recebe a oferta`() =
        assertEquals(2L, ShoppingCompletionOffer.candidate(complete, originTaskId = 2, linkedTasks = listOf(LinkedTask(1, true), LinkedTask(2, true))))

    @Test
    fun `sem origem a unica pendente recebe a oferta`() =
        assertEquals(1L, ShoppingCompletionOffer.candidate(complete, originTaskId = null, linkedTasks = listOf(LinkedTask(1, true), LinkedTask(2, false))))

    @Test
    fun `nenhuma pendente nao tem oferta`() =
        assertNull(ShoppingCompletionOffer.candidate(complete, originTaskId = null, linkedTasks = listOf(LinkedTask(1, false))))

    @Test
    fun `mais de uma pendente sem origem nao tem oferta`() =
        assertNull(ShoppingCompletionOffer.candidate(complete, originTaskId = null, linkedTasks = listOf(LinkedTask(1, true), LinkedTask(2, true))))

    @Test
    fun `lista vazia nao tem oferta`() =
        assertNull(ShoppingCompletionOffer.candidate(ShoppingProgress.EMPTY, originTaskId = 1, linkedTasks = listOf(LinkedTask(1, true))))

    @Test
    fun `lista com item desmarcado nao tem oferta`() =
        assertNull(ShoppingCompletionOffer.candidate(ShoppingProgress(2, 3), originTaskId = 1, linkedTasks = listOf(LinkedTask(1, true))))

    @Test
    fun `tarefa de origem ja concluida cai na unica pendente`() {
        assertEquals(2L, ShoppingCompletionOffer.candidate(complete, originTaskId = 1, linkedTasks = listOf(LinkedTask(1, false), LinkedTask(2, true))))
        assertNull(ShoppingCompletionOffer.candidate(complete, originTaskId = 1, linkedTasks = listOf(LinkedTask(1, false))))
    }

    // --- NB-2 (decisão do usuário): tarefa ÚNICA com data futura conta como pendente ---

    private val singleTomorrow = ShoppingCompletionOffer.isPending(TaskSchedule.Status.UPCOMING, ReminderRepeat.NONE)
    private val repeatedDoneToday = ShoppingCompletionOffer.isPending(TaskSchedule.Status.DONE_TODAY, ReminderRepeat.DAILY)

    @Test
    fun `regra de pendente`() {
        assertEquals(true, singleTomorrow)
        assertEquals(false, repeatedDoneToday)
        assertEquals(false, ShoppingCompletionOffer.isPending(TaskSchedule.Status.UPCOMING, ReminderRepeat.WEEKLY))
        assertEquals(true, ShoppingCompletionOffer.isPending(TaskSchedule.Status.TODAY, ReminderRepeat.DAILY))
        assertEquals(true, ShoppingCompletionOffer.isPending(TaskSchedule.Status.OVERDUE, ReminderRepeat.NONE))
        assertEquals(true, ShoppingCompletionOffer.isPending(TaskSchedule.Status.NO_DATE, ReminderRepeat.NONE))
    }

    @Test
    fun `tarefa unica de amanha vinda do detalhe recebe a oferta`() =
        assertEquals(4L, ShoppingCompletionOffer.candidate(complete, originTaskId = 4, linkedTasks = listOf(LinkedTask(4, singleTomorrow))))

    @Test
    fun `tarefa unica de amanha como unica vinculada recebe a oferta`() =
        assertEquals(4L, ShoppingCompletionOffer.candidate(complete, originTaskId = null, linkedTasks = listOf(LinkedTask(4, singleTomorrow))))

    @Test
    fun `tarefa unica futura mais outra pendente sem origem nao tem oferta`() =
        assertNull(
            ShoppingCompletionOffer.candidate(
                complete,
                originTaskId = null,
                linkedTasks = listOf(LinkedTask(4, singleTomorrow), LinkedTask(5, pending = true)),
            ),
        )

    @Test
    fun `repetida concluida hoje fica fora`() =
        assertEquals(
            4L,
            ShoppingCompletionOffer.candidate(
                complete,
                originTaskId = 5,
                linkedTasks = listOf(LinkedTask(4, singleTomorrow), LinkedTask(5, repeatedDoneToday)),
            ),
        )

    @Test
    fun `repetida futura fica fora`() =
        assertNull(
            ShoppingCompletionOffer.candidate(
                complete,
                originTaskId = 6,
                linkedTasks = listOf(LinkedTask(6, ShoppingCompletionOffer.isPending(TaskSchedule.Status.UPCOMING, ReminderRepeat.DAILY))),
            ),
        )

    @Test
    fun `unica ja concluida fica fora mesmo com data futura`() {
        // Concluída, a tarefa única está em DONE_TODAY ou DONE (nunca UPCOMING): não é candidata.
        assertEquals(false, ShoppingCompletionOffer.isPending(TaskSchedule.Status.DONE_TODAY, ReminderRepeat.NONE))
        assertEquals(false, ShoppingCompletionOffer.isPending(TaskSchedule.Status.DONE, ReminderRepeat.NONE))
    }
}
