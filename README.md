# Kalendrys

App Android pessoal de tarefas, rotinas, lembretes e calendário de atividades.
Kotlin + Jetpack Compose + Room + AlarmManager. Funciona offline — a única
funcionalidade que usa rede é o botão manual e opcional "Buscar atualizações"
(Config → Sobre), que consulta o GitHub; todo o resto roda sem internet.

## Arquitetura

```
ui (Compose + ViewModel)  →  data/repository  →  data/local (Room)
                                    ↓
                          reminder (AlarmManager)
domain/ = lógica pura (calendário, recorrência, linha do tempo das rotinas), testável sem Android
```

## Estrutura

```
app/src/main/java/com/kalendrys/app/
├── KalendrysApp.kt          # Application: DI manual, canais de notificação, widgets em sincronia
├── MainActivity.kt          # Entry point + tema + permissão de notificação
├── AppLinks.kt              # Intents que abrem o app numa tarefa, em "nova tarefa" ou nas rotinas de um dia
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt
│   │   ├── entity/          # Task, CompletionLog (log imutável), Routine, RoutineCompletion
│   │   └── dao/             # TaskDao, CompletionLogDao, RoutineDao, RoutineCompletionDao
│   ├── repository/
│   │   ├── TaskRepository.kt    # tarefas: banco + alarmes de lembrete sempre em sincronia
│   │   ├── RoutineRepository.kt # rotinas: banco + alarme do próximo início + notificações
│   │   ├── TodayRepository.kt   # retrato do dia (tarefas + rotinas) para o resumo e os widgets
│   │   └── Clock.kt             # data de hoje que vira sozinha à meia-noite
│   └── settings/            # AppSettings + SettingsRepository (DataStore)
├── domain/
│   ├── CalendarCalculator.kt # grade do mês, início da semana, agrupamento por dia local
│   ├── ReminderRecurrence.kt # ocorrências dos lembretes recorrentes (diário, dias úteis, semanal, mensal)
│   ├── RoutineSchedule.kt    # em que dias e horários cada rotina acontece (relógio local)
│   ├── DayTimeline.kt        # linha do tempo do dia: blocos, ligações e tempo livre
│   ├── RoutineDays/Icon/Color.kt, DurationFormat.kt
│   └── TodayOverview.kt      # retrato do dia: pendentes, rotinas de hoje, "a seguir"
├── ui/
│   ├── theme/               # Paleta azul-ardósia fixa (Material 3) + tipografia
│   ├── components/          # EmptyState, lista agrupada, TimePickerDialog, swipe paginado, pontos, relógio
│   ├── navigation/          # Bottom bar: Tarefas | Rotinas | Calendário
│   ├── tasks/               # Lista em seções, arrastar p/ excluir, desfazer, NewTaskSheet, ReminderPicker
│   ├── taskdetail/          # Detalhe/edição: título, observações, lembrete, status do dia, histórico
│   ├── routines/            # Faixa da semana + linha do tempo; paleta e ícones das rotinas
│   ├── routineeditor/       # Criar/editar rotina: ícone, cor, horário, duração, dias, aviso
│   ├── calendar/            # Mês com marcadores, agenda do dia (rotinas, lembretes, conclusões)
│   └── settings/            # Configurações: resumo diário (liga/desliga, horário), notificações, sobre
├── reminder/
│   ├── ReminderScheduler.kt # AlarmManager (setAlarmClock), só a próxima ocorrência
│   ├── ReminderReceiver.kt  # Mostra a notificação (abre a tarefa) e agenda a ocorrência seguinte
│   ├── RoutineAlarms.kt     # Um único alarme: o próximo início de bloco de rotina
│   ├── RoutineAlarmReceiver.kt + RoutineNotifications.kt # aviso de início e "Concluir"
│   └── BootReceiver.kt      # Reagenda após reiniciar, atualizar o app ou mudar relógio/fuso
├── summary/                 # Resumo diário (alarme exato, horário das configurações) + virada do dia
└── widget/                  # Widgets Glance: "Hoje" e "Resumo do dia"

baselineprofile/             # Gera o Baseline Profile e mede a abertura (Macrobenchmark)
```

O schema do Room fica versionado em `app/schemas/` (base para as migrações; a v4, das rotinas,
é uma `@AutoMigration` conferida contra esses arquivos).

## Tarefas

Cada tarefa tem **data** (ou "sem data"), **repetição** (a partir da data), **lembrete** (um
horário nesse dia), **observações** e **local** opcional (com atalho para abrir no mapa). Sem
repetição, a tarefa é feita uma vez: concluída, sai das pendentes de vez. Com repetição, cada dia
em que ela acontece tem a própria conclusão. A lista separa *Atrasadas*, *Hoje*, *Próximas*,
*Sem data* e *Concluídas hoje* (`domain/TaskSchedule.kt`).

## Rotinas

Blocos de horário que se repetem (ex.: *Treino, 17:20–17:35, seg a sex*), na aba **Rotinas**:

- **Linha do tempo do dia**: pílula colorida com ícone, mais alta quanto maior a duração;
  ligação cheia entre blocos que encostam e tracejada nos intervalos; intervalos de 30 min ou
  mais mostram "Tempo livre · 1h 20min". O bloco em andamento ganha "Agora".
- **Faixa da semana** no topo (deslize para trocar de semana), com um ponto por bloco: cheio se
  feito, contorno se pendente. Dá para marcar dias passados; dias futuros não.
- **Editor**: título, ícone (35), cor (8, com tons claro e escuro), início, duração ou
  "termina às" (pode passar da meia-noite), dias da semana e aviso no início.
- **Aviso no início** pelo canal "Rotinas", com o botão **Concluir** (funciona com o app
  fechado). Um único alarme no `AlarmManager` cobre todas as rotinas: o próximo início. Bloco
  já feito não avisa. As rotinas seguem o relógio local (inclusive no horário de verão).
- **Excluir arquiva**: a rotina some de hoje em diante, mas os dias passados mantêm o bloco e
  as conclusões. Editar muda também como os dias passados aparecem.
- Aparecem também no **calendário** (seção "Rotinas" do dia), no **resumo das 8h** e nos
  **widgets**.

## Resumo diário e widgets

- **Resumo às 8h**: notificação com as tarefas pendentes e as rotinas que ainda vêm no dia,
  em ordem de horário (as tarefas sem horário depois). Usa alarme exato do `AlarmManager` em
  vez de WorkManager, que pode atrasar em economia de bateria. Tem canal próprio ("Resumo
  diário"), que pode ser desligado nas configurações de notificação do Android. Dias sem
  pendências não geram notificação.
- **Widgets** (Jetpack Glance, paleta escura do app sobre fundo 75% opaco, em qualquer tema
  do sistema): *Calendário* (4×2, o mês com hoje marcado e, ao lado, as tarefas e rotinas do dia),
  *Hoje* (4×2, tarefas e rotinas pendentes com conclusão direto no widget, toque
  abre a tarefa ou as rotinas, "+" cria uma tarefa) e *Resumo do dia* (2×2, quanto falta e o
  que vem a seguir). Atualizam a cada mudança no banco, quando um bloco começa e na virada do dia.
- **Configurações** (engrenagem na barra das abas): ligar/desligar o resumo e escolher o
  horário (guardado no DataStore; os alarmes se reajustam sozinhos), atalho para as
  notificações do sistema e versão do app.
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

## Lembretes recorrentes

A tarefa guarda só a primeira ocorrência (`reminderAt`) e a regra (`reminderRepeat`); as
próximas são calculadas por `ReminderRecurrence`, e o `AlarmManager` só conhece a próxima.
Quando ela toca, o `ReminderReceiver` agenda a seguinte. O lembrete mensal do dia 31 cai no
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
  bloco pelo editor), calendário e configurações. Regerar após mudanças grandes de UI, com um emulador ou aparelho Android 13+
  conectado: `./gradlew :app:generateReleaseBaselineProfile`. Medir a abertura:
  `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`. Requer benchmark 1.4.1+
  (a 1.3.x não entende o `pm dump-profiles` do Android 16+).
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
