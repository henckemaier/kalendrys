package com.kalendrys.app.testutil

import com.kalendrys.app.data.repository.RoutineCatchUpMark

/** Marca do catch-up de rotinas em memória, para os testes. Começa ausente, como logo após atualizar. */
class InMemoryRoutineCatchUpMark(private var value: Long? = null) : RoutineCatchUpMark {
    override suspend fun get(): Long? = value

    override suspend fun set(millis: Long) {
        value = millis
    }
}
