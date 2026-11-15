package com.kalendrys.app.testutil

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/**
 * `openFactory` do `MigrationTestHelper` para os testes de migração rodarem no Windows.
 *
 * Causa: no Room 2.8.x o helper abre o banco pelo `SupportSQLiteDriver` do androidx.sqlite 2.6.2
 * (o 2.7.1 tem o mesmo código). O `open(fileName)` faz um `require` de que o nome do open helper
 * seja igual ao `fileName` ou que ambos terminem igual depois do último `'/'`. O Room passa
 * `context.getDatabasePath(nome).absolutePath`, que no Windows + Robolectric usa `'\'`, então a
 * comparação falha com "This driver is configured to open a database named ...". No Linux (CI) o
 * sufixo bate e os testes passariam sem isto.
 *
 * O ajuste só faz o open helper informar como nome o mesmo caminho absoluto que o Room vai pedir.
 * O banco continua sendo aberto pelo [FrameworkSQLiteOpenHelperFactory] com o nome original, então
 * nada muda no que os testes exercitam.
 */
class AbsolutePathOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val helper = delegate.create(configuration)
        val absoluteName = configuration.name?.let { configuration.context.getDatabasePath(it).absolutePath }
        return object : SupportSQLiteOpenHelper by helper {
            override val databaseName: String? get() = absoluteName
        }
    }
}
