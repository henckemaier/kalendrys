# ADR-0001 — Listas de compras independentes, com vínculo opcional em rotina e tarefa

- **Status:** aceito (aprovado pelo usuário em 2026-11-13)
- **Autor:** Esquadro (arquiteto)
- **Base:** `main` em v1.4.1, Room v9, backup `schemaVersion` 5
- **Entrega:** v1.5.0 (versionCode 10500) e v1.6.0 (versionCode 10600)

## 1. Contexto

Hoje a lista de compras não existe como entidade. Existe só `shopping_item`, preso a um
`routineId`, e a lista só aparece dentro do editor de rotina quando a categoria da rotina é do
tipo `COMPRAS`. Consequências:

- Para usar a lista no mercado, o usuário abre o **editor** da rotina.
- Não há lista fora de rotina nem em tarefa.
- As marcações (`picked`) nunca voltam a zero: na compra seguinte os itens continuam marcados.
- Trocar a categoria da rotina esconde a lista (os itens ficam no banco, invisíveis).
- O `RoutineEditorViewModel` carrega um snapshot dos itens só para o "Descartar alterações".

O pedido: usar a lista fora das rotinas e também dentro de uma tarefa (com lembrete e conclusão).

## 2. Decisão

**Alternativa B + E1** do parecer:

- **B:** a lista vira entidade própria (`shopping_list`). O item aponta para a lista. O vínculo
  fica do lado do dono: `tasks.shoppingListId` e `routines.shoppingListId`, ambos opcionais e
  sem foreign key. Lista sem dono é uma lista avulsa.
- **E1:** quarta aba na barra inferior, chamada **Listas**.

Alternativas rejeitadas: dono direto no item (A, só comporta uma lista avulsa), checklist
genérico (C, reescreve Exercícios sem demanda) e tarefa do tipo lista (D, lista permanente
ficaria pendente todo dia ou sumiria ao concluir).

Exercícios não mudam: continuam só dentro de rotinas, com o gate por categoria `EXERCICIO`.

## 3. Regras de produto

| # | Regra | Versão |
|---|-------|--------|
| P1 | Entrada pela 4ª aba **Listas** | v1.5.0 |
| P2 | Uma lista pode ter mais de um dono (rotinas e tarefas). A UI oferece "Criar nova" ou "Usar lista existente" | v1.5.0 (rotina), v1.6.0 (tarefa) |
| P3 | Concluir tarefa com itens não marcados é permitido, sem aviso. Ao marcar o último item de uma lista ligada a tarefa, oferecer "Concluir tarefa" em snackbar, sem forçar | v1.6.0 |
| P4 | Ao concluir uma tarefa repetida ou um bloco de rotina dono da lista, as marcações voltam a zero | v1.5.0 (bloco de rotina), v1.6.0 (tarefa repetida) |
| P5 | Excluir a tarefa ou arquivar a rotina não exclui a lista: ela segue na aba, sem o vínculo | v1.5.0 (rotina), v1.6.0 (tarefa) |
| P6 | Qualquer rotina pode anexar uma lista; a categoria `COMPRAS` deixa de ser exigida. Listas existentes são migradas | v1.5.0 |
| P7 | "Descartar" no editor de rotina não reverte edições de itens | v1.5.0 |
| P8 | Progresso (3/8) no widget Hoje, no resumo diário e na notificação | v1.6.0 |
| P9 | Lista avulsa não tem lembrete próprio. Quem quer lembrete anexa a lista a uma tarefa | — |
| P10 | Exercícios continuam só dentro de rotinas | — |

Detalhamento das regras que têm casos de borda:

**P2, compartilhamento.** Cada dono tem no máximo uma lista. A lista é uma só: marcar um item
vale para todos os donos, e o reinício (P4) disparado por um dono zera a lista para todos.

**P3, oferta de conclusão.** A oferta aparece quando uma marcação feita na tela da lista deixa
todos os itens marcados (lista não vazia). A tarefa candidata é:
1. a tarefa de origem, se a tela foi aberta a partir do detalhe de uma tarefa e ela está pendente;
2. senão, a única tarefa pendente vinculada à lista. Com zero ou mais de uma, não há oferta.

Rotinas não recebem oferta. A conclusão pela oferta é uma conclusão comum (com "Desfazer").

**P4, reinício das marcações.**
- Tarefa: reinicia só se a tarefa se repete (`repeat != NONE`). Tarefa única concluída mantém as
  marcações.
- Bloco de rotina: reinicia só quando a ocorrência concluída é a de **hoje**. Marcar como feito
  um dia passado (correção de histórico) não mexe na lista.
- Vale para todos os caminhos de conclusão, inclusive os sem UI (widget Hoje e ação "Concluir"
  das notificações), por isso a regra mora no repositório, não na tela.
- **Desfazer a conclusão restaura as marcações** que o reinício apagou (ver seção 4.4). Sem isso,
  um toque acidental em "Concluir" no meio da compra perderia todas as marcações.
- A tela da lista ganha a ação manual "Desmarcar todos", para listas avulsas e tarefas únicas.

**P5, vínculo de rotina arquivada.** Arquivar não apaga `routines.shoppingListId`: o vínculo de
rotina arquivada é apenas ignorado na leitura (etiqueta de dono, reinício). Assim nada se perde
se o arquivamento for revertido. Excluir tarefa apaga a linha, e o "Desfazer" da exclusão
(`restoreTask`) traz o vínculo de volta.

**P6, categoria `COMPRAS`.** O valor do enum continua existindo (é persistido e está em backups),
mas deixa de controlar a lista. Fica como rótulo de categoria. Decidir se a opção some do seletor
de tipo de categoria é assunto futuro, fora deste ADR.

**P7, descartar no editor.** O **vínculo** (`shoppingListId`) faz parte do rascunho da rotina:
é salvo com "Salvar" e revertido com "Descartar". Os **itens** são da lista e persistem na hora.
Uma lista criada pelo editor e depois descartada continua existindo como lista avulsa.

## 4. Desenho

### 4.1 Entidades (Room v10)

```
shopping_list                      shopping_item
  id            PK autoincrement     id                PK autoincrement
  name          TEXT NOT NULL        listId            INTEGER NOT NULL (índice)
  createdAt     INTEGER NOT NULL     name, quantity, priceCents, picked, position, createdAt
  lastResetKey  TEXT NULL            pickedBeforeReset INTEGER NOT NULL DEFAULT 0

tasks.shoppingListId     INTEGER NULL DEFAULT NULL
routines.shoppingListId  INTEGER NULL DEFAULT NULL
```

- Sem foreign key, como o resto do banco. **Invariante de leitura:** um `shoppingListId` que
  aponta para lista inexistente equivale a "sem lista". Todo leitor tolera isso.
- `shopping_item.routineId` deixa de existir.
- `tasks.shoppingListId` entra já na v10, sem uso até a v1.6.0, para haver uma migração só
  (precedente: a v7 foi fundação com colunas usadas depois).
- `lastResetKey` e `pickedBeforeReset` sustentam o reinício reversível (seção 4.4).
- A tabela continua tipada como lista de compras. O nome genérico "Listas" é só da aba; outro
  tipo de lista no futuro será decisão nova.

### 4.2 Migração 9 → 10

É uma `Migration` **manual**, a primeira desde a v3, porque há backfill e troca de coluna. O
resultado precisa bater com `app/schemas/10.json`. Passos, na mesma transação:

1. Criar `shopping_list`.
2. `ALTER TABLE` em `routines` e `tasks` adicionando `shoppingListId`.
3. Para cada `routineId` distinto em `shopping_item`, **em ordem crescente de `routineId`**,
   criar uma lista:
   - nome = título da rotina; se a rotina não existir, `Lista de compras`;
   - `createdAt` = menor `createdAt` dos itens daquela rotina;
   - se a rotina existir, preencher `routines.shoppingListId` (inclusive em rotina arquivada).
4. Recriar `shopping_item` com `listId` no lugar de `routineId` e `pickedBeforeReset = 0`,
   copiando `id, name, quantity, priceCents, picked, position, createdAt`. Recriar o índice
   (`index_shopping_item_listId`).

Garantias: nenhum item se perde, os ids dos itens são preservados, as marcações são mantidas.

Efeitos visíveis e esperados:
- Itens de rotinas que saíram da categoria `COMPRAS` (hoje ocultos) **reaparecem** como lista
  vinculada à rotina. É consequência direta da P6.
- Itens de rotina inexistente viram lista avulsa.
- Rotina `COMPRAS` sem nenhum item não ganha lista vazia; o editor mostra "Adicionar lista".

### 4.3 Camadas

**`data/local`**
- `ShoppingListDao` (novo): observar todas, por id, inserir, renomear, excluir.
- `ShoppingItemDao`: passa a operar por `listId`. Ganha:
  - progresso agregado por lista (`listId, total, picked`) como `Flow`;
  - `resetPicked(listId, key)` e `restoreReset(key)`, cada um em `@Transaction`;
  - `clearPicked(listId)` para o "Desmarcar todos".
  - Saem `deleteByRoutine` e `replaceForRoutine`.
- `TaskDao` / `RoutineDao`: consulta dos donos de listas (`id, title, shoppingListId`; rotinas
  só com `archivedAt IS NULL`) e o desvínculo em lote por `shoppingListId`.

**`data/repository`**
- `ShoppingRepository(database)`: fonte única de listas e itens.
  - Listas: criar, renomear, excluir. Excluir apaga os itens e zera o `shoppingListId` de
    tarefas e rotinas na mesma transação (padrão do `RoutineCategoryRepository.delete`).
  - Itens: observar, adicionar, editar, marcar, remover, reinserir (desfazer remoção),
    desmarcar todos.
  - Resumos para a aba e para os cartões: nome, progresso, total em centavos, donos.
  - Sai `replaceItems`.
- `RoutineRepository`: recebe o `ShoppingItemDao`. `complete()` reinicia a lista quando a regra
  P4 se aplica; `uncomplete()` chama `restoreReset`.
- `TaskRepository` (v1.6.0): idem em `logCompletion()` e `undoCompletion()`.
- `TodayRepository` (v1.6.0): combina o progresso por lista ao retrato do dia.
- `BackupManager`: `SCHEMA_VERSION` 6 (seção 4.6).
- `KalendrysApp`: `ShoppingRepository(database)` e os DAOs novos nos repositórios acima.
  Nenhuma dependência nova no Gradle.

**`domain` (puro, sem Android)**
- `ShoppingTotals` (já existe).
- `ShoppingProgress(picked, total)`, com `isComplete`.
- `ShoppingReset`: decide se uma conclusão reinicia a lista (tarefa repetida; bloco de hoje) e
  monta a chave do reinício.
- `ShoppingCompletionOffer` (v1.6.0): escolhe a tarefa candidata da regra P3.
- `LegacyShoppingLists`: converte itens antigos (por `routineId`) em listas + itens + vínculos.
  É a regra usada no restauro de backup antigo e espelha o SQL da migração.

**`ui`**
- `ui/shopping` (pacote novo):
  - `ShoppingListsScreen` + `ShoppingListsViewModel`: a aba. Lista todas as listas com
    progresso, total e etiqueta dos donos; cria, renomeia e exclui (com diálogo de confirmação
    que cita os donos que perderão o vínculo).
  - `ShoppingListScreen` + `ShoppingListViewModel`: tela única de uso. Marcar, adicionar,
    editar, remover (com "Desfazer"), total, "Desmarcar todos", renomear. Tudo persiste na hora;
    o estado sobrevive a rotação e morte de processo porque só o `listId` vem da rota.
  - `ShoppingListCard`: cartão-resumo reutilizado no editor de rotina e no detalhe da tarefa.
  - `ShoppingListPicker`: "Criar nova" ou "Usar lista existente".
- `ui/routineeditor`: a seção inline de compras sai. Entra o cartão, disponível para **qualquer**
  rotina, inclusive rotina nova ainda não salva (o vínculo vive no `RoutineDraft`). Saem do
  `RoutineEditorViewModel`: `shoppingBaseline`, o gate por `COMPRAS`, as operações de item e a
  restauração por snapshot. O snapshot de exercícios fica como está.
- Duplicar rotina **não** leva o vínculo (mantém o comportamento atual de não copiar itens).
- `ui/taskdetail` e `NewTaskSheet` (v1.6.0): cartão e seletor de lista; `TaskDraft` ganha
  `shoppingListId`.

### 4.4 Reinício reversível das marcações (P4)

Reinício, em uma transação do DAO:

```
UPDATE shopping_item SET pickedBeforeReset = picked, picked = 0 WHERE listId = :listId
UPDATE shopping_list SET lastResetKey = :key WHERE id = :listId
```

Chave do reinício:
- tarefa: `task-log:{id do CompletionLog}`
- bloco de rotina: `routine:{routineId}:{epochDay}`

Desfazer a conclusão chama `restoreReset(key)`. Só age se alguma lista ainda tem
`lastResetKey == key`:

```
UPDATE shopping_item SET picked = 1 WHERE listId = :listId AND pickedBeforeReset = 1
UPDATE shopping_item SET pickedBeforeReset = 0 WHERE listId = :listId
UPDATE shopping_list SET lastResetKey = NULL WHERE id = :listId
```

Propriedades:
- Só o reinício mais recente de cada lista é reversível. Um reinício posterior troca a chave e
  o desfazer antigo vira no-op.
- Itens marcados depois do reinício continuam marcados após o desfazer (união).
- Funciona nos caminhos sem UI e sobrevive à morte do processo, porque o estado está no banco.
- A ordem em `logCompletion` é: gravar o `CompletionLog`, depois reiniciar com o id dele. Uma
  falha entre os dois deixa a conclusão sem reinício, o que é inofensivo.

### 4.5 Navegação e pontos de entrada

- `AppTab.Lists` (rota `lists`, rótulo "Listas"): 4ª aba.
- Rota `shopping-list/{listId}?taskId={taskId}`: `taskId` é opcional e só alimenta a oferta P3.
  A barra inferior fica oculta nessa tela, como nas demais telas de detalhe.
- Pontos de entrada:
  1. aba Listas;
  2. cartão no editor de rotina (v1.5.0);
  3. cartão no detalhe da tarefa (v1.6.0). Notificação de lembrete e widget Hoje continuam
     abrindo a tarefa; dali, um toque abre a lista.
- `AppLinks`/`LaunchRequest` **não** ganham link de lista: nada fora do app abre uma lista
  diretamente nestas duas versões.

### 4.6 Backup (`SCHEMA_VERSION` 6)

- Novo array `shoppingLists` (`id, name, createdAt, lastResetKey`).
- `shoppingItems` passa a ter `listId` e `pickedBeforeReset`, sem `routineId`.
- Tarefas e rotinas ganham `shoppingListId`.
- Restauro de backup ≤ v5 (itens com `routineId`, sem listas): passa por
  `LegacyShoppingLists`, que aplica a mesma regra da migração (ordem crescente de `routineId`,
  nome pelo título da rotina, fallback `Lista de compras`). O parser usa um DTO legado, porque a
  entidade não tem mais `routineId`.
- Backup v6 não abre na v1.4.1 (rejeitado como incompatível). É o comportamento já existente
  para versões mais novas.

### 4.7 Alarmes, notificações e widgets

- Nenhum alarme novo, nenhum canal novo, `BootReceiver` intocado. O lembrete continua sendo o
  da tarefa (`ReminderScheduler`) ou o aviso do bloco (`RoutineAlarms`).
- v1.6.0: `TodayOverview.PendingTask` e `RoutineBlock` ganham `listProgress: ShoppingProgress?`.
  O widget Hoje, o resumo diário, o lembrete de tarefa e o aviso de bloco mostram "· 3/8" ao
  lado do título quando há lista.
- Lista avulsa não entra no retrato do dia.

## 5. Fatiamento

Feature nova retrocompatível = MINOR (docs/VERSIONING.md).

### v1.5.0 — Listas independentes (versionCode 10500)

1. **Fundação.** Room v10 com a migração manual, `ShoppingList`, DAOs, `ShoppingRepository` por
   lista, `LegacyShoppingLists`, backup v6 com leitura de ≤ v5. Sem mudança visível além da
   adaptação mínima do editor para compilar.
2. **Tela da lista.** `ShoppingListScreen`, rota, "Desmarcar todos", desfazer remoção de item.
3. **Aba Listas.** `AppTab.Lists`, criar, renomear e excluir lista.
4. **Editor de rotina.** Cartão-resumo para qualquer rotina (P6), "Criar nova" / "Usar lista
   existente" (P2), desvincular, vínculo no rascunho (P7), remoção do snapshot de compras.
5. **Reinício ao concluir bloco de rotina** (P4, parte de rotina), com restauração ao desfazer.
6. **Fechamento.** Jornada da aba no Baseline Profile (`Journeys.kt`) e regeração; README
   (seções Arquitetura, Estrutura e Rotinas; nova seção Listas).

### v1.6.0 — Lista na tarefa (versionCode 10600)

1. **Vínculo na tarefa.** Cartão e seletor no detalhe da tarefa e no `NewTaskSheet`; etiqueta
   de tarefa na aba Listas (P2, P5). O schema já está pronto.
2. **Reinício ao concluir tarefa repetida** (P4, parte de tarefa), com restauração ao desfazer.
3. **Oferta "Concluir tarefa"** ao marcar o último item (P3).
4. **Progresso** no `TodayOverview`, widget Hoje, resumo diário e notificações (P8).
5. **Fechamento.** Baseline Profile se a jornada mudou; README.

Fora do escopo (candidatos a v1.6.x / v1.7.0): reordenar itens, duplicar rotina copiando a
lista, compartilhar lista como texto, link externo direto para a lista.

## 6. Riscos

| Risco | Mitigação |
|-------|-----------|
| Bug na migração 9→10 perde listas de usuário real | Teste com `MigrationTestHelper` nos cenários da seção 7; teste manual de atualização sobre a v1.4.1 com dados |
| Restauro de backup antigo divergir da migração | Regra única em `LegacyShoppingLists`; teste que compara os dois resultados sobre os mesmos dados |
| Regressão no editor de rotina (área estabilizada na v1.4.1) | `RoutineEditorViewModelTest` revisado; exercícios com snapshot intacto |
| Reinício apagar marcações por engano | Reinício reversível; restrição a "hoje" e a tarefa repetida; testes de repositório |
| Lista compartilhada surpreender (reinício por outro dono) | Etiqueta de donos visível na aba e no cartão |
| Widgets redesenhando a cada item marcado (v1.6.0) | O debounce de 500 ms já existe; medir no emulador |
| 4ª aba alterar a inicialização | Regerar o Baseline Profile; conferir o `StartupBenchmark` |

## 7. Plano de testes

### Unitários (JVM / Robolectric)

- **`AppDatabaseMigrationTest` 9→10:** várias rotinas com itens; rotina arquivada; rotina fora da
  categoria `COMPRAS` com itens; itens de rotina inexistente; banco sem itens; ids, `picked`,
  `position` e `createdAt` preservados; `shoppingListId` preenchido; validação contra `10.json`.
  Cadeia completa 3→10.
- **`LegacyShoppingListsTest`:** mesmos cenários; mesma saída que a migração.
- **`ShoppingRepositoryTest` (novo):** posição ao adicionar; editar preserva campos; excluir
  lista apaga itens e desvincula tarefas e rotinas; reinserir item mantém o id; progresso e
  total; `resetPicked` / `restoreReset` (união com marcações novas, chave trocada vira no-op).
- **`ShoppingResetTest`:** tarefa única não reinicia; tarefa repetida reinicia; bloco de hoje
  reinicia; bloco de dia passado não.
- **`RoutineRepositoryTest`:** `complete` de hoje zera a lista; de dia passado não; rotina sem
  lista ou com `shoppingListId` órfão não falha; `uncomplete` restaura; lista compartilhada por
  duas rotinas.
- **`RoutineEditorViewModelTest`:** vínculo entra no rascunho; salvar grava; descartar reverte o
  vínculo e não toca nos itens; rotina nova aceita lista; duplicar não leva o vínculo;
  exercícios inalterados.
- **`BackupManagerTest`:** round-trip v6; restauro de v5, v4 e v3; `schemaVersion` 7 rejeitado.
- **v1.6.0 — `TaskRepositoryTest`:** concluir tarefa repetida zera; tarefa única não;
  `undoCompletion` restaura; excluir + `restoreTask` mantém o vínculo; vínculo órfão não falha.
- **v1.6.0 — `ShoppingCompletionOfferTest`:** tarefa de origem; única tarefa pendente; nenhuma;
  mais de uma; lista vazia; tarefa já concluída.
- **v1.6.0 — `TodayOverviewTest`:** progresso em tarefa e em bloco; lista avulsa fora do retrato;
  vínculo órfão sem progresso.
- **v1.6.0 — `TaskDraftTest`:** `shoppingListId` no rascunho.

### Emulador (Lince / Coruja)

- Atualizar por cima da v1.4.1 com listas existentes: nada sumiu, marcações mantidas, lista de
  rotina fora de `COMPRAS` reapareceu.
- Aba Listas: criar, renomear, excluir (com e sem donos); troca de abas preservando estado.
- Tela da lista: rotação e morte de processo; desfazer remoção; "Desmarcar todos"; total.
- Editor de rotina: criar lista em rotina nova; usar lista existente; descartar; desvincular;
  arquivar a rotina e conferir que a lista ficou na aba.
- Concluir o bloco de hoje pela tela, pelo widget e pela notificação: lista zerada. Desfazer
  pela tela: marcações de volta. Concluir dia passado: lista intacta.
- Backup na v1.4.1 e restauro na v1.5.0; backup e restauro na v1.5.0.
- v1.6.0: lista em tarefa nova e existente; notificação → tarefa → lista; concluir pelo widget e
  pela notificação com itens pendentes; oferta "Concluir tarefa" ao marcar o último item;
  tarefa repetida zera e o desfazer restaura; progresso no widget, no resumo e na notificação;
  excluir tarefa e desfazer.

## 8. Consequências

- A lista passa a ter tela própria; o editor de rotina fica mais simples.
- Marcações deixam de ser permanentes para donos recorrentes.
- Itens antes ocultos por troca de categoria voltam a aparecer.
- O "Descartar" do editor de rotina deixa de cobrir itens (mudança em relação à v1.4.1).
- O padrão (entidade própria + vínculo no dono) fica como referência caso Exercícios precise
  sair das rotinas. Checklist genérico só com um terceiro caso real.
