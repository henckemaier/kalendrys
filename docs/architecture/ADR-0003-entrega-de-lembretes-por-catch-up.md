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

**Desfazer conclusão também fecha a janela** (emenda de 2026-11-14, revisão do núcleo): o
`undoCompletion` passa pelo mesmo gancho. Sem isso, uma tarefa única com lembrete às 9:00,
concluída às 8:50 e desfeita às 9:10, geraria depois um atrasado ou um "perdido" indevido.

**Duas garantias de execução** (mesma emenda):
- O `agora` é lido **dentro** do Mutex quando o chamador não o informa. Lido fora, duas chamadas
  quase simultâneas podem entrar em ordem trocada e a marca andaria para trás.
- O trecho "mostrar, agendar, gravar a marca" não é cancelável. O catch-up é chamado de escopos
  que podem ser cancelados (Activity, ViewModel); cancelar no meio reexibiria o lembrete.

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
  orquestrador), chamado no início de `addTask`, `updateTask`, `restoreTask` e
  `undoCompletion`. Valor padrão vazio, para os testes de repositório existentes.

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

> **Emenda de 2026-11-14.** O pulo foi **confirmado por teste**
> (`ProcessStartAlarmReplacementTest`, commit afcd8f1 em `dev/brasa`), para o aviso de bloco e
> para o resumo diário. Esta seção substitui o texto original, que só pedia a verificação.
>
> **Decisão do usuário (2026-11-14):** as duas correções **entram na v1.6.1**, com o desenho
> abaixo; bloco de rotina com duração já terminado **não é avisado**; o efeito da primeira
> execução depois de atualizar (4.3.3) está **aceito**.

**O que foi confirmado.** Sem alarme exato:
- Aviso de bloco: qualquer `scheduleNextAlarm` (início de processo, criar, editar, arquivar,
  pausar, concluir ou pular rotina, remover exceção, boot, restauro) substitui o alarme pendente
  atrasado, e o aviso some. O `onAlarm` tolera atraso, mas não a substituição.
- Resumo diário: `DailyScheduler.scheduleAll` rodando depois do horário (início de processo ou
  emissão das configurações) deixa só o de amanhã, e o de hoje some.

**Princípio.** O mesmo das tarefas: o alarme é só o despertar; a entrega é decidida por uma
janela `(marca, agora]`; a ordem é entregar, agendar, gravar a marca; só o catch-up do domínio
grava a marca dele. Cada domínio tem **marca própria** no `SettingsRepository` (DataStore, fora
do backup), porque os três catch-ups rodam em momentos diferentes:

| Domínio | Marca | Fora da tolerância |
|---------|-------|--------------------|
| Tarefas | `last_seen_at` (já existe) | aviso agregado |
| Rotinas | `routine_last_seen_at` (nova) | descartado, sem agregado |
| Resumo diário | `summary_last_seen_at` (nova) | descartado |

#### 4.3.1 Aviso de bloco

- **Plano puro em `domain/`** (`RoutineCatchUp`): recebe rotinas ativas, exceções, conclusões
  dos dias da janela, marca, `agora`, fuso e tolerância; devolve os blocos a avisar. Um bloco
  entra quando tem aviso ligado, ocorre no dia (honrando `SKIP` e remarcação), não está
  concluído naquele dia, o início efetivo está em `(marca, agora]` e `agora - início <= 60 min`.
  A janela pode cruzar a meia-noite: avaliar ontem e hoje.
- **Bloco já terminado não é avisado:** se o bloco tem duração e `agora >= fim`, é descartado
  mesmo dentro dos 60 minutos. É aviso de início; depois do fim não serve. Aprovado pelo
  usuário. Bloco sem duração segue só a regra dos 60 minutos.
- **`RoutineRepository.catchUp(agora)`**, sob um Mutex do repositório: lê a marca (ausente:
  grava `agora`, agenda e sai); monta o plano; entrega; agenda o próximo início estritamente
  depois de `agora`; grava a marca.
- **Entrega por gancho:** o repositório não monta notificação. Recebe do `KalendrysApp`
  `deliverDueBlocks: suspend (List<Routine>, LocalDate) -> Unit` (mesmo padrão do
  `afterRestore`), que chama `RoutineNotifications.show` com exceções e progresso da lista.
  Valor padrão vazio para os testes existentes.
- **Métodos que alteram rotinas** (criar, editar, arquivar, pausar, remarcar, pular, remover
  exceção, concluir e desfazer): chamam `catchUp` **antes** de gravar, para fechar a janela com
  a configuração antiga; depois de gravar, só agendam. Sem isso, às 9:20 criar um bloco das
  9:00, mover um bloco das 10:00 para as 9:00 ou tirar um `SKIP` de hoje avisaria o bloco das
  9:00 como atrasado. É o mesmo caso do `beforeReminderChange` das tarefas.
- **Mutex:** todo `alarms.scheduleNext` acontece dentro do Mutex, com os dados lidos dentro
  dele. O trecho "catch-up, gravação, agendamento" de uma alteração fica inteiro sob o mesmo
  Mutex, para um catch-up concorrente não agendar com a lista antiga.
- **`RoutineAlarmReceiver.ACTION_START`** só chama `catchUp`. O extra do minuto agendado deixa
  de decidir a entrega (continua no intent por compatibilidade com alarmes já agendados).
  `onAlarm` sai. Adiar, Concluir e Pular da notificação não mudam.
- Blocos sem aviso continuam usando o alarme só para redesenhar widgets; o catch-up os ignora.

#### 4.3.2 Resumo diário

- **Mesma regra de janela, não uma chave de data.** A marca `summary_last_seen_at` evita dois
  problemas da chave "data do último resumo": trocar o horário nas Configurações não entrega um
  resumo "atrasado" na hora, e trocar para um horário mais tarde no mesmo dia continua
  funcionando.
- **Plano puro em `domain/`:** a ocorrência do resumo é o horário configurado de hoje (ou de
  ontem, se a janela cruza a meia-noite). É entregue se o resumo está ligado, a ocorrência está
  em `(marca, agora]` e `agora - ocorrência <= 60 min`.
- **Catch-up em `summary/`** (junto do `DailyScheduler`), sob Mutex próprio: plano, mostra
  (`DailySummaryNotifier`), agenda (`scheduleAll`), grava a marca.
- **Quem chama o catch-up:** `DailyReceiver` (ação do resumo), início de processo, boot e volta
  ao primeiro plano.
- **Mudança nas Configurações** (ligar, desligar, novo horário): grava a marca `= agora` e só
  agenda, sem entregar. No `keepDailyAlarmsInSync`, a primeira emissão é o início de processo
  (catch-up); as seguintes são mudança de configuração (marca e agenda).
- O alarme da virada do dia (00:01, só widgets) fica como está.

#### 4.3.3 Pontos comuns

- **Entrada única da aplicação:** início de processo, `BootReceiver` e `MainActivity.onStart`
  chamam os três catch-ups com o mesmo `agora`. Cada receiver de alarme chama só o do seu
  domínio.
- **Restauro de backup:** grava as três marcas `= agora` e só agenda. Nada do passado do backup
  é entregue.
- **Relógio voltou** (`agora <= marca`): janela vazia; a marca passa a ser `agora`.
- **Primeira execução** depois de atualizar da v1.6.0: as marcas novas não existem; cada
  catch-up só estabelece a marca e agenda.
- A tolerância é a mesma constante de 60 minutos.

#### 4.3.4 Testes adicionais

- `ProcessStartAlarmReplacementTest` passa a verde (os 5 casos que hoje demonstram o defeito).
- Plano de rotinas: bloco atrasado dentro e fora da tolerância; bloco já terminado; concluído;
  `SKIP`; remarcado de dia e de minuto; janela cruzando a meia-noite; aviso desligado.
- Repositório de rotinas: cada método que altera rotinas com horário já passado não avisa
  (criar às 9:20 um bloco das 9:00; mover das 10:00 para as 9:00; tirar o `SKIP` de hoje);
  alarme atrasado substituído ainda avisa uma vez; duas execuções simultâneas avisam uma vez.
- Resumo: abrir o app 3 minutos depois do horário mostra o resumo uma vez e agenda o de amanhã;
  o alarme atrasado chegando depois não duplica; trocar o horário para um já passado não mostra
  nada; trocar para mais tarde no mesmo dia mostra de novo; resumo desligado não mostra; mais
  de 60 minutos de atraso descarta.
- Restauro: nenhuma das três entregas dispara.
- Emulador, sem a permissão de alarme exato: bloco para daqui a 2 minutos e resumo para daqui a
  2 minutos, repetindo os cenários da seção 6 (app fechado, abrir a frio na janela, editar
  outra rotina na janela, reinício). Com a permissão: aviso e resumo na hora, uma vez só.

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
5. Item 4.3 entregue na v1.6.1: `ProcessStartAlarmReplacementTest` verde e os testes de 4.3.4
   presentes, para aviso de bloco e resumo diário.
6. As duas garantias de execução da seção 3 cobertas por teste (relógio lido dentro do Mutex;
   cancelamento no meio não reexibe).

## 7. Consequências

Decisão fechada em 2026-11-14: a v1.6.1 corrige os três caminhos de alarme (lembrete de tarefa,
aviso de bloco e resumo diário) com a mesma regra.

- Um caminho só de entrega por domínio, em vez de regras diferentes em cada receiver.
- Cada domínio tem sua marca (`last_seen_at`, `routine_last_seen_at`, `summary_last_seen_at`),
  que passa a significar "tudo até aqui foi entregue, contado ou descartado". Só o catch-up do
  domínio a move, além das mudanças de configuração e do restauro, que a levam para `agora`.
- Quem negou o alarme exato deixa de depender do momento em que o sistema entrega o alarme,
  sempre que o app tiver qualquer chance de rodar.
- Diferenças entre domínios, por decisão de produto: só lembrete de tarefa gera o aviso agregado
  de perdidos; aviso de bloco e resumo fora da tolerância são descartados; bloco com duração já
  terminado não é avisado.
- Na primeira execução depois de atualizar da v1.6.0, as marcas novas ainda não existem: um
  aviso de bloco ou resumo que esteja atrasado exatamente nesse momento não é recuperado. Aceito.
- A v1.6.1 mexe nos três caminhos de alarme de uma vez. Commits separados por domínio e teste de
  emulador com e sem a permissão de alarme exato para os três.
- Nenhuma migração, nenhuma dependência nova, backup inalterado: as marcas ficam no DataStore,
  fora do backup.
