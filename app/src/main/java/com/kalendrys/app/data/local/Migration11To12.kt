package com.kalendrys.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kalendrys.app.data.local.entity.ShoppingItem
import com.kalendrys.app.data.local.entity.ShoppingList
import com.kalendrys.app.domain.ListOwnership
import com.kalendrys.app.domain.RoutineKind

/**
 * Banco 11 → 12 (v1.10.0, ADR-0002 16.10.3): rotina com tipo e sem lista; lista de UMA tarefa só.
 * Nenhuma tabela é recriada. Roda na transação do Room: falhou um passo, nada fica pela metade.
 *
 * Os passos 1 e 4 a 6 usam as regras ÚNICAS do [ListOwnership], as mesmas do restauro de backup.
 *
 * ATENÇÃO: o SQL daqui é FIXO no formato do banco 12 (`12.json`). Se uma entidade tocada aqui mudar
 * numa versão futura, esta migração continua produzindo o 12 e a seguinte parte dele: leia e grave só
 * as colunas que existiam no 11/12, nunca pela entidade de agora.
 */
object Migration11To12 : Migration(11, 12) {

    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Tipo da rotina, pela categoria (Exercício → Exercício; o resto → Nenhum).
        db.execSQL("ALTER TABLE `routines` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'NONE'")
        val categoryKinds = mutableMapOf<Long, String>()
        db.query("SELECT id, kind FROM routine_category").use { c ->
            while (c.moveToNext()) categoryKinds[c.getLong(0)] = c.getString(1)
        }
        // Lê tudo e fecha o cursor ANTES de gravar na mesma tabela (A2 da revisão da T1).
        val kinds = mutableMapOf<Long, RoutineKind>()
        db.query("SELECT id, categoryId FROM routines WHERE categoryId IS NOT NULL").use { c ->
            while (c.moveToNext()) kinds[c.getLong(0)] = ListOwnership.kindFromCategory(categoryKinds[c.getLong(1)])
        }
        for ((routineId, kind) in kinds) {
            if (kind != RoutineKind.NONE) {
                db.execSQL("UPDATE routines SET kind = ? WHERE id = ?", arrayOf<Any>(kind.name, routineId))
            }
        }

        // 2. Exercícios salvos.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `saved_exercise` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `sets` INTEGER NOT NULL, `reps` INTEGER NOT NULL, `load` TEXT, " +
                "`restSeconds` INTEGER, `createdAt` INTEGER NOT NULL)",
        )

        // 3. Rotina não tem lista: o vínculo sai de TODAS (nenhuma rotina é apagada).
        db.execSQL("UPDATE routines SET shoppingListId = NULL WHERE shoppingListId IS NOT NULL")

        // 4 a 6. Lista de uma tarefa só: compartilhada vira cópia, vínculo órfão sai, lista sem tarefa
        // é apagada com os itens, item sem lista também.
        val tasks = mutableListOf<ListOwnership.TaskLink>()
        db.query("SELECT id, createdAt, shoppingListId FROM tasks").use { c ->
            while (c.moveToNext()) {
                tasks += ListOwnership.TaskLink(c.getLong(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2))
            }
        }
        val lists = mutableListOf<ShoppingList>()
        db.query("SELECT id, name, createdAt, lastResetKey FROM shopping_list").use { c ->
            while (c.moveToNext()) {
                lists += ShoppingList(c.getLong(0), c.getString(1), c.getLong(2), if (c.isNull(3)) null else c.getString(3))
            }
        }
        val items = mutableListOf<ShoppingItem>()
        db.query(
            "SELECT id, listId, name, quantity, priceCents, picked, position, createdAt, pickedBeforeReset FROM shopping_item",
        ).use { c ->
            while (c.moveToNext()) {
                items += ShoppingItem(
                    id = c.getLong(0),
                    listId = c.getLong(1),
                    name = c.getString(2),
                    quantity = c.getInt(3),
                    priceCents = if (c.isNull(4)) null else c.getLong(4),
                    picked = c.getInt(5) != 0,
                    position = c.getInt(6),
                    createdAt = c.getLong(7),
                    pickedBeforeReset = c.getInt(8) != 0,
                )
            }
        }
        val plan = ListOwnership.plan(tasks, lists, items)
        for (list in plan.newLists) {
            db.execSQL(
                "INSERT INTO shopping_list (id, name, createdAt, lastResetKey) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>(list.id, list.name, list.createdAt, list.lastResetKey),
            )
        }
        for (item in plan.newItems) {
            db.execSQL(
                "INSERT INTO shopping_item (id, listId, name, quantity, priceCents, picked, position, createdAt, " +
                    "pickedBeforeReset) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    item.id, item.listId, item.name, item.quantity, item.priceCents,
                    if (item.picked) 1 else 0, item.position, item.createdAt, if (item.pickedBeforeReset) 1 else 0,
                ),
            )
        }
        for ((taskId, listId) in plan.taskLinks) {
            db.execSQL("UPDATE tasks SET shoppingListId = ? WHERE id = ?", arrayOf<Any?>(listId, taskId))
        }
        for (itemId in plan.deletedItemIds) db.execSQL("DELETE FROM shopping_item WHERE id = ?", arrayOf<Any?>(itemId))
        for (listId in plan.deletedListIds) db.execSQL("DELETE FROM shopping_list WHERE id = ?", arrayOf<Any?>(listId))

        // 7. Por último, com os dados já limpos: o banco recusa duas tarefas na mesma lista.
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tasks_shoppingListId` ON `tasks` (`shoppingListId`)")
    }
}
