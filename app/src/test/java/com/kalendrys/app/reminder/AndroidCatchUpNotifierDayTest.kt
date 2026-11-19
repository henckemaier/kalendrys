package com.kalendrys.app.reminder

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.reminder.ReminderActionReceiver.Companion.occurrenceDay
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * O notificador do aparelho converte o instante da OCORRÊNCIA no dia que vai nas ações (NB-E5-B da
 * revisão do Lince): a de ontem às 23:50, entregue depois da meia-noite, leva o dia de ontem.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AndroidCatchUpNotifierDayTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `ocorrencia de ontem 23h50 entregue hoje leva o dia de ontem em Concluir e Adiar`() = runTest {
        val yesterday = LocalDate.now().minusDays(1)
        val firedAt = yesterday.atTime(LocalTime.of(23, 50)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val task = Task(id = 7, title = "Remédio", repeat = ReminderRepeat.DAILY)

        AndroidCatchUpNotifier(app, ShoppingRepository(db)).showReminder(task, firedAt)

        val notification = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.single()
        val days = notification.actions.map { shadowOf(it.actionIntent).savedIntent.occurrenceDay() }
        assertEquals(listOf(yesterday, yesterday), days)
    }
}
