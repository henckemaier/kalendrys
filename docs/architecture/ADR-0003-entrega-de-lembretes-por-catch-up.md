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
- Blocos sem aviso continuam usando o alarme só para redesenhar widgets; o catch-up os ignora. **Corrigido pela seção 8 (v1.9.1):** o alarme dos widgets passa a ser separado do alarme de aviso.

#### 4.3.2 Resumo diário

- **Mesma regra de janela, não uma chave de data.** A marca `summary_last_seen_at` evita dois
  problemas da chave "data do último resumo": trocar o horário nas Configurações não entrega um
  resumo "atrasado" na hora, e trocar para um horário mais tarde no mesmo dia continua
  funcionando.
- **Plano puro em `domain/`:** a ocorrência do resumo é **só** o horário configurado de
  **hoje** (o dia de `agora`, no fuso do aparelho). É entregue se o resumo está ligado, a
  ocorrência está em `(marca, agora]` e `agora - ocorrência <= 60 min`.
- **A ocorrência de ontem é descartada depois da meia-noite** (decisão do usuário,
  2026-11-14), mesmo dentro dos 60 minutos. O resumo mostra o retrato do dia corrente; um
  resumo das 23:50 entregue às 0:10 mostraria o dia novo com o rótulo de um resumo de ontem.
  Vale só para o resumo: o aviso de bloco continua avaliando ontem e hoje (4.3.1).
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
  chamam os três catch-ups. Cada receiver de alarme chama só o do seu domínio.
- **Cada catch-up lê o próprio `agora`, dentro do seu Mutex** (ratificado na revisão da
  entrega, 2026-11-14; o texto original pedia um `agora` comum aos três). As marcas são
  independentes, então um instante comum não traz nenhuma garantia a mais. E um `agora` lido
  fora do Mutex reabre a troca de ordem da seção 3: uma chamada com instante anterior ao da
  marca já gravada cairia em "relógio voltou" e reentregaria. O mesmo `agora` continua
  obrigatório **dentro** de um domínio: catch-up, gravação e agendamento de uma mesma alteração
  de rotina usam um único instante.
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
  de 60 minutos de atraso descarta; resumo das 23:50 com catch-up às 0:10 não mostra nada e
  agenda o das 23:50 do dia novo.
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
  terminado não é avisado; o resumo só considera a ocorrência de hoje (a de ontem é descartada
  depois da meia-noite).
- Na primeira execução depois de atualizar da v1.6.0, as marcas novas ainda não existem: um
  aviso de bloco ou resumo que esteja atrasado exatamente nesse momento não é recuperado. Aceito.
- A v1.6.1 mexe nos três caminhos de alarme de uma vez. Commits separados por domínio e teste de
  emulador com e sem a permissão de alarme exato para os três.
- Nenhuma migração, nenhuma dependência nova, backup inalterado: as marcas ficam no DataStore,
  fora do backup.

## 8. Emenda de 2026-11-24 — dois alarmes de rotina (v1.9.1)

Versão corretiva, só com este defeito. Aprovada pelo usuário em 2026-11-24. Sem migração (banco
11, backup na versão 6), sem dependência nova, sem mudança de manifesto.

### 8.1 O defeito

Achado no fechamento da v1.9.0 (ADR-0002, 15.12); existe desde a v1.8.0. Lido no código, não
reproduzido até a data desta emenda.

Havia **um** alarme para todas as rotinas, no próximo início de qualquer bloco. Quando nesse
início nenhuma rotina avisava (aviso desligado, ou as que avisam já concluídas no dia), o alarme
era silencioso: não acorda o aparelho, espera ele acordar. O alarme do bloco seguinte só era
agendado quando o anterior disparava, porque é o catch-up que agenda o próximo (4.3.1). Com o
aparelho dormindo, a corrente parava no elo silencioso.

O efeito não é só atraso. O catch-up descarta o aviso de bloco com mais de 60 minutos, sem aviso
de perdidos (seção 7). Bloco sem aviso às 7:00, bloco com aviso às 7:30, aparelho parado até
8:45: o aviso das 7:30 nunca aparece.

### 8.2 A regra

O horário do próximo aviso não pode depender de um alarme que não acorda o aparelho. Passam a
existir dois alarmes, calculados juntos, sob o Mutex do repositório, no mesmo ponto em que hoje
se agenda um:

- **Alarme de aviso:** no próximo início em que alguma rotina avisa e ainda não foi concluída no
  dia. É o alarme de despertador de sempre (degrada para inexato que acorda, sem a permissão de
  alarme exato).
- **Alarme dos widgets:** no próximo início de qualquer bloco, silencioso, **só quando ele vem
  antes do alarme de aviso** ou quando não há alarme de aviso. Serve para redesenhar os widgets
  e não é condição de nenhum aviso.

Consequências do desenho:

- **Uma função só para o próximo aviso.** O cálculo que o diagnóstico de alarmes já fazia
  (`nextRemindingStart`: pula os inícios em que todas as que avisam já estão concluídas no dia)
  passa a ser o mesmo que agenda. O diagnóstico mostra exatamente o que está no sistema; deixa
  de valer a ressalva de que o horário previsto podia ainda não estar agendado.
- **`RoutineAlarms` não calcula.** Recebe do repositório os dois horários prontos, cada um
  podendo ser nulo, e agenda ou cancela cada um. O repositório é a única fonte do início
  calculado (item que estava previsto para a v1.10.0 no ADR-0002, 15.9).
- **Dois `PendingIntent`.** O do aviso continua sendo o que já existia (`ROUTINE_START`), para
  quem atualiza por cima da v1.9.0 não ficar com alarme órfão. O dos widgets tem ação própria.
- **O receiver trata as duas ações do mesmo jeito:** chama o catch-up, que entrega o que estiver
  vencido e reagenda os dois. Quem chega primeiro entrega; o segundo encontra a janela vazia.
- **Nada novo a ligar.** Alteração de rotina, conclusão, exceção, restauro, boot, troca de hora,
  troca de fuso e atualização do app já terminam no mesmo agendamento.
- **Ordem preservada:** entregar antes de substituir alarme e gravar a marca no fim (seção 3).
- Se o limite de inícios descartados do cálculo do próximo aviso se esgotar, não há alarme de
  aviso e o dos widgets segue a corrente, como antes da emenda.

**Efeito visível, aceito pelo usuário:** o ícone de despertador e o próximo alarme da tela de
bloqueio passam a aparecer sempre que houver uma rotina com aviso pela frente, mesmo com um bloco
sem aviso antes dela.

### 8.3 Riscos

| Risco | Mitigação |
|-------|-----------|
| Os dois alarmes disparando quase juntos (o silencioso atrasado chega com o de aviso) | Mutex e marca do catch-up; teste em sequência e em paralelo |
| Aviso duplicado ou pulado na atualização por cima da v1.9.0 | O alarme de aviso reaproveita o `PendingIntent` antigo; caso próprio no emulador |
| Prometer mais do que a correção entrega | Sem a permissão de alarme exato o aviso ainda pode atrasar alguns minutos, e fabricante que mata o app em segundo plano continua fora do alcance |

### 8.4 Aceite e testes

1. Com uma rotina sem aviso antes de uma com aviso, o sistema mostra os dois alarmes logo depois
   de salvar: o de despertador no horário da que avisa e o silencioso no horário da outra.
2. Com o aparelho em soneca forçada, o aviso da segunda toca no horário. O mesmo roteiro é
   rodado **antes** na v1.9.0, para reproduzir o defeito e validar o próprio teste.
3. Concluir a que avisa antes da hora move o alarme de aviso para a próxima que avisa; desmarcar
   devolve. O mesmo para desligar o aviso, pausar, pular e excluir. Sem rotina que avise, não há
   alarme de despertador de rotina.
4. O diagnóstico de alarmes mostra o mesmo horário que está no sistema.
5. Atualização por cima da v1.9.0 com alarme pendente: alarmes certos depois, nenhum aviso
   duplicado nem perdido.
6. Sem regressão: aviso não duplicado nem pulado; widgets redesenham no início do bloco
   silencioso com a tela ligada; concluir, pular e adiar pela notificação.

Unitários e Robolectric: `RoutineAlarms` com horário nulo de cada lado, tipos de alarme e
`PendingIntent` distintos; repositório com alarmes falsos nos casos do item 3, mais virada de
dia, troca de fuso, remarcação de dia e de minuto e limite esgotado; catch-up chamado pelas duas
ações; diagnóstico e agendamento com o mesmo resultado nos mesmos dados.

Release com R8 instalado e testado. O Baseline Profile não é regerado numa corretiva.

### 8.5 Etapas

| Etapa | Conteúdo |
|-------|----------|
| E1 | Função única do próximo aviso, agendamento com os dois horários, `RoutineAlarms`, receiver. Revisão do arquiteto antes dos testes |
| E2 | Testes, matriz de emulador da 8.4, release, versão 1.9.1 |

### 8.6 Fechamento (2026-11-25)

Branch `fix/aviso-rotina-v1.9.1`, último commit de código e testes `d64810c`, CI verde nesse
commit: 1123 testes, 0 falhas, 31 deles novos para esta emenda. Depois dele o PR só acrescenta
documentação e o bump para 1.9.1. O código de produção é o de `fdfd9f4` (três arquivos),
revisado pelo arquiteto antes dos testes; o emulador foi testado em `b69592c`, em debug e em
release com R8.

**Defeito reproduzido antes da correção.** Na v1.9.0, com soneca forçada, o aviso do bloco que
vinha depois de um bloco sem aviso só saiu quando o aparelho deixou a soneca, 5 min 44 s depois
do horário. Até aqui ele só tinha sido lido no código (8.1).

**Correção confirmada no emulador.** Os itens 1 e 3 a 6 da 8.4 aprovados. O item 2 em dois
cenários: tela apagada fora da tomada, e soneca profunda forçada, em que o bloco sem aviso não
tocou e o bloco com aviso notificou uma vez, no segundo do horário. Sem falha fatal nem ANR;
dados do emulador restaurados e conferidos no fim.

**Observações que ficam, nenhuma criada por esta emenda:**

- O horário dos alarmes é calculado no fuso do repositório e convertido em instante no
  `RoutineAlarms` com o fuso do sistema. Em produção são o mesmo. O repositório passar o instante
  pronto fica para a v1.10.0.
- Troca de fuso para oeste pode reavisar um bloco já avisado. Já existia.
- Se o relógio do aparelho atrasar, a marca do catch-up anda para trás. Já existia.
- O alarme dos widgets é inexato: o redesenho sai de 1 a 4 minutos depois do início do bloco
  silencioso, como na v1.9.0.
- O sistema recusa forçar a soneca quando há um despertador a menos de uma hora. É o efeito
  esperado do alarme de aviso, e quem testar soneca precisa pôr o aviso a mais de uma hora.

Abertura a frio: continua não medida (ADR-0002, 15.12); esta versão não muda isso.

## 9. Emenda de 2026-11-25 — ocorrência de tarefa repetida já concluída adiantada (v1.10.0)

Decisão do usuário (ADR-0002, 16.1, P3): concluir adiantado uma tarefa que se repete vale pela
próxima ocorrência, e o lembrete daquele dia não toca. Qual ocorrência um registro de conclusão
satisfaz é regra do domínio (ADR-0002, 16.4); esta seção diz o que ela muda na entrega.

- **Uma pergunta só, feita ao domínio:** "esta ocorrência já está satisfeita?". Agendador e
  catch-up não recalculam a janela de registros por conta própria.
- **Agendador:** o próximo toque de uma tarefa repetida pula a ocorrência satisfeita e vai para a
  seguinte. Só uma ocorrência pode estar consumida, então o pulo é de no máximo uma.
- **Catch-up:** uma ocorrência satisfeita dentro da janela é descartada como já tratada. Não
  entra no aviso agregado de perdidos.
- **Concluir e desfazer reagendam.** Concluir adiantado e desfazer essa conclusão passam pelo
  mesmo caminho de mudança de lembrete das tarefas: fecha a janela com a situação antiga, grava,
  agenda com a nova. Desfazer devolve o lembrete da ocorrência, se o horário ainda não passou.
- **Tarefa sem repetição não muda:** a conclusão adiantada dela já reescreve a data (ADR-0002,
  14.2).
- **Sem lembrete, nada muda aqui:** a regra só afeta tarefa repetida com horário.

Aceite: tarefa semanal com lembrete, concluída na véspera, não toca no dia; desfeita antes do
horário, toca; concluída no próprio dia, comportamento de sempre; atualização por cima da v1.9.1
com lembrete pendente não duplica nem perde aviso. Testes do plano e do agendador com ocorrência
consumida, e um caso de emulador com o aparelho em soneca.

O desenho de código (onde o agendador e o catch-up fazem a pergunta) é fechado na revisão da E1 e
da E2 e acrescentado aqui. É o primeiro corte da versão: se sair, esta seção fica registrada como
não implementada.
