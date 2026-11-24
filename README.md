# Kalendrys

App Android pessoal de tarefas, rotinas, lembretes e calendário de atividades.
Kotlin + Jetpack Compose + Room + AlarmManager. Funciona offline — a única
funcionalidade que usa rede é o botão manual e opcional "Buscar atualizações"
(Config → Sobre), que consulta o GitHub e baixa a atualização; todo o resto roda
sem internet.

## Arquitetura

```
ui (Compose + ViewModel)  →  data/repository  →  data/local (Room)
                                    ↓
                          reminder (AlarmManager)
domain/ = lógica pura (calendário, recorrência, linha do tempo das rotinas, regras das listas), testável sem Android
```

As listas de compras são entidades próprias (`ShoppingList`), e tarefas e rotinas só guardam o
vínculo (`shoppingListId`). Concluir um dono recorrente zera a lista pelo `ShoppingRepository`
(reinício reversível); a oferta "Concluir tarefa" é uma regra pura
(`domain/ShoppingCompletionOffer`). As notificações e o cartão da lista no detalhe da tarefa
abrem a lista pelo link interno `kalendrys://list/{id}` (`AppLinks.openList`).

## Estrutura

```
app/src/main/java/com/kalendrys/app/
├── KalendrysApp.kt          # Application: DI manual, canais de notificação, widgets em sincronia
├── MainActivity.kt          # Entry point + tema + permissão de notificação
├── AppLinks.kt              # Intents que abrem o app numa tarefa, em "nova tarefa", nas rotinas de um dia
│                            # ou numa lista (kalendrys://list/{id}?taskId={id})
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt
│   │   ├── entity/          # Task, CompletionLog (log imutável), Routine, RoutineCompletion,
│   │   │                    # ShoppingList + ShoppingItem (listas de compras independentes)
│   │   └── dao/             # TaskDao, CompletionLogDao, RoutineDao, RoutineCompletionDao,
│   │                        # ShoppingListDao, ShoppingItemDao
│   ├── repository/
│   │   ├── TaskRepository.kt    # tarefas: banco + alarmes de lembrete sempre em sincronia
│   │   ├── RoutineRepository.kt # rotinas: banco + alarme do próximo início + notificações
│   │   ├── ShoppingRepository.kt # listas: itens, progresso, donos e reinício reversível
│   │   ├── BackupManager.kt  # backup/restauro em JSON (schemaVersion 6, lê os antigos)
│   │   ├── TodayRepository.kt   # retrato do dia (tarefas + rotinas) para o resumo e os widgets
│   │   └── Clock.kt             # data de hoje que vira sozinha à meia-noite
│   └── settings/            # AppSettings + SettingsRepository (DataStore)
├── domain/
│   ├── CalendarCalculator.kt # grade do mês, início da semana, agrupamento por dia local
│   ├── ReminderRecurrence.kt # ocorrências dos lembretes recorrentes (diário, dias úteis, semanal, mensal)
│   ├── ReminderCatchUp.kt, RoutineCatchUp.kt, SummaryCatchUp.kt # planos puros do catch-up (ADR-0003)
│   ├── RoutineSchedule.kt    # em que dias e horários cada rotina acontece (relógio local)
│   ├── DayTimeline.kt        # linha do tempo do dia: blocos, ligações e tempo livre
│   ├── RoutineDays/Icon/Color.kt, DurationFormat.kt
│   ├── ShoppingReset.kt, ShoppingProgress.kt, LegacyShoppingLists.kt # regras das listas
│   ├── AppLocale.kt          # idioma fixo do app (pt-BR, semana começando no domingo)
│   └── TodayOverview.kt      # retrato do dia: pendentes, rotinas de hoje, "a seguir"
├── ui/
│   ├── theme/               # Paleta azul-ardósia (Material 3), Aparência (Sistema/Claro/Escuro, Material You), formas, tipografia
│   ├── components/          # Design system: AppScaffold, AppTopBar, CollapsingFab, SwipeRow, ListRow, QuickAddField, GroupedCard, EmptyState...
│   ├── navigation/          # Bottom bar: Hoje | Tarefas | Calendário | Listas; painel de nova tarefa e Desfazer no topo
│   ├── today/               # Tela Hoje (inicial): progresso, próximo, linha do tempo, tarefas do dia, Concluídas
│   ├── tasks/               # Lista em seções, arrastar p/ excluir, desfazer, NewTaskSheet, ReminderPicker
│   ├── edit/                # Salvar na hora dos editores: buffer de campo de texto e fila de gravações
│   ├── taskdetail/          # Detalhe/edição que grava na hora: título, observações, lembrete, desfazer conclusão, histórico
│   ├── routines/            # Todas as rotinas (ativas e pausadas, filtro por categoria); paleta e ícones
│   ├── routineeditor/       # Criar (rascunho + "Criar rotina") e editar rotina (grava na hora), por seção
│   ├── shopping/            # Aba Listas, tela da lista, cartão-resumo e seletor de lista
│   ├── calendar/            # Mês com marcadores, agenda do dia (rotinas, lembretes, conclusões)
│   └── settings/            # Configurações: resumo diário (liga/desliga, horário), notificações, sobre
├── reminder/
│   ├── ReminderScheduler.kt # AlarmManager (setAlarmClock), só a próxima ocorrência
│   ├── ReminderReceiver.kt  # Alarme de lembrete: só acorda o catch-up
│   ├── ReminderCatchUpRunner.kt + AndroidCatchUpNotifier.kt # entrega atrasados, "N perdidos", reagenda
│   ├── RoutineAlarms.kt     # Um único alarme: o próximo início de bloco de rotina
│   ├── RoutineAlarmReceiver.kt + RoutineNotifications.kt # aviso de início e "Concluir"
│   └── BootReceiver.kt      # Catch-up e reagenda após reiniciar, atualizar o app ou mudar relógio/fuso
├── summary/                 # Resumo diário (alarme, horário das configurações, catch-up) + virada do dia
└── widget/                  # Widgets Glance: "Hoje" e "Resumo do dia"

baselineprofile/             # Gera o Baseline Profile e mede a abertura (Macrobenchmark)
```

O schema do Room fica versionado em `app/schemas/` (base para as migrações; a v4, das rotinas,
é uma `@AutoMigration` conferida contra esses arquivos; a v10, das listas, é a `MIGRATION_9_10`
manual, com backfill — ver `docs/architecture/ADR-0001-listas-de-compras-independentes.md`; a
v11, da v1.8.0, é automática e só acrescenta ao `completion_log` três colunas opcionais com a
data e o lembrete que uma conclusão adiantada sobrescreveu, para o Desfazer devolvê-los).

## Tarefas

Cada tarefa tem **data** (ou "sem data"), **repetição** (a partir da data), **lembrete** (um
horário nesse dia), **observações** e **local** opcional (com atalho para abrir no mapa). Sem
repetição, a tarefa é feita uma vez: concluída, sai das pendentes de vez. Com repetição, cada dia
em que ela acontece tem a própria conclusão. A lista separa *Atrasadas*, *Hoje*, *Próximas*,
*Sem data* e *Concluídas hoje* (`domain/TaskSchedule.kt`).

- **Lista de compras**: o detalhe da tarefa e o painel de nova tarefa (em Tarefas e no
  Calendário) anexam uma lista, nova ("Criar nova") ou existente ("Usar existente"). A linha da
  tarefa mostra o progresso (3/8). Concluir uma tarefa **repetida** zera as marcações e o
  "Desfazer" as devolve; tarefa única não zera.
- **Lembrete com lista**: ações Concluir, Adiar e **Abrir lista**, e título com o progresso
  ("Feira · 3/8"). O lembrete já exibido some ao concluir a tarefa por qualquer caminho ou ao
  excluí-la (o próximo alarme da repetida continua agendado).
- **Concluir adiantado**: concluir antes do dia uma tarefa única com data futura a traz para hoje
  e tira o lembrete futuro; o "Desfazer" devolve a data e o lembrete (melhor esforço: se o app
  foi encerrado no meio, desfaz sem restaurar).
- **Detalhe da tarefa grava na hora** (v1.9.0): o texto é gravado 500 ms depois da última tecla
  e sempre ao sair ou ao ir para segundo plano; não há "Salvar". Cada edição grava só o campo
  mudado (`TaskChange`, `TaskRepository.edit`) sobre a linha mais recente, então concluir pela
  notificação com o detalhe aberto não é desfeito. "Reverter alterações" (menu) devolve os campos
  mudados enquanto a tela está aberta; com a tarefa concluída, a ação principal é **Desfazer
  conclusão**.
- **Rascunho do painel de nova tarefa** (v1.7.1): fechar o painel pelo voltar, tocando fora ou
  arrastando guarda o rascunho; Cancelar descarta e Salvar grava. Um rascunho só, compartilhado
  entre Tarefas e Calendário, em memória (não vai para o banco nem para o backup).

## Rotinas

Blocos de horário que se repetem (ex.: *Treino, 17:20–17:35, seg a sex*), na tela **Hoje**
(inicial desde a v1.8.0, no lugar da aba Rotinas; o endereço `kalendrys://routines/{data}`
continua abrindo a Hoje na data):

- **Tela Hoje**: progresso do dia, próximo item, linha do tempo e as tarefas do dia em
  *Atrasadas*, *Hoje* e *Sem data*, mais **Concluídas (n)** no fim, recolhível e com desmarcar;
  os números são os mesmos do widget Resumo (`TodayOverview`). A tela inteira rola e acompanha a
  virada do dia. O "+" cria tarefa; "Nova rotina" fica no menu.
- **Todas as rotinas** (menu da Hoje): ativas e pausadas, filtro por categoria, editar, pausar,
  retomar e excluir com Desfazer.
- **Pular com Desfazer** (segurar o bloco: "Pular este dia", "Remarcar este dia"): o bloco
  pulado fica esmaecido no dia, com o controle de desfazer. As ações valem para o dia da
  ocorrência, inclusive em bloco remarcado.
- **Linha do tempo do dia**: pílula colorida com ícone; a duração dá a altura mínima e o bloco
  cresce para caber o título e o horário com fonte grande;
  ligação cheia entre blocos que encostam e tracejada nos intervalos; intervalos de 30 min ou
  mais mostram "Tempo livre · 1h 20min". O bloco em andamento ganha "Agora".
- **Faixa da semana** no topo (deslize para trocar de semana), com um ponto por bloco: cheio se
  feito, contorno se pendente. Dá para marcar dias passados; dias futuros não.
- **Editor**: título; quando (início, duração ou "termina às", que pode passar da meia-noite,
  dias da semana, a cada N semanas); aviso no início; categoria; lista; exercícios; aparência
  (ícone entre 35, cor entre 8, com tons claro e escuro); e, em "Mais opções", vigência e
  observações. Desde a v1.9.0, a rotina existente **grava na hora** (só o campo mudado, por
  `RoutineChange`; o alarme só é refeito quando muda horário, dias, vigência ou aviso) e o menu
  tem Pausar, Duplicar, **Reverter alterações** e Excluir. A criação usa rascunho: nada é gravado
  nem agendado antes de "Criar rotina", e a tela fecha depois dele.
- **Aviso no início** pelo canal "Rotinas", com os botões **Concluir**, **Pular** e **Adiar**
  (funcionam com o app fechado). Em bloco com lista, **Pular** dá lugar a **Abrir lista** e o
  título mostra o progresso. Um único alarme no `AlarmManager` cobre todas as rotinas: o próximo início. Bloco
  já feito não avisa. As rotinas seguem o relógio local (inclusive no horário de verão).
- **Excluir arquiva**: a rotina some de hoje em diante, mas os dias passados mantêm o bloco e
  as conclusões. Desde a v1.8.0 "Excluir rotina" não pede confirmação e oferece Desfazer na tela
  de origem. Editar muda também como os dias passados aparecem.
- **Lista de compras**: qualquer rotina pode anexar uma lista (nova ou existente) pelo cartão do
  editor; os itens se editam na tela da lista. Concluir o bloco de **hoje** zera as marcações
  (pela tela, pelo widget ou pela notificação) e o "Desfazer" as devolve. Marcar um dia passado
  não mexe na lista. Arquivar a rotina não apaga a lista. O "Reverter alterações" do editor de
  rotina desfaz só os campos da rotina e o vínculo com a lista; as edições de itens, salvas na
  hora na tela da lista, não são revertidas.
- Aparecem também no **calendário** (seção "Rotinas" do dia), no **resumo das 8h** e nos
  **widgets**.

## Listas

A 4ª aba, **Listas**, reúne as listas de compras, com progresso (3/8), total e a etiqueta de quem
usa cada uma. Lista sem dono é uma lista avulsa (sem lembrete próprio).

- **Tela da lista**: adição rápida pelo campo no rodapé (vários itens seguidos sem fechar o
  teclado); os itens marcados vão para o grupo **Marcados (n)**, que recolhe. Deslizar marca ou
  remove (com "Desfazer") e o toque edita; uma dica única explica o gesto. Total calculado na
  leitura, "Desmarcar todos" (com "Desfazer") e renomear. Tudo é salvo na hora. Marcar um item dá
  um retorno tátil leve.
- **Cartões da aba**: o toque abre a lista; o menu de três pontos renomeia e exclui (com
  confirmação). Estados vazios trazem a ação de criar.
- **Oferta "Concluir tarefa"**: ao marcar o último item, se a lista tem uma tarefa pendente
  (a de origem, quando se chegou pela tarefa, senão a única vinculada pendente), aparece a oferta
  de concluí-la, com "Desfazer". Conta como pendente a tarefa atrasada, de hoje, sem data ou
  única com data futura; tarefa repetida futura e rotinas não recebem a oferta.
- **Progresso** (3/8) aparece também no bloco da timeline, no widget Hoje, no resumo diário e
  nas notificações.
- **Compartilhada**: uma lista pode ter mais de um dono; marcar vale para todos, e o reinício
  disparado por um dono zera a lista para todos.
- **Excluir** uma lista apaga os itens e desfaz o vínculo dos donos (o diálogo cita quem perde).
- As listas antigas, presas a rotinas do tipo Compras, viram listas independentes na atualização
  (Room v10) e no restauro de backups antigos, pela mesma regra (`LegacyShoppingLists`).
- **Backup**: o formato passa a ser o v6 (com as listas e os vínculos). A v1.5.0 lê os backups
  antigos (v1 a v5); um backup v6 não abre na v1.4.1 ou anterior (é recusado como incompatível).

## Resumo diário e widgets

- **Resumo às 8h**: notificação com as tarefas pendentes e as rotinas que ainda vêm no dia,
  em ordem de horário (as tarefas sem horário depois). Usa alarme exato do `AlarmManager` em
  vez de WorkManager, que pode atrasar em economia de bateria. Tem canal próprio ("Resumo
  diário"), que pode ser desligado nas configurações de notificação do Android. Dias sem
  pendências não geram notificação.
- **Widgets** (Jetpack Glance; seguem a **Aparência** do app, claros ou escuros, e o Material
  You quando ligado; no tema claro o fundo é opaco): *Calendário* (4×2, o mês com hoje marcado e, ao lado, as tarefas e rotinas do dia),
  *Hoje* (4×2, tarefas e rotinas pendentes com conclusão direto no widget, toque
  abre a tarefa ou as rotinas, "+" cria uma tarefa) e *Resumo do dia* (2×2, quanto falta e o
  que vem a seguir). Atualizam a cada mudança no banco, quando um bloco começa e na virada do dia.
- **Configurações** (engrenagem na barra das abas): **Aparência** (tema Sistema, Claro ou Escuro
  e Material You opcional; padrão Sistema com Material You desligado, inclusive para quem
  atualiza; vai no backup como campos opcionais), ligar/desligar o resumo e escolher o
  horário (guardado no DataStore; os alarmes se reajustam sozinhos), atalho para as
  notificações do sistema e versão do app.
- **Atalhos do ícone** (toque longo no ícone do app): *Nova tarefa* e *Listas*.
- **Diagnóstico de alarmes** (Configurações): mostra o próximo aviso do Kalendrys (lembrete,
  início de rotina ou resumo), calculado dos dados com as mesmas funções dos agendadores, e não
  o despertador do sistema; sem alarme exato, avisa que pode atrasar.
- **Exportar histórico** (Configurações → Dados): salva um CSV com todas as conclusões de
  tarefas e rotinas onde o usuário escolher (Downloads, Drive...), pelo seletor de arquivos do
  sistema, sem permissão de armazenamento. Colunas: `Dia;Tipo;Título;Horário;Concluída em;Situação`
  (rotina marcada depois fica no dia da ocorrência; "Excluída" para tarefa excluída ou rotina
  arquivada). Formato pensado para o Excel em português: separador `;`, CRLF e UTF-8 com BOM.
- **Testar o resumo sem esperar o horário** (só no build de debug):
  `adb shell am broadcast -n com.kalendrys.app/.summary.DailyReceiver -a com.kalendrys.app.action.DAILY_SUMMARY`

## Alarmes no horário

Lembretes, avisos de rotina e o resumo usam `AlarmManager.setAlarmClock` (helper em
`reminder/ExactAlarms.kt`), não `setExactAndAllowWhileIdle`: no ColorOS (realme) os alarmes
exatos de apps de terceiros ganham uma janela de até 1 hora, mesmo com a bateria "Sem
restrições", e os de despertador não. Por isso aparece o ícone de despertador na barra de status
e o "próximo alarme" na tela de bloqueio. Conferir no aparelho com
`adb shell dumpsys alarm | grep -A2 com.kalendrys.app` (o certo é `window=0`).

**Entrega por catch-up** (v1.6.1, ADR-0003). Sem a permissão de alarme exato o sistema pode
atrasar o alarme, então o alarme é só o despertar. A entrega é feita por um catch-up por domínio
(tarefas, rotinas, resumo), chamado pelo alarme, pelo início do processo, pelo boot e pela volta
do app ao primeiro plano: entrega o que venceu desde a última marca (DataStore, fora do backup),
agenda o próximo e grava a marca, sob Mutex. Até 60 minutos de atraso sai como aviso normal;
lembretes de tarefa mais antigos viram um único "N lembretes perdidos", e avisos de bloco e o
resumo mais antigos são descartados. Bloco com duração já terminado não é avisado, e o resumo
de ontem não sai depois da meia-noite.

## Lembretes recorrentes

A tarefa guarda só a primeira ocorrência (`reminderAt`) e a regra (`reminderRepeat`); as
próximas são calculadas por `ReminderRecurrence`, e o `AlarmManager` só conhece a próxima.
Quando ela toca, o catch-up entrega e agenda a seguinte. O lembrete mensal do dia 31 cai no
último dia dos meses mais curtos e volta ao 31 depois. Em outro fuso, o lembrete mantém o
mesmo instante (18:05 de Brasília toca às 17:05 em Manaus).

## Decisão-chave de design

`CompletionLog` é um **registro de eventos**: cada conclusão vira uma linha
imutável. O calendário (dias com conclusões e o histórico de cada dia) é
*derivado* desse log, e o histórico sobrevive à exclusão da tarefa — ela
aparece como "Tarefa excluída". A única remoção é o "Desfazer" logo após
concluir, que cancela um toque acidental.

## Design

Visual sóbrio e sem gamificação: paleta própria (sem cores do papel de parede),
barras de título grandes, listas agrupadas e ações com "Desfazer" em vez de
confirmações. A exceção é excluir pela tela de detalhe, que pede confirmação
porque não há lista para onde voltar com o "Desfazer". Suporta tema claro e escuro.

O app é só em **português do Brasil**, independente do idioma do aparelho: datas, dias da
semana, meses, seletores de data e hora, widgets, notificações e textos do leitor de tela usam
`AppLocale` (semana começando no domingo). As telas se adaptam à fonte grande do sistema
(testado em 1.0, 1.3 e 2.0).

## Como abrir

1. Android Studio (Ladybug ou mais novo) → Open → selecione esta pasta.
2. Aguarde o Gradle sync e rode no emulador ou aparelho (min SDK 26 / Android 8).
3. Para uso no dia a dia, prefira o build **release** (Build → Select Build Variant → `release`,
   ou `./gradlew :app:installRelease`): o debug é várias vezes mais lento.

## Atualização pelo app

As atualizações chegam por sideload via GitHub Releases: o botão "Buscar
atualizações" (Config → Sobre) consulta a release mais recente e baixa o APK, que
é instalado por cima da versão atual sem perder dados. Por ser um app fora da Play
Store, ao instalar o Android (Play Protect) pode exibir um aviso do tipo "este app
pode ser prejudicial" — é esperado: toque em "Mais detalhes" → "Instalar mesmo
assim".

## Performance

| Medida (emulador, abertura a frio)          | debug    | release + Baseline Profile |
|---------------------------------------------|----------|----------------------------|
| Tempo até a primeira tela (`am start -W`)    | 2.184 ms | 909 ms                     |
| APK                                          | 18,8 MB  | 2,5 MB                     |

Macrobenchmark (mesmo build): 2.583 ms sem compilação → 2.049 ms com o Baseline Profile.

- **Release**: R8 (minify + remoção de recursos) e assinado com a chave de debug, para
  atualizar por cima da versão instalada sem perder dados. Para publicar na Play Store, criar
  uma chave de upload própria.
- **Baseline Profile** (`baselineprofile/`): percorre lista, detalhe, rotinas (criando um
  bloco pelo editor), listas (criando uma pela aba), calendário e configurações. O perfil da
  v1.9.0 não cobre as telas de rotinas (lacuna conhecida, ADR-0002 15.12). Regerar após mudanças grandes de UI, com um emulador ou aparelho Android 13+
  conectado: `./gradlew :app:generateReleaseBaselineProfile`. Medir a abertura:
  `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`. Requer benchmark 1.4.1+
  (a 1.3.x não entende o `pm dump-profiles` do Android 16+). Com o plugin 1.5.0 e o AGP 9.5.1
  há um passo manual (copiar o perfil do aparelho) e o app deve estar sem dados: ver
  "Regerando o Baseline Profile" em `docs/RELEASING.md`.
- **Banco**: as telas só carregam as conclusões do período exibido (hoje ou o mês do
  calendário), com índice em `completedAt`; o histórico inteiro nunca é lido de uma vez.
- **Compose**: `app/compose_stability.conf` declara `java.time`, `Locale` e as coleções do
  Kotlin como estáveis; relatório de skipping sob demanda com
  `./gradlew :app:compileReleaseKotlin -PcomposeReports=true` (saída em `app/build/compose_compiler`).
  A lista de tarefas só recompõe uma linha quando o lembrete dela vence ou o dia vira.
- **StrictMode** ativo no debug: acesso a disco ou rede na thread principal aparece no logcat.

## Próximos passos sugeridos

- [x] Seletor de data/hora no diálogo de nova tarefa
- [x] Calendário com conclusões e lembretes por dia
- [x] Tela de detalhe/edição da tarefa (histórico de conclusões, alterar lembrete)
- [x] Criar tarefa já com lembrete no dia selecionado do calendário
- [x] Lembretes recorrentes (todo dia, dias úteis, semanal, mensal)
- [x] Exportar histórico de conclusões em CSV (tarefas e rotinas)
- [x] Resumo diário às 8h
- [x] Widgets para a tela inicial
- [x] Tela de configurações (horário do resumo, ligar/desligar)
- [x] Rotinas: blocos de horário recorrentes com linha do tempo, aviso e conclusão
- [x] Nova marca: Kalendrys (nome, ícone e identificador `com.kalendrys.app`)
