package com.kalendrys.app.testutil

import android.app.Application

/**
 * Application usada pelos testes Robolectric no lugar da `KalendrysApp` real.
 *
 * Por quê: o `onCreate` da `KalendrysApp` dispara, num escopo externo
 * (`CoroutineScope(SupervisorJob() + Dispatchers.Default)`), três corrotinas de longa duração —
 * reagendar os lembretes e dois `collect` infinitos (configurações e `TodayRepository.observe()`),
 * que tocam o Room. Sob Robolectric cada teste que pega o `applicationContext` instancia a
 * Application real e roda esse `onCreate`; o trabalho em background sobrevive ao fim do teste e,
 * quando o banco é fechado no teardown, estoura `IllegalStateException: Illegal connection pointer`.
 * A exceção escapa para o handler padrão e reaparece no início do PRÓXIMO teste como
 * `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest`, deixando a suíte flaky (a vítima alterna
 * conforme a ordem/tempo de execução).
 *
 * O leak é da Application, não do corpo dos testes, então nenhum `advanceUntilIdle`/cancel dentro
 * de um teste o alcança. Não é bug de produção: em runtime o banco vive o processo inteiro e o
 * escopo nunca é cancelado sob um banco fechado. Os testes só precisam de um `Context` (constroem
 * seus próprios repositórios), então uma Application mínima basta e não roda bootstrap algum.
 */
class TestKalendrysApp : Application()
