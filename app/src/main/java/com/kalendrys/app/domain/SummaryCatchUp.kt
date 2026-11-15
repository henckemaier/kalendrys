package com.kalendrys.app.domain

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Plano do catch-up do resumo diário (ADR-0003, 4.3.2): o resumo deve sair agora? Lógica pura.
 *
 * A ocorrência é só o horário configurado de HOJE: sai se estiver em `(lastSeen, now]` e a no
 * máximo [toleranceMinutes] de [now]; fora da tolerância é descartada (sem agregado). O resumo de
 * ontem que só chegaria depois da meia-noite é descartado (decisão do usuário, opção A): mostraria o
 * dia novo com o título de ontem. A marca avança normalmente. Desligado ou relógio voltando
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
        val occurrence = today.atTime(time).atZone(zone).toInstant().toEpochMilli()
        return occurrence.takeIf { it > lastSeen && it <= now && now - it <= toleranceMinutes * 60_000L }
    }
}
