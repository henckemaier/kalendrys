package com.kalendrys.app.domain

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Plano do catch-up do resumo diário (ADR-0003, 4.3.2): o resumo deve sair agora? Lógica pura.
 *
 * A ocorrência é o horário configurado de hoje, ou de ontem quando a janela cruza a meia-noite.
 * Sai a mais recente que estiver em `(lastSeen, now]` e a no máximo [toleranceMinutes] de
 * [now]; fora da tolerância é descartada (sem agregado). Desligado ou relógio voltando
 * (`now <= lastSeen`): nada. É uma janela, não uma chave de data: trocar o horário nas
 * Configurações grava a marca e o novo horário só sai quando chegar.
 */
object SummaryCatchUp {

    /** Instante da ocorrência a entregar agora, ou `null`. */
    fun due(
        enabled: Boolean,
        time: LocalTime,
        lastSeen: Long,
        now: Long,
        zone: ZoneId,
        toleranceMinutes: Long = ReminderCatchUp.LATE_TOLERANCE_MINUTES,
    ): Long? {
        if (!enabled || now <= lastSeen) return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return listOf(today, today.minusDays(1))
            .map { date -> date.atTime(time).atZone(zone).toInstant().toEpochMilli() }
            .firstOrNull { occurrence -> occurrence > lastSeen && occurrence <= now }
            ?.takeIf { occurrence -> now - occurrence <= toleranceMinutes * 60_000L }
    }
}
