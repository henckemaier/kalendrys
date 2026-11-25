package com.kalendrys.app.testutil

/**
 * Carimbo de criação (e de conclusão de rotina) FIXO dos testes (v1.10.0, ADR-0002 16.3, A3 do
 * Esquadro): as entidades não têm mais `createdAt = System.currentTimeMillis()` por padrão, porque o
 * padrão era uma leitura de hoje escondida (o `Routine.createdAt` entra na regra de ocorrência). O
 * início da época: a rotina de teste ocorre em qualquer dia de teste, como já faziam os testes que
 * passavam `createdAt = 0L`. Teste que depende do dia de criação passa o seu.
 */
const val TEST_CREATED_AT: Long = 0L
