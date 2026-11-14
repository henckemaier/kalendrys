# ADR-0003 — Entrega de lembretes por catch-up único

- **Status:** aceito (aprovado pelo usuário em 2026-11-14)
- **Autor:** Esquadro (arquiteto)
- **Base:** `main` na v1.6.0
- **Relacionado:** ADR-0002 (seção 11: a v1.6.1 passou a ser esta correção)
- **Entrega:** v1.6.1 (versionCode 10601), PATCH

## 1. Contexto

Sem a permissão de alarme exato ("Alarmes e lembretes" negada), o app agenda com
`setAndAllowWhileIdle` (`reminder/ExactAlarms.kt`), que o sistema pode entregar com atraso. Na
prática foram observados cerca de 8 minutos. O desenho atual supõe que o alarme chega na hora, e
daí saem quatro defeitos. O diagnóstico é de leitura de código; não foi reproduzido pelo arquiteto.

1. **Lembrete repetido pulado.** Todo início de processo chama
   `TaskRepository.rescheduleReminders` (`KalendrysApp.onCreate`), que agenda a próxima ocorrência
   estritamente depois de agora, com o mesmo `PendingIntent`. Se o alarme das 9:00 ainda não foi
   entregue às 9:03, ele é substituído pelo de amanhã e o de hoje nunca toca.
2. **Falso perdido, às vezes duplicado.** `MissedReminderRecovery` (chamado no `onStart` da
   `MainActivity` e no boot) conta como perdida toda ocorrência entre o último visto e agora,
   inclusive a que só está atrasada. Na tarefa única, o alarme atrasado ainda chega depois: o
   usuário recebe "1 lembrete perdido" e o lembrete.
3. **Marca global avançada por outro alarme.** O `ReminderReceiver` grava `lastSeen = agora` a
   cada alarme. Se o alarme da tarefa B chega antes do alarme atrasado da tarefa A, a ocorrência
   de A fica para trás da marca; se o alarme de A se perder, ninguém avisa.
4. **Repetição personalizada contada errado.** `MissedReminders.between` chama
   `ReminderRecurrence.occurrencesBetween` sem `every`, `unit` e `byWeekdays`.

## 2. Decisão

O alarme deixa de ser a entrega e passa a ser só o **despertar**. Existe uma única rotina de
entrega, o **catch-up**, chamada por todos os pontos de entrada: alarme de lembrete, início do
processo, boot (e mudança de hora ou fuso) e volta do app ao primeiro plano. Quem acordar
primeiro entrega tudo o que está vencido e ainda não foi processado; quem chegar depois encontra
a janela vazia.

Decisões de produto (usuário, 2026-11-14):

- Tolerância de **60 minutos** para tratar uma ocorrência como atrasada, e não perdida.
- Sai na **v1.6.1**, antes da atualização de toolchain (v1.6.2), em release separada.

## 3. Regras

**Janela.** O catch-up olha as ocorrências em `(lastSeen, agora]`: aberta embaixo, fechada em
cima. `lastSeen` é a marca já existente em `SettingsRepository` (DataStore, fora do backup).

**Classificação**, por ocorrência:

| Situação | Critério | O que acontece |
|----------|----------|----------------|
| Atrasada | `agora - ocorrência <= 60 min` | Entregue agora como lembrete normal (Concluir, Adiar, Abrir lista) |
| Perdida | `agora - ocorrência > 60 min` | Entra na contagem do aviso agregado "N lembretes perdidos" |
| Futura | `ocorrência > agora` | Fica para o agendamento |

- Por tarefa, só a ocorrência **mais recente** da janela é considerada para entrega; as demais
  da mesma tarefa contam como perdidas se estiverem fora da tolerância.
- Ficam de fora, como hoje: tarefa única já concluída e tarefa repetida já concluída no dia da
  ocorrência.
- Não existe espera pelo alarme. O app nunca sabe de um atraso e fica quieto: aberto às 9:03, o
  lembrete das 9:00 aparece às 9:03.
- A tolerância é uma constante em `domain/` (`LATE_TOLERANCE_MINUTES = 60`).

**Ordem da rotina**, sempre com um único `agora` e uma execução por vez (Mutex):

1. Lê `lastSeen`. Se não existe (primeira execução), grava `agora`, agenda os alarmes e sai.
2. Monta o plano (função pura): atrasadas a entregar e quantidade de perdidas.
3. Mostra as atrasadas e, se houver perdidas, o aviso agregado.
4. Agenda a próxima ocorrência de cada tarefa, estritamente depois de `agora`.
5. Grava `lastSeen = agora`.

**Só o catch-up grava `lastSeen`.** O `ReminderReceiver` deixa de gravar por conta própria.

**Propriedades que os testes devem garantir:**

- Sem pulo: o reagendamento (passo 4) só acontece depois da entrega (passo 3), então substituir
  um alarme pendente não perde a ocorrência.
- Sem duplicidade: um alarme atrasado que toque depois encontra a janela vazia e não mostra nada.
- Entrega ao menos uma vez: se o processo morrer entre os passos 3 e 5, a ocorrência é reexibida
  na próxima execução, com o mesmo id de notificação. Repetir é preferível a perder.
- Com alarme exato nada muda para o usuário: o alarme chega na hora, a janela tem só aquela
  ocorrência e ela é entregue uma vez.
- Relógio voltou (`agora <= lastSeen`): janela vazia; a marca passa a ser `agora`.

**Criar ou editar lembrete com horário já passado** (tarefa diária às 9:00 criada às 10:00) não
gera atrasado nem perdido. Para isso a janela é fechada no instante da mudança: o
`TaskRepository` roda o catch-up **antes** de gravar em `addTask`, `updateTask` e `restoreTask`.

## 4. Desenho

Sem migração do Room, sem dependência nova, sem mudança no backup, sem canal de notificação novo.

### 4.1 Camadas

- **`domain/ReminderCatchUp`** (substitui `domain/MissedReminders`): função pura.
  Entrada: tarefas com lembrete, última conclusão por tarefa, conclusões do dia, `lastSeen`,
  `agora`, fuso e tolerância. Saída: `Plan(late: List<Occurrence>, missedCount: Int)`. Usa
  `ReminderRecurrence.occurrencesBetween` passando `intervalEvery`, `intervalUnit` e
  `byWeekdays`. As guardas que hoje estão em `TaskRepository.onReminderFired` (concluída,
  concluída hoje) passam a morar aqui.
- **`reminder/ReminderCatchUpRunner`** (substitui `reminder/MissedReminderRecovery`):
  orquestrador. Tem o Mutex, lê e grava `lastSeen`, chama o plano, mostra as notificações
  (`ReminderNotifications.show` para as atrasadas, com o progresso da lista; o agregado para as
  perdidas), chama `TaskRepository.rescheduleReminders(agora)` e atualiza os widgets. É criado no
  `KalendrysApp` (DI manual).
- **`data/repository/TaskRepository`**: `onReminderFired` sai. Ganha o gancho
  `beforeReminderChange: suspend () -> Unit`, passado pelo `KalendrysApp` (mesmo padrão do
  `afterRestore` do `BackupManager`, para não criar dependência circular entre repositório e
  orquestrador), chamado no início de `addTask`, `updateTask` e `restoreTask`. Valor padrão
  vazio, para os testes de repositório existentes.

### 4.2 Pontos de entrada

| Ponto | Hoje | Passa a ser |
|-------|------|-------------|
| `ReminderReceiver` | grava `lastSeen`, `onReminderFired`, mostra | chama o catch-up |
| `KalendrysApp.onCreate` | `rescheduleReminders()` direto | chama o catch-up (corrige o pulo) |
| `BootReceiver` | `rescheduleReminders(now)` + recuperação | chama o catch-up com o mesmo `now` |
| `MainActivity.onStart` | recuperação | chama o catch-up |
| Restauro de backup (`afterRestore`) | `rescheduleReminders()` | grava `lastSeen = agora` e agenda, sem entregar o passado do backup |

O adiamento ("Adiar", alarme avulso do `ReminderActionReceiver`) não muda. O
`ReminderScheduler` não muda. Um alarme de tarefa única que sobre pendente depois da entrega é
inofensivo (janela vazia), mas o runner o cancela por higiene.

### 4.3 Avisos de rotina e resumo diário

Pela leitura, o início do processo também chama `routineRepository.scheduleNextAlarm()`, que
agenda o próximo início depois de agora e pode substituir um aviso de bloco atrasado. O
`onAlarm` tolera atraso quando o alarme chega, mas não quando ele é substituído. **Não foi
verificado.** Regra para a v1.6.1:

1. Primeiro um teste que confirme ou descarte o pulo, para o aviso de bloco e para o resumo
   diário.
2. Se confirmar e a correção for pequena, entra na v1.6.1 com marca própria
   (`lastSeen` de rotinas, separada da de tarefas). Aviso de bloco fora da tolerância não gera
   agregado: é só descartado.
3. Se não for pequena, vira demanda separada, registrada como emenda a este ADR.

## 5. Riscos

| Risco | Mitigação |
|-------|-----------|
| Muda o caminho de entrega de todo lembrete, inclusive com alarme exato | Testes de emulador com a permissão concedida são obrigatórios (seção 6) |
| Lembrete normal aparecendo com o app aberto em primeiro plano | É o comportamento correto; conferir que não atrapalha a tela |
| Notificação reexibida após morte do processo | Aceito (ao menos uma vez); mesmo id, não empilha |
| Gancho acopla escrita de tarefa à rotina de lembretes | Restrito a três métodos; valor padrão vazio |
| Duas entradas simultâneas (alarme + início de processo) | Mutex no runner; teste de concorrência |
| Tolerância curta ou longa demais | Uma constante em `domain/`; revisão por emenda |

## 6. Plano de testes

### Unitários do plano (JVM)

- atrasada dentro da tolerância; perdida fora; limites exatos da janela e da tolerância;
- várias ocorrências da mesma tarefa: só a mais recente é entregue;
- tarefa única concluída; repetida concluída hoje;
- repetição personalizada com intervalo e dias da semana (regressão do defeito 4);
- `agora <= lastSeen` (relógio voltou);
- virada de dia e horário de verão.

### Runner (Robolectric, agendador e notificador falsos)

- app abre na janela de atraso: lembrete mostrado uma vez, próxima ocorrência agendada para
  amanhã; o alarme atrasado chega depois e não duplica;
- alarme chega primeiro e o app abre depois: nenhum perdido;
- A atrasada e B no horário: A não some;
- início de processo não pula a ocorrência de hoje;
- duas execuções simultâneas: uma entrega só;
- falha antes de gravar a marca: reexibe, não perde;
- criar, editar e restaurar tarefa com lembrete em horário passado: nada é mostrado;
- primeira execução só estabelece a marca;
- restauro de backup não entrega lembretes antigos.

### Emulador (Coruja)

Negar a permissão (`adb shell appops set com.kalendrys.app SCHEDULE_EXACT_ALARM deny`) e, com
lembrete para daqui a 2 minutos:

- app fechado, esperando;
- abrir o app a frio dentro da janela de atraso;
- abrir com o app já em segundo plano;
- tarefa diária: a de hoje aparece e a de amanhã fica agendada (tela de diagnóstico de alarmes);
- Doze forçado; reinício do aparelho;
- aparelho desligado por mais de 1 hora: vem o aviso agregado.

Depois, **com** a permissão: lembrete na hora, uma vez só, sem agregado; Concluir, Adiar e Abrir
lista funcionando; adiar e concluir antes de o adiamento tocar.

### Critérios de aceite

1. Os quatro defeitos da seção 1 cobertos por teste que falha antes da correção e passa depois.
2. Suíte completa e lint sem erros; nenhum teste removido sem justificativa (os de
   `MissedRemindersTest` migram para o plano novo).
3. Schema do Room e versão do backup inalterados.
4. Roteiro de emulador acima aprovado nas duas condições de permissão.
5. Item 4.3 resolvido: teste escrito e decisão registrada.

## 7. Consequências

- Um caminho só para entregar lembrete, em vez de três com regras diferentes.
- `lastSeen` passa a significar "tudo até aqui foi entregue ou contado", e só o catch-up a move.
- O lembrete de quem negou o alarme exato deixa de depender do momento em que o sistema entrega
  o alarme, sempre que o app tiver qualquer chance de rodar.
- Fica aberta a decisão sobre avisos de rotina e resumo diário (4.3).
