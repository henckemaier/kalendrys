# ADR-0002 — Design system e roteiro de UI

- **Status:** aceito (aprovado pelo usuário em 2026-11-14)
- **Autor:** Esquadro (arquiteto)
- **Base:** `feat/listas-v1.5.0` (v1.5.0), Compose BOM 2024.12.01 (`material3` 1.3.1), target SDK 35
- **Relacionado:** ADR-0001 (este documento o emenda na seção 8)
- **Entrega:** v1.5.1 a v1.9.0

## 1. Contexto

A UI da v1.5.0 é consistente (tema Material 3 por tokens, edge-to-edge, tema escuro, alvos de
48dp, concluir e marcar com 1 toque), mas tem atritos medidos no emulador e no código:

- Cabeçalho grande e FAB estendido consomem a tela; em paisagem a lista de Tarefas some.
- Fonte do sistema em 2.0 quebra a coluna de horas da timeline e o rótulo da barra inferior.
- Não há nenhum layout adaptativo (zero uso de classes de tamanho de janela).
- Linhas com lápis e lixeira sempre visíveis; adicionar item de lista exige um diálogo por item.
- Editor de rotina com mais de 3 telas, com ícone e cor antes do horário.
- Datas misturam idiomas quando o aparelho não está em português.
- Tarefa e rotina exigem "Salvar"; listas salvam na hora.
- Widgets usam sempre a paleta escura.
- Raios e espaçamentos escritos à mão em cerca de 20 pontos; não há componentes de linha,
  seção ou barra de topo compartilhados.

Material 3 Expressive está na `material3` 1.5.0, que em 2026-11-04 era rc01. A última estável é
a 1.4.0.

## 2. Decisões de produto

| # | Decisão | Versão |
|---|---------|--------|
| U1 | Paleta azul-ardósia como padrão; Material You opcional em Configurações > Aparência, junto com tema sistema/claro/escuro | v1.7.0 |
| U2 | A tela **Hoje** é a tela inicial e substitui a aba Rotinas. Abas: Hoje, Tarefas, Calendário, Listas. A gestão de todas as rotinas fica acessível a partir de Hoje | v1.8.0 |
| U3 | App só em pt-BR: data, hora, mês e dia da semana fixos em pt-BR em qualquer aparelho, sem tradução | v1.5.1 |
| U4 | Lápis e lixeira saem das linhas; entram deslizar, toque e menu, com ações equivalentes para o leitor de tela | v1.7.0 |
| U5 | Salvar na hora em tarefa e rotina, como nas listas, com Desfazer onde fizer sentido | v1.8.0 |
| U6 | Em listas, itens marcados descem para um grupo "Marcados (n)" recolhível | v1.7.0 |
| U7 | Linguagem natural na nova tarefa só como sugestão de chips | depois da v1.9.0 |
| U8 | Tablet e paisagem: só o mínimo (paisagem utilizável, largura máxima de conteúdo; rail com o target 36) | v1.7.0 e v1.9.0 |
| U9 | Material 3 Expressive só com a `material3` estável | v1.9.0 ou depois |
| U10 | Widget de lista e notificação de bloco em andamento | depois da v1.9.0 |
| U11 | Link interno para a lista a partir da notificação | v1.6.0 |

## 3. Decisões de arquitetura

1. **Fundação antes das telas.** Os componentes compartilhados (seção 4) entram na v1.7.0, antes
   da tela Hoje e da reforma dos editores, que os reutilizam.
2. **Atualização de toolchain isolada.** Compose BOM, AGP, Gradle e o que mais for exigido sobem
   numa PATCH sem mudança visual (v1.6.1). O escopo exato depende de um ensaio do Forja: a
   exigência de compileSdk 36 e de Kotlin/KSP mais novos **não foi verificada**.
3. **Nada de alpha ou rc em produção.** O release é automático por tag.
4. **A UI nova não muda as camadas.** `ui -> data/repository -> data/local`, lógica pura em
   `domain/`. A tela Hoje consome `TodayRepository`/`TodayOverview`, que já alimentam widgets e
   o resumo diário.
5. **Dependências novas aprovadas em princípio:** só `material3-adaptive-navigation-suite`, na
   v1.9.0. Qualquer outra passa por parecer.
6. **pt-BR por constante única** (U3): `domain/AppLocale` é a única origem de idioma para
   formatação. Dias da semana curtos vêm de tabela própria em `strings.xml`, não de
   `getDisplayName`, porque o resultado varia entre versões do Android. `MainActivity` aplica a
   configuração pt-BR no contexto base para os seletores do Material 3. `Locale.getDefault()`
   fica proibido em `ui/`, `widget/`, `reminder/` e `summary/`.

## 4. Fundação de design system (v1.7.0)

### 4.1 Tema (`ui/theme`)

- `KalendrysTheme(themeMode, dynamicColor)`: o parâmetro `dynamicColor` já existe; passa a ser
  alimentado pelas configurações.
- `Shapes` próprio no `MaterialTheme` (pequeno 12dp, médio 16dp, grande 20dp) e um objeto de
  espaçamentos. As telas deixam de escrever `RoundedCornerShape(n.dp)` e margens soltas.
- `AppSettings` ganha `themeMode` (SYSTEM, LIGHT, DARK) e `dynamicColor`. Entram no JSON de
  configurações do backup como campos opcionais com padrão, sem subir o `SCHEMA_VERSION`.
- As cores de rotina (pastéis fixos) ganham variante para tema escuro e são testadas contra a
  paleta dinâmica.

### 4.2 Componentes compartilhados (`ui/components`)

| Componente | Papel | Substitui |
|-----------|-------|-----------|
| `AppScaffold` | Insets, largura máxima de conteúdo (640dp), `SnackbarHost`, espaço inferior para o FAB não cobrir a última linha | `Scaffold` repetido em cada tela |
| `AppTopBar` | Barra pequena com título, navegação e ações; ações raras num menu com rótulo | `LargeTopAppBar` nas abas e os ícones soltos dos editores |
| `CollapsingFab` | FAB que recolhe para ícone ao rolar | FAB estendido fixo |
| `ListRow` | Linha padrão: início, título, apoio, fim; toque na linha; semântica mesclada | Linhas de tarefa, lista, item e exercício |
| `SwipeRow` | Deslizar para concluir/marcar e para excluir, com as mesmas ações expostas ao leitor de tela | `SwipeToDismissBox` da lista de tarefas e os botões de linha |
| `SectionHeader` | Título de seção com contagem, marcado como cabeçalho | Títulos de seção feitos à mão |
| `GroupedCard` | Cartão agrupado (evolução do `GroupedShape` atual) | Uso direto de formas |
| `ProgressLine` | Barra + texto "3/8" | Texto solto de progresso |
| `QuickAddField` | Campo fixo no rodapé; Enter adiciona e mantém o foco | Diálogo de novo item |
| `ChipRow` | Fileira de chips rolável, que não corta com fonte grande | `FlowRow`/`Row` de chips |
| `EmptyState` | Estado vazio com a ação principal dentro | `EmptyState` atual, sem ação |
| `ConfirmDialog` | Confirmação de ação destrutiva sem Desfazer | Diálogos repetidos |
| `Haptics` | Retorno tátil ao concluir, marcar e excluir | Nada (não existe hoje) |

Regras:

- Componentes são sem estado e não conhecem ViewModel nem repositório.
- Toda ação de gesto tem equivalente em ação personalizada de acessibilidade.
- Deslizar em linha **não** entra onde o deslize horizontal troca de dia ou mês (Hoje e
  Calendário); lá o círculo de concluir continua.
- Cada componente novo ganha prévia em tema claro, escuro e fonte 2.0.
- Widgets (Glance) não reutilizam esses componentes: ganham `widget/WidgetTheme` com
  `ColorProviders` claro/escuro e cor dinâmica quando a opção estiver ligada.

## 5. Novo modelo de salvar (U5, v1.8.0)

### 5.1 Regra geral

- **Entidade existente** (tarefa ou rotina já criada): cada alteração persiste sozinha. Somem o
  botão "Salvar", o estado `hasChanges`/`canSave` e o diálogo "Descartar alterações?".
- **Entidade nova**: a criação continua sendo um ato explícito.
  - Tarefa: o `NewTaskSheet` mantém o botão de criar (e a ação do teclado passa a criar).
  - Rotina: o editor abre em **modo criação**, com botão "Criar rotina" fixo no rodapé. Nada é
    gravado antes disso. Voltar com campos preenchidos pede confirmação de descarte; é o único
    lugar onde esse diálogo sobrevive.
  - Motivo: criar uma rotina agenda alarmes; uma linha gravada a cada tecla de um título
    incompleto criaria rotinas fantasmas e alarmes indevidos.
- Depois de criada, a rotina passa ao modo edição na mesma tela, já salvando na hora.

> **Corrigido pela seção 15 (P3):** depois de "Criar rotina" a tela **fecha**, como antes; a
> rotina não passa ao modo edição na mesma tela. O desenho do salvar na hora que vale é o da
> 15.2 e da 15.3, na v1.9.0.

### 5.2 Quando grava

- Campos discretos (data, hora, dias, chips, alternadores, cor, ícone, categoria, vínculo de
  lista): gravam imediatamente.
- Campos de texto (título, observações, local): gravam com espera de 500 ms após a última tecla
  e **sempre** ao sair da tela ou quando o app vai para segundo plano.
- A gravação sempre aplica o rascunho sobre a **linha mais recente** do banco (como o
  `applyTo` atual), para não sobrescrever mudanças vindas de fora (pausar, concluir por
  notificação).
- A gravação final ao sair roda num escopo da aplicação, não no `viewModelScope`, que é
  cancelado ao fechar a tela. `KalendrysApp` expõe esse escopo.
- Morte do processo dentro da janela de 500 ms perde no máximo o último trecho digitado. Aceito.

### 5.3 Validação

Estado inválido nunca é gravado. A UI torna os estados inválidos inalcançáveis sempre que
possível:

| Campo | Regra |
|-------|-------|
| Título vazio | Não grava; o campo mostra erro. O banco mantém o último título válido. Ao sair com o campo vazio, o título volta ao último válido |
| Dias da rotina | O último dia selecionado não pode ser desmarcado |
| Vigência | O seletor de fim só oferece datas a partir do início; mover o início para depois do fim limpa o fim |
| Lembrete no passado | Mantém a regra atual do repositório (não agenda) |

Os demais campos continuam gravando enquanto o título está inválido.

### 5.4 Desfazer

| Ação | Desfazer |
|------|----------|
| Excluir tarefa, arquivar rotina, remover item ou exercício | Snackbar "Desfazer" (já existe para tarefa e item) |
| Concluir | Snackbar "Desfazer" (já existe) |
| Edição de campos | "Reverter alterações" no menu do editor, enquanto ele está aberto. Restaura o retrato tirado na abertura, inclusive exercícios |
| Desvincular lista | Snackbar "Desfazer" |

O retrato de exercícios que hoje serve ao "Descartar" passa a servir ao "Reverter alterações".

### 5.5 Efeitos colaterais

- Cada gravação de tarefa refaz o alarme do lembrete; cada gravação de rotina reagenda o
  próximo aviso. As operações já são idempotentes. A espera de 500 ms no texto evita rajadas.
- Widgets já têm espera de 500 ms antes de redesenhar.
- Sem o `BackHandler` de descarte, o voltar preditivo funciona nos editores de entidade
  existente.

### 5.6 Por que na v1.8.0

É a versão que reorganiza o editor de rotina (D3) e o detalhe da tarefa (D1). Fazer antes
obrigaria a mexer duas vezes nas mesmas telas e ViewModels. Até lá, a v1.6.0 segue o modelo
atual (vínculo da lista no `TaskDraft`, com "Salvar").

## 6. Tela Hoje (U2, v1.8.0)

- `AppTab.Today` (rota `today`) substitui `AppTab.Routines` e vira o destino inicial.
- Conteúdo: progresso do dia, próximo item, timeline de blocos, tarefas de hoje e atrasadas,
  progresso das listas vinculadas. Abre em hoje; a faixa da semana continua permitindo ver e
  corrigir outros dias, com "voltar para hoje".
- Nova tela "Todas as rotinas" (rota `routines-all`): lista com ativas e pausadas, para editar,
  pausar e arquivar.
- `LaunchRequest.OpenRoutines` passa a abrir Hoje na data pedida. O endereço
  `kalendrys://routines/{data}` continua sendo aceito, porque notificações e widgets já
  emitidos o carregam.
- As jornadas do Baseline Profile são refeitas.

## 7. Roteiro por versão

Os códigos (A1..F6) são os do parecer de UI de 2026-11-14.

### v1.5.1 — PATCH (polimento)

- Seletor de dias em inglês e lint (já previstos).
- U3: toda a formatação em pt-BR (decisão 6 da seção 3).
- F1 (mínimo): coluna de horas, rótulo da barra inferior e chips com fonte grande.
- B3 (parcial): espaço inferior para o FAB não cobrir a última linha.

### v1.6.0 — MINOR (lista na tarefa, ADR-0001)

- As 5 entregas do ADR-0001.
- E3 + U11: link interno para a lista e ação "Abrir lista" na notificação (seção 8).
- E2: progresso das listas no widget Hoje (é a regra P8 do ADR-0001).
- A5 (parcial): retorno tátil ao marcar item.

### v1.6.1 — PATCH (infraestrutura)

> **Renumerada pela seção 11:** a v1.6.1 passou a ser a correção de lembretes (ADR-0003) e esta
> entrega de infraestrutura passou a ser a **v1.6.2**. O escopo detalhado está na seção 11.

- A2: toolchain e Compose BOM estável.
- F5: opt-in do voltar preditivo no manifesto.
- Baseline Profile regerado. Sem mudança visível.

### v1.7.0 — MINOR (fundação visual e fluxos rápidos)

- A1: design system (seção 4).
- A4 + D7: Aparência em Configurações (U1).
- A5: motion e retorno tátil.
- B2: cabeçalho compacto. B3: FAB que recolhe.
- C1: adição rápida em linha. C3: gestos de deslizar (U4). C5: atalhos do app.
- D5: Listas (barra de progresso, grupo "Marcados (n)" da U6). D8: estados vazios com ação.
- E1: widgets seguem tema e cor do sistema.
- F1 (completo), F2, F4 (mínimo: largura máxima e paisagem utilizável, U8).

### v1.8.0 — MINOR (Hoje e editores)

> **Dividida pela seção 14:** a v1.8.0 ficou com "Hoje e dados" (B1, D2, a lista de tarefas de
> D1 e o Desfazer definitivo). C4, D3 e o detalhe da tarefa foram para a v1.9.0. F3 fica nos
> testes de semântica (14.9).

- B1: tela Hoje (seção 6). D2: timeline.
- D3: editor de rotina reorganizado. D1: lista e detalhe de tarefa.
- C4: salvar na hora (seção 5).
- F3: leitor de tela.

### v1.9.0 — MINOR (adaptativo, calendário e busca)

> **Renumerada pela seção 14:** este escopo passou a ser a **v1.10.0**. A v1.9.0 passou a ser
> "editores e salvar na hora" (C4, D3 e o detalhe da tarefa de D1).

- B5: barra inferior ou rail conforme a janela. F6: target SDK 36.
- D4: calendário recolhível para semana. B4: busca global.
- A3: Material 3 Expressive, **se** a `material3` 1.5.0 já for estável; senão fica para depois.

### Depois

C2 (linguagem natural como sugestão, U7), E4 (widget de lista), E5 (resumo diário com ações),
E6 (bloco em andamento), D6 (arrastar exercícios), F4 completo (lista e detalhe lado a lado).

**Mudança em relação ao roteiro aprovado:** D4 e B4 saíram da v1.8.0 para a v1.9.0. A v1.8.0
ganhou o salvar na hora, que reescreve os dois editores; a v1.9.0 ficou mais leve com a U8.

## 8. Emendas ao ADR-0001

O arquivo do ADR-0001 não foi editado. Valem as emendas abaixo.

**E-1 (U11, v1.6.0) — link interno para a lista.** Substitui o último item da seção 4.5 e o
item "link externo direto para a lista" de "Fora do escopo".

- `AppLinks.openList(listId, taskId?)` com endereço `kalendrys://list/{listId}?taskId={id}`.
- `LaunchRequest.OpenList(listId, taskId?)`: abre a aba Listas e, sobre ela, a tela da lista.
  Lista inexistente cai no estado "não encontrada" que a tela já tem.
- Lembrete de tarefa com lista: ações Concluir, Adiar, **Abrir lista**.
- Aviso de bloco com lista: ações Concluir, **Abrir lista**, Adiar. O Android mostra no máximo
  3 ações, então "Pular" sai do aviso **só nesses blocos** e continua disponível no app.
  Blocos sem lista não mudam. Este ponto é decisão minha e pode ser revisto pelo produto.
- Vira a entrega 6 da v1.6.0. Testes: `AppLinks.parse` para o novo endereço, ações das duas
  notificações com e sem lista, e o caminho notificação > lista no emulador.

**E-2 (U5, v1.8.0) — regra P7.** O vínculo `shoppingListId` deixa de ser revertido por
"Descartar", porque o botão deixa de existir para rotina e tarefa já criadas. Passa a gravar na
hora e é revertido por "Reverter alterações" ou pelo "Desfazer" do desvincular. No modo criação
de rotina o vínculo continua no rascunho. Até a v1.8.0 vale a P7 original.

**E-3 (U2, v1.8.0) — pontos de entrada.** Onde o ADR-0001 cita o editor de rotina como ponto de
entrada, o caminho passa a ser Hoje > bloco ou Hoje > Todas as rotinas.

**E-4 (U6, v1.7.0) — tela da lista.** Itens marcados passam a aparecer no grupo "Marcados (n)".
Não muda dados: `position` e `picked` continuam como estão.

**E-5 (v1.6.0) — regra P3, o que conta como "pendente".** Detalha a seção 3 do ADR-0001 (P3,
oferta de conclusão). Decisão de produto tomada na revisão da v1.6.0. Para a oferta "Concluir
tarefa", uma tarefa vinculada à lista é candidata quando ainda não foi concluída e:

- está atrasada, é de hoje ou não tem data (critério do retrato do dia); **ou**
- é tarefa **única** (`repeat == NONE`) com data **futura**: quem faz a compra antes do dia
  recebe a oferta. Aceitar é uma conclusão comum: a tarefa passa a ser de hoje e perde o
  lembrete futuro, como já faz `TaskRepository.logCompletion`.

Tarefa **repetida** cuja próxima ocorrência é futura (inclusive a já concluída hoje) continua
fora: concluí-la adiantaria a ocorrência seguinte e reiniciaria a lista (P4). O restante da P3
não muda: origem primeiro, senão a única candidata; zero ou mais de uma, sem oferta.

A regra fica em `domain/ShoppingCompletionOffer` (função pura, recebe estado e repetição da
tarefa), não na tela. Testes: tarefa única futura como origem e como única candidata; repetida
futura fora; única futura somada a outra pendente sem origem não gera oferta.

## 9. Riscos

| Risco | Mitigação |
|-------|-----------|
| A fundação toca todas as telas e não há testes de UI instrumentados | Migrar tela a tela; matriz manual claro/escuro, fonte 1.0/1.3/2.0, retrato/paisagem; Baseline Profile regerado |
| Salvar na hora grava estado intermediário ou perde a última edição | Validação por campo; gravação ao sair em escopo da aplicação; testes de ViewModel com tempo virtual |
| Rotinas fantasmas ou alarmes indevidos | Criação explícita (5.1) |
| Atualização de toolchain maior que o previsto | Ensaio antes de fechar a v1.6.1; release isolada |
| Troca da aba inicial confundir o uso atual | Hoje reaproveita a timeline existente; "Todas as rotinas" a um toque |
| Gestos pouco descobríveis | Dica na primeira vez; ações equivalentes no menu e no leitor de tela |
| Material 3 Expressive não estabilizar a tempo | A v1.9.0 não depende dele |

## 10. Testes por versão

- **v1.5.1:** unitários de datas com o idioma padrão do teste em inglês; teste que falha se
  sobrar `Locale.getDefault()` nos pacotes de apresentação; emulador em inglês.
- **v1.6.0:** os do ADR-0001 mais os da emenda E-1.
- **v1.6.1:** suíte completa, `StartupBenchmark`, voltar preditivo nos dois editores.
- **v1.7.0:** `SettingsRepository` e backup com os campos novos; ViewModels de lista com o grupo
  de marcados; matriz manual completa; TalkBack nas ações de `SwipeRow`.
- **v1.8.0:** ViewModels dos editores reescritos (grava na hora, título vazio não grava,
  reverter, gravação ao sair, modo criação); `AppLinks` com o endereço antigo de rotinas;
  repositórios sem rajada de reagendamento.
- **v1.9.0:** janela compacta, média e expandida; target 36 (edge-to-edge, voltar preditivo,
  orientação livre em tela grande).

## 11. Emenda de 2026-11-14 — renumeração e escopo da toolchain

Aprovada pelo usuário em 2026-11-14, depois do release da v1.6.0.

### 11.1 Renumeração

| Versão | Antes | Agora |
|--------|-------|-------|
| v1.6.1 | toolchain, voltar preditivo, Baseline Profile | correção da entrega de lembretes (ADR-0003) |
| v1.6.2 | não existia | toolchain, voltar preditivo (F5), Baseline Profile |

Releases separadas, nesta ordem. Motivos: o lembrete é defeito na função central e a correção é
pequena; e, se um lembrete falhar depois do release, é preciso saber se foi a correção ou o
compilador novo. Onde este documento diz "v1.6.1" para a infraestrutura (seções 3, 7, 9 e 10),
leia-se **v1.6.2**. As versões v1.7.0 em diante não mudam.

### 11.2 Situação de partida e o que mudou no mercado

Repositório na v1.6.0: Gradle 8.10.2, AGP 8.7.3, Kotlin 2.0.21, KSP 2.0.21-1.0.28,
compileSdk e targetSdk 35, minSdk 26, Compose BOM 2024.12.01 (`material3` 1.3.1), Room 2.6.1,
`benchmark` e plugin `baselineprofile` 1.4.1.

O Baseline Profile **já existe**: módulo `:baselineprofile` (gerador, `Journeys`,
`StartupBenchmark`) e `profileinstaller` no app, tudo no catálogo de versões. Regerar não é
dependência nova. Os perfis versionados são da v1.5.0.

Páginas oficiais consultadas em 2026-11-14: Compose BOM estável 2026.09.00 (Compose 1.12.1,
`material3` 1.4.0); a partir do Compose 1.12 são exigidos compileSdk 37 e AGP 9 (mínimo 9.2.0);
AGP estável 9.5.1 (Gradle 9.6.0, JDK 17 ou mais); Kotlin estável 2.4.21; `benchmark` 1.5.0;
Room 2.8.5 (KSP2 a partir do Room 2.7). O BOM mais novo obriga, portanto, o salto de versão
maior do AGP e do Gradle. Isso responde a pendência "não foi verificada" da seção 3, item 2.

### 11.3 Trilhas

- **Trilha 1 (alvo):** Gradle 9.6.x, AGP 9.5.1, Kotlin 2.4.21 (plugin compose na mesma versão),
  KSP compatível, compileSdk 37 (app e `:baselineprofile`), Compose BOM 2026.09.00,
  `benchmark` 1.5.0, Room 2.8.x. O AGP 9 é inevitável antes da v1.9.0; fazê-lo numa PATCH sem
  mudança de tela é o propósito desta versão.
- **Trilha 2 (recuo):** último AGP 8.x, compileSdk 36 e o último BOM com Compose anterior ao
  1.12 (que já traz `material3` 1.4.0). Os números exatos **não foram verificados**. O salto
  para o AGP 9 ficaria para antes da v1.9.0.
- **Ensaio E0** (Forja, 1 dia, branch só de arquivos de build): sobe para a Trilha 1 e anota a
  matriz que fechou e o que quebrou. Se ao fim do prazo não compila, não passa nos testes ou
  exige biblioteca nova, recua para a Trilha 2. O resultado passa por parecer do arquiteto antes
  de seguir.

### 11.4 Escopo

**Entra:**
- Gradle wrapper, AGP, Kotlin, plugin compose, KSP, compileSdk, Compose BOM, `benchmark` e
  plugin `baselineprofile`.
- Room, só porque o KSP novo exige (**não verificado**); sem mexer em entidades, banco na
  versão 10.
- Bibliotecas já existentes (core-ktx, lifecycle, activity, navigation, glance, datastore,
  robolectric, coroutines-test, turbine): só o mínimo exigido para resolver e passar, com a
  justificativa de cada subida no PR.
- Ajustes de sintaxe de build que o AGP 9 obrigar.
- F5: `android:enableOnBackInvokedCallback` no manifesto.
- Baseline Profile regerado, por último.

**Não entra:**
- targetSdk (continua 35; o 36 é o F6 da v1.9.0) e minSdk (continua 26).
- Qualquer versão alpha, beta ou rc; `material3` 1.5 e Expressive.
- Entrada nova nas seções de bibliotecas ou plugins do catálogo.
- Refatoração para APIs novas além do necessário para compilar; avisos de API obsoleta são
  listados no PR, não corrigidos.
- Mudança nas jornadas do perfil.

**Etapas**, um commit cada: E1 build (Gradle, AGP, Kotlin, KSP, compileSdk); E2 bibliotecas;
E3 voltar preditivo; E4 Baseline Profile e `StartupBenchmark`.

### 11.5 Riscos

| Risco | Mitigação |
|-------|-----------|
| Mudança visual não intencional (Compose 1.7 para 1.12, `material3` 1.3.1 para 1.4.0) | Capturas antes e depois; matriz manual |
| R8 e build de release com padrões novos; o release sai por tag | Testar sempre o APK de release, não só o debug |
| Código gerado do Room muda com o KSP novo | Schema exportado idêntico |
| Robolectric com SDK e AGP novos | Nenhum teste ignorado para passar |
| CI com JDK ou Gradle incompatível | Conferir o workflow no ensaio |
| Diálogo de descartar dos editores com o voltar preditivo | Teste manual nos dois editores |
| Geração de perfil ocupa o emulador | Avisar os testers |

### 11.6 Critérios de aceite

1. Diff do catálogo: só linhas de versão; nenhuma biblioteca ou plugin novo; nenhuma versão
   não estável.
2. Com JDK 21: testes unitários, lint e build de release passam. Número de testes igual ou
   maior que o da v1.6.1, nenhum removido ou ignorado; lint sem erros e sem supressão nova.
3. Schema do Room (versão 10) byte a byte igual; versões do banco e do backup inalteradas.
4. targetSdk 35, minSdk 26, assinatura e permissões iguais; no manifesto final a única
   diferença é o atributo do voltar preditivo.
5. Baseline Profile regerado, não vazio, com classes `com.kalendrys.app`, e perfil de
   inicialização presente.
6. `StartupBenchmark` antes e depois no mesmo emulador: sem piora acima de 10% na mediana.
7. Tamanho do APK de release antes e depois informado no PR.
8. APK novo instalado por cima da versão anterior: dados, alarmes e widgets preservados.
9. Capturas antes e depois das telas principais sem diferença relevante.

### 11.7 Plano de teste

- **Lince:** suíte, lint, comparação de schema, manifesto e catálogo; build de release.
- **Coruja, no APK de release:** as 4 abas, detalhe e nova tarefa (painel com teclado), editor
  de rotina, tela da lista, Configurações; claro e escuro; fonte 1.0 e 2.0; rotação; os 3
  seletores de data em pt-BR; deslizar para excluir; voltar preditivo nos dois editores com
  alteração pendente; lembrete com ações, aviso de bloco, resumo diário; widgets Hoje e
  Calendário; backup e restauro; verificador de atualização.

### 11.8 Fechamento da v1.6.2 (2026-11-15)

Registro do que de fato entrou (PR #41) e das decisões tomadas durante a execução. Onde este
item diverge dos anteriores da seção 11, vale este.

#### 11.8.1 Matriz final de versões

A Trilha 1 fechou sem recorrer à alternativa.

| Item | v1.6.1 | v1.6.2 |
|------|--------|--------|
| Gradle (wrapper) | 8.10.2 | 9.6.1, com `distributionSha256Sum` |
| AGP | 8.7.3 | 9.5.1 |
| Kotlin e plugin compose | 2.0.21 | 2.4.21 |
| KSP | 2.0.21-1.0.28 | 2.3.14 |
| compileSdk (app e `:baselineprofile`) | 35 | 37 |
| Compose BOM | 2024.12.01 | 2026.09.00 |
| Room | 2.6.1 | 2.8.5 (banco continua na versão 10) |
| benchmark e plugin `baselineprofile` | 1.4.1 | 1.5.0 |
| core-ktx | 1.15.0 | 1.16.0 |
| lifecycle | 2.8.7 | 2.9.4 |
| targetSdk / minSdk | 35 / 26 | 35 / 26 |
| JDK | 21 local, 17 no CI | 21 local, 17 no CI |

Sem mudança: activity-compose 1.9.3, navigation-compose 2.8.5, glance 1.1.1, datastore 1.1.1,
profileinstaller 1.4.1 e as bibliotecas de teste.

Notas:
- core-ktx e lifecycle subiram só para o catálogo declarar a versão que o release já resolvia
  por transitividade. Regra daqui em diante: o catálogo declara o que vai no APK.
- O KSP passou a ter numeração própria, desacoplada do Kotlin.
- No AGP 9 o Kotlin é embutido: os módulos não aplicam mais o plugin `kotlin-android`, e o
  `jvmTarget` fica no bloco `kotlin { compilerOptions }`.
- Ao subir o Gradle, atualizar o checksum da distribuição e conferir o do `gradle-wrapper.jar`
  (procedimento em `docs/RELEASING.md`).
- Teste de migração do Room no Windows: `AbsolutePathOpenHelperFactory`, só em código de teste.
- Avisos de API obsoleta listados e não corrigidos (`largeTopAppBarColors`,
  `SwipeToDismissBoxState`): as duas telas são reescritas na v1.7.0.

#### 11.8.2 Desvios de execução do E4

1. **`appops RUN_ANY_IN_BACKGROUND=ignore` durante o benchmark.** O WorkManager dos widgets
   religava o app e abortava a partida a frio. Aplicado igual no antes e no depois, e restaurado
   no fim. É condição de medição, não muda o app.
2. **Perfil gerado com os dados do app vazios**, com a cópia dos dados restaurada depois. A
   jornada de rotinas falha quando há muitas rotinas na tela; as jornadas não foram alteradas
   (11.4) e a robustez delas ficou para o Backlog da v1.7.0. As jornadas criam os próprios dados
   de exemplo, então os caminhos percorridos são os mesmos.
3. **`startup-prof` copiado à mão de `additional_test_output`.** O plugin `baselineprofile`
   1.5.0 com o AGP 9.5.1 apaga os perfis versionados e não traz os novos do aparelho. O passo
   manual está em `docs/RELEASING.md`. `baseline-prof.txt` e `startup-prof.txt` são idênticos
   entre si, como já eram na v1.6.1. Acompanhar a correção do plugin.

#### 11.8.3 Critério 6: como medir e o que foi decidido

**Protocolo.** O critério 6 (11.6) é comparado **por pares medidos em sequência**, antes e
depois, no mesmo aparelho e nas mesmas condições. Números de sessões diferentes não se comparam,
e não se usa valor absoluto de uma sessão como limite para outra. Para um par valer:

- memória livre do host acima de 4 GB no início e no fim de cada medição, e nada pesado rodando
  em paralelo;
- p90 até 20% acima da mediana em cada medição;
- de preferência ordem intercalada (antes, depois, depois, antes), com os dois "antes" diferindo
  em até 10%; se o mesmo build varia mais que o limite, a sessão não mede nada;
- compara-se a mediana **com perfil**; o número sem compilação é informativo.

**O que aconteceu.** O mesmo build da v1.6.1 deu 1051, 1394 e 1647 ms de mediana em três
sessões no emulador, num PC dividido com os outros agentes e com a memória livre chegando a
menos de 1 GB. Só um par foi medido em condições equivalentes: 1393,8 ms antes e 1395,3 ms
depois (+0,1%). O par de ordem invertida deu +17,6%, com a memória caindo de 5,2 para 1,5 GB
durante a medição. Além disso, todas as medições do "depois" foram feitas antes da correção
das regras keep (11.8.4), com os workers dos widgets falhando.

**Decisão do usuário (2026-11-15).** Critério 6 **aceito pelo par de +0,1%**, sem nova rodada,
porque este host não tem precisão para medir um limite de 10%. A abertura a frio será
**reavaliada na v1.7.0**, com aparelho físico ou com o host livre, seguindo o protocolo acima.

Fica registrado como sinal a acompanhar: sem compilação, o build novo foi mais lento nas duas
rodadas em que houve par (+12% e +26%). Não é o uso real, porque o perfil vai no APK, mas
indica que o código novo pesa mais a frio e que o perfil compensa.

#### 11.8.4 Risco concretizado: regras keep do R8 no AGP 9

O risco "R8 e build de release com padrões novos" da 11.5 aconteceu. No AGP 9 as regras keep
passam a valer em modo estrito: `-keep class X` sem membros mantém só a classe, não o
construtor sem argumentos. A regra do `work-runtime` 2.7.1 (trazido pelo Glance) para
`InputMerger` é desse tipo; no APK de release o construtor foi removido, nenhum worker rodava e
os widgets ficavam carregando para sempre. O defeito só aparecia no release, e quem o encontrou
foi o teste manual no APK de release. A v1.6.1 publicada não tem o defeito.

Decisões:
- **Correção por regra local** em `app/proguard-rules.pro`, com `{ public <init>(); }`
  explícito, para `GlanceAppWidget`, `ActionCallback` e `InputMerger`.
- **A opção global `android.r8.strictFullModeForKeepRules=false` é vetada.** É opção de
  transição e esconderia o próximo caso.
- **Regra para o futuro:** toda classe criada por nome (reflexão) declara o construtor na regra
  keep. Classes do manifesto já recebem a regra do próprio build.
- **Verificação no CI:** o build-check cruza `usage.txt` e `seeds.txt` do R8 e falha se uma
  classe mantida por regra perdeu o construtor sem argumentos
  (`scripts/check-r8-keep-constructors.sh`; exceções justificadas em
  `app/r8-keep-ctor-exceptions.txt`). É rede de segurança, não substitui o teste abaixo.
- **Toda versão que muda toolchain ou regras do R8 é testada no APK de release**, incluindo
  widgets, ações dos widgets, receivers depois de reiniciar o aparelho, backup e restauro, e
  busca no log por `InstantiationException`, `NoSuchMethodException` e `ClassNotFoundException`.
- Quando o Glance subir, reavaliar a regra do `InputMerger` e removê-la se a biblioteca passar
  a trazê-la correta.

#### 11.8.5 build-check (condição C5)

O `release.yml` só dispara por tag, então uma quebra de toolchain só apareceria na hora de
publicar. Foi criado `.github/workflows/build-check.yml`:

- roda em todo PR para a `main` e por disparo manual;
- permissão só de leitura e **nenhum secret**; sem a keystore, o build de release é assinado
  com a chave de debug e o APK não é publicado;
- repete o preparo do `release.yml` (mesmo runner, mesmos actions fixados por SHA, JDK 17, mesmo
  comando de build) e a conferência de `versionName` e `versionCode` pelo `aapt`, mais a
  verificação das regras keep (11.8.4).

Decisões:
- **O `release.yml` não recebe gatilho de PR.** Ele tem permissão de escrita e a chave de
  assinatura; código de PR ainda não revisado não roda com a chave.
- Os dois workflows mantêm o mesmo preparo: mudou JDK ou action em um, muda no outro no mesmo
  commit.
- O build-check não prova a assinatura com a chave real nem a criação do Release. Risco aceito:
  se a tag falhar, nenhum Release é criado e a tag é refeita.
- Testes unitários e lint no CI ficam para o Backlog da v1.7.0.

## 12. Emenda de 2026-11-16 — escopo e desenho da v1.7.0

Decisões do usuário de 2026-11-15 sobre o parecer de escopo da v1.7.0. Detalha as seções 4 e 7
e, onde divergir delas, vale esta. Base: `main` em 563137e (v1.6.2), toolchain da seção 11.8.1.

### 12.1 Fatiamento

| Versão | Tipo | Conteúdo |
|--------|------|----------|
| **v1.7.0** | MINOR | Fundação visual e fluxos rápidos: tudo que é visível e novo (12.3 a 12.7) |
| **v1.7.1** | PATCH | Acessibilidade e pendências (12.8), logo em seguida |

A v1.8.0 e a v1.9.0 não são renumeradas. Motivo do fatiamento: a v1.7.0 toca todas as telas
sem teste de interface automatizado; tirar dela o que é correção deixa a entrega menor e já
reserva uma versão de ajuste para o que o uso real mostrar.

### 12.2 Regra da casca

A v1.8.0 reescreve a aba Rotinas (que vira Hoje), a timeline, o editor de rotina e o detalhe
da tarefa. Na v1.7.0 essas telas recebem **só a casca**: `AppScaffold`, `AppTopBar` e as formas
e espaçamentos do tema. O miolo delas não é reorganizado agora.

Consequências aceitas até a v1.8.0:
- os exercícios do editor de rotina continuam com lápis e lixeira;
- a faixa de 30 minutos e os títulos longos da timeline com fonte 2.0 continuam como estão;
- a lista de tarefas só troca o `SwipeToDismissBox` pelo `SwipeRow`, sem mudar comportamento.

Limites da versão: **sem migração de Room** (banco na versão 10), **sem subir o
`SCHEMA_VERSION` do backup** (continua 6), targetSdk 35 e minSdk 26.

### 12.3 Aparência (U1)

- `AppSettings` ganha `themeMode` (`SYSTEM`, `LIGHT`, `DARK`) e `dynamicColor`. Padrão, inclusive
  para quem atualiza: `SYSTEM` e cor dinâmica **desligada**. A atualização não muda a cor do app
  sozinha.
- Backup: os dois campos entram no JSON de configurações como opcionais. Restaurar um backup
  **sem** os campos mantém a aparência atual do aparelho; não volta ao padrão.
- **Sem piscar na abertura.** As configurações vêm do DataStore, que é assíncrono. `KalendrysApp`
  expõe a aparência num `StateFlow` carregado no início do processo e a `MainActivity` segura o
  primeiro quadro até o valor chegar. Sem `runBlocking` e sem biblioteca de splash.
- **Janela inicial (acréscimo de 2026-11-16, consulta do Forja na E1).** Segurar o primeiro
  quadro não cobre a splash do sistema nem o fundo da janela, que o Android desenha com o tema
  do aparelho antes de o processo rodar. No Android 12 ou mais (API 31+) o app também informa
  ao sistema o modo noturno escolhido (`UiModeManager.setApplicationNightMode`, API da
  plataforma, sem permissão nem dependência), e o sistema o guarda por app. Regras:
  - O DataStore continua sendo a única fonte da verdade; o modo noturno no sistema é um
    **espelho**. Um único ponto o aplica: um coletor em `KalendrysApp` sobre a aparência,
    inclusive na primeira emissão (cobre atualização e restauro). A tela de Configurações e o
    `BackupManager` só gravam no `SettingsRepository`.
  - A chamada da plataforma fica numa classe pequena fora de `data/`, com a guarda de versão
    dentro dela.
  - O tema do Compose continua decidido pelo `themeMode`, igual em todas as versões do Android;
    não depende de a configuração do processo estar forçada.
  - Segurar o primeiro quadro continua valendo em todas as versões.
  - Trocar o tema recria a Activity, como já acontece ao trocar o tema do aparelho. A
    `MainActivity` não passa a tratar essa mudança por conta própria nesta versão.
  - Os widgets **não** contam com o espelho: são desenhados no processo da tela inicial. O
    `WidgetTheme` (12.5) continua responsável por aplicar o tema escolhido.
  - **Limite aceito:** no Android 8 a 11 (API 26 a 30) a janela inicial segue o tema do
    aparelho; com tema forçado diferente, a splash sai num tema e o app no outro.
  - "Sem piscar" significa: depois do primeiro quadro do app nunca há troca de tema, em
    qualquer versão; e, no API 31+, da segunda abertura a frio em diante a splash já sai no
    tema escolhido. Na primeira abertura depois de uma atualização ou restauro que mude o tema
    pode haver uma recriação.
- A marca do resumo diário (ADR-0003, 4.3.2) não pode reagir à troca de tema. O código já
  observa só os dois campos do resumo (`summaryChanges`); a v1.7.0 acrescenta o teste que prova
  isso com os campos novos.
- `KalendrysTheme(themeMode, dynamicColor)`, `Shapes` próprio (12, 16 e 20dp) e objeto de
  espaçamentos. As cores de rotina ganham variante para tema escuro e são conferidas contra a
  cor dinâmica.

### 12.4 Componentes e gestos

Vale a tabela da seção 4.2, com estes acréscimos:

- `SwipeRow` nasce na API nova de deslizar da `material3` 1.4 e a `AppTopBar` substitui a barra
  grande. Com isso somem os dois avisos de API obsoleta listados na 11.8.1.
- `CollapsingFab` recebe `expanded` como parâmetro; cada tela o deriva do próprio estado de
  rolagem. O componente não conhece a lista.
- Largura máxima de 640dp e insets ficam no `AppScaffold`. Nenhuma biblioteca adaptativa entra
  agora; a aprovada na seção 3 continua para a v1.9.0.
- Movimento (A5): só o que o Compose já oferece (animação de item de lista), respeitando a opção
  do sistema de remover animações. Retorno tátil pelo `Haptics`, sem permissão nova.

Regras fixadas no aceite da E3 (2026-11-16):

- **O teclado encolhe a casca.** A `MainActivity` declara `windowSoftInputMode="adjustResize"`
  e o `AppScaffold` aplica o espaço do teclado na casca inteira: a barra de topo fica no lugar,
  a área visível do conteúdo diminui (o campo focado rola sozinho para dentro dela) e a barra de
  rodapé sobe junto. Nenhuma tela aplica espaço de teclado por conta própria, e o teclado
  **não** entra como preenchimento do conteúdo. Motivo: como preenchimento, a área visível não
  encolhia e o sistema deslocava a janela inteira, levando a barra de topo para fora da tela
  (regressão B-1 da E3; o mesmo deslocamento já existia no editor de tarefa por falta do
  atributo no manifesto). Painéis e diálogos são janelas próprias e cuidam do próprio teclado.
- **Largura máxima sem faixas mortas.** O conteúdo ocupa a largura toda e o que passar de 640dp
  vai para o preenchimento lateral que a tela recebe; a rolagem pega a tela inteira.
- **FAB recolhido em janela baixa.** Em altura compacta (paisagem de celular) o `CollapsingFab`
  já nasce só com o ícone, pela regra de `rememberFabExpanded`, para não cobrir elemento tocável
  com fonte grande. O rótulo para o leitor de tela não muda.
- **Ação de navegação da barra tem rótulo escrito.** "Hoje" é botão de texto, não ícone. Ícone
  sozinho fica para ações universais (voltar, configurações, mais opções).
- **Ordem das ações da barra.** O `AppTopBar` desenha as ações na ordem em que a tela as passa,
  seja ícone ou texto. A ação que aparece e some (como "Hoje") vem **antes** das permanentes,
  para que nenhuma ação permanente mude de lugar; nos editores, "Salvar" é a última antes do
  menu de mais opções.
- **A barra de topo é fixa**, não recolhe ao rolar. Decisão tomada com as capturas de paisagem:
  as 4 abas mostram conteúdo no primeiro quadro com fonte 1.0.
- **O clique mora na linha.** `GroupedCard` não tem toque próprio; o clique e o rótulo ficam no
  `ListRow`, para cada cartão ser um nó só para o leitor de tela.
- **`SwipeRow`:** o deslize só dispara depois de 40% da largura; o retorno tátil do deslize é do
  componente; uma ação que tira o item da lista (`removesRow`) exige que quem chama garanta a
  saída do item.

Regras fixadas na revisão da E4 e no fechamento (2026-11-16):

- **O estado do item vai no nó da linha.** A linha de item de lista expõe "Marcado" ou "Não
  marcado" como descrição de estado no próprio nó; texto riscado não é lido pelo leitor de tela.
  A linha **não** vira caixa de marcar: o toque dela é Editar, e marcar é ação de deslize com
  ação equivalente no leitor de tela. A caixa de marcar visual fica fora da semântica.
- **Cabeçalho que recolhe é um nó só.** Em "Marcados (n)", o mesmo nó tem o texto, o papel de
  cabeçalho e a ação de recolher ou abrir.
- **A lista acompanha a adição rápida.** Depois de adicionar pelo `QuickAddField`, a tela rola
  até o item novo, esperando ele ser posicionado. É regra da tela, não do componente.
- **O preenchimento do `Scaffold` não vai para `remember`.** O objeto que o `Scaffold` entrega é
  reaproveitado e os valores mudam sem o objeto mudar; o `AppScaffold` calcula o preenchimento
  do conteúdo a cada composição.
- **Folga do FAB limitada à área útil.** A folga de fim de lista para o FAB (104dp) nunca passa
  do espaço entre as barras menos uma linha de 48dp (`fabClearanceFor`). Motivo: com cabeçalho
  alto em paisagem (aba Rotinas) a folga fixa era maior que a área útil e a lista rolava até o
  último bloco sumir sob a barra (S-1). Em retrato vale a folga inteira. A regra fica só no
  `AppScaffold`.

Limites aceitos nesta versão: com fonte 2.0 em paisagem a aba Tarefas não mostra nenhuma tarefa
no primeiro quadro, e o editor de rotina com teclado fica com área útil mínima. Os dois dependem
do miolo das telas e são revistos na v1.8.0.

Gestos por tela (U4). Decisão de arquitetura aceita pelo usuário; o produto pode rever.

| Onde | Toque | Deslizar | Menu |
|------|-------|----------|------|
| Item de lista | Editar | Marcar/desmarcar; remover com Desfazer | — |
| Cartão de lista | Abrir | — | Renomear; excluir com confirmação |
| Categoria de rotina | Editar | — | Excluir com confirmação |
| Tarefa (lista) | Como hoje | Como hoje, via `SwipeRow` | — |
| Exercício (editor) | Sem mudança até a v1.8.0 | | |

Excluir uma lista apaga muitos itens de uma vez, por isso pede confirmação e não cabe num
deslize. Uma dica única explica o gesto: ela fica na tela **até ser dispensada** ("Entendi") e
depois não volta mais; "única" quer dizer dispensada uma vez, não mostrada uma vez. O registro
de que ela foi dispensada fica no DataStore, fora de `AppSettings` e fora do backup.

Listas (U6, emenda E-4): o grupo "Marcados (n)" é calculado no ViewModel; recolhido ou aberto é
estado de tela e não vai para o banco. O `QuickAddField` usa a operação de adicionar item que
já existe. A regra que esconde o "3/8" de lista vazia passa a ter uma origem só, em `domain/`,
usada pelo `ProgressLine`.

### 12.5 Widgets (E1)

- **Os widgets seguem a Aparência do app** (decisão do usuário): sistema, claro ou escuro, e
  cor dinâmica quando ligada. Quem usa o aparelho em tema claro e não mexer na configuração
  passa a ver widgets claros. É mudança visível e vai nas notas da versão.
- `widget/WidgetTheme` concentra as cores (claro, escuro e dinâmica), inclusive as fixas que
  hoje estão em `KalendrysWidgets` e a escolha da paleta de rotina.
- `KalendrysApp` redesenha os widgets quando a Aparência muda, e só quando muda. Os widgets
  leem a Aparência do DataStore, porque o processo pode ter sido aberto só pelo widget.
- **Fundo claro opaco.** A variante clara, inclusive a de cor dinâmica, usa fundo sem
  transparência; os 75% de opacidade ficam só na variante escura. Motivo: o fundo claro
  translúcido sobre papel de parede escuro virava cinza médio e deixava de parecer a superfície
  do app.
- **Cores em par, do mesmo esquema.** Toda forma colorida com texto por cima sai do mesmo
  esquema que o texto. O círculo do dia de hoje no widget Calendário é um desenho tingido com a
  cor do esquema em uso, não uma cor fixa no recurso; com cor fixa, o tema claro dava número
  branco sobre azul-claro.
- Limite aceito: com cor dinâmica, as cores do papel de parede são lidas quando o widget é
  desenhado; trocar o papel de parede só aparece no redesenho seguinte.
- Os rótulos abreviados de dia e mês dos widgets e do seletor de lembrete passam a ser montados
  por tabela própria (seção 3, decisão 6), nos três pontos que ainda usam o formatador.

### 12.6 Atalhos do app (C5)

- Dois atalhos fixos: **Nova tarefa** e **Listas**. Arquivo `res/xml/shortcuts.xml`, sem
  dependência nova.
- Os atalhos usam endereços `kalendrys://`, resolvidos pelo `AppLinks` como os demais.
  `AppLinks` ganha o endereço de nova tarefa e `LaunchRequest.NewTask`, que abre a aba Tarefas
  com o painel de nova tarefa.
- O manifesto ganha a referência aos atalhos na Activity principal. Vale a regra da 11.8.4:
  teste no APK de release.

### 12.7 Testes, CI e perfil

- **Dependências de teste aprovadas pelo usuário:** `androidx.compose.ui:ui-test-junit4`
  (`testImplementation`) e `ui-test-manifest` (`debugImplementation`), pela BOM, sem linha de
  versão nova. Nada vai para o APK de release. Condição: ensaio na E0; **não foi verificado** se
  o Robolectric 4.14.1 roda testes de Compose 1.12 neste projeto. Se o ensaio exigir subir o
  Robolectric ou outra biblioteca, volta para parecer.
- Uso: contrato de acessibilidade dos componentes (toda ação de gesto com ação equivalente para
  o leitor de tela) e foco do `QuickAddField`.
- O build-check passa a rodar a suíte e o lint em todo PR.
- **As jornadas do Baseline Profile mudam nesta versão**, revogando para a v1.7.0 o "não entra"
  da 11.4: FAB e cabeçalho mudam, e elas precisam rolar a lista e não criar dados duplicados.
  O perfil é regerado no fechamento, com o passo manual do `docs/RELEASING.md`.
- Abertura a frio reavaliada no fechamento pelo protocolo da 11.8.3, com aparelho físico ou
  host livre.
- **Fechamento da v1.7.0 (2026-11-16): abertura a frio NÃO MEDIDA, por decisão do usuário.** O
  host tinha cerca de 2 GB livres e o emulador 3,7 GB, abaixo dos 4 GB livres que o protocolo
  da 11.8.3 exige; medir assim daria número sem valor. O critério 6 da 11.6 fica **aceito sem
  medição**, não cumprido. O que foi conferido no lugar (relato do time, não refeito pelo
  arquiteto):
  - Baseline Profile regerado com o código final: 32.144 linhas, 3.224 de `com.kalendrys.app`,
    incluindo `MainActivity`, `KalendrysApp` e a leitura da Aparência; `baseline.prof` e
    `baseline.profm` presentes no APK.
  - APK de release: 3.246.230 → 3.306.319 bytes (+1,9%).
  - O ponto de atenção desta versão é a leitura da Aparência antes do primeiro quadro (12.3),
    com teto de 1 s; não há número que mostre o custo dela.
  - **Pendência:** medir pelo protocolo da 11.8.3, em aparelho físico ou host livre, contra a
    v1.6.2 (563137e), junto da passada de acessibilidade da v1.7.1. Se a v1.7.0 ficar mais de
    10% acima, vira defeito da v1.7.1.
- Limpeza: sai a entrada `kotlin-android` do catálogo e do `build.gradle.kts` da raiz.

### 12.8 v1.7.1

- Alvo de toque de 48dp no seletor de dias da semana (F2).
- Diagnóstico de alarmes correto quando não há permissão de alarme exato.
- Painel de nova tarefa: **o rascunho é guardado enquanto o app estiver aberto** e só é
  descartado no Cancelar (decisão do usuário); `resolveListId` sai do arquivo de tela para o
  repositório; título cortado em paisagem.
- Passada de leitor de tela nas telas migradas, e o que o uso da v1.7.0 mostrar.
- Medição da abertura a frio que ficou pendente no fechamento da v1.7.0 (12.7).
- Toda mensagem com "Desfazer" passa a usar a duração longa, em todas as telas de uma vez.
- Campo de busca da aba Tarefas, que fica meio encoberto pela barra com o teclado aberto.

### 12.9 Etapas e critérios de aceite da v1.7.0

Um PR de integração, um commit ou grupo de commits por etapa. E5 e E6 podem correr em paralelo
depois da E1. Revisão de aderência do arquiteto ao fim da E1, ao fim da E2 (antes de a E3
começar, porque a E2 é o contrato do resto) e antes da E7.

| Etapa | Conteúdo | Aceite |
|-------|----------|--------|
| E0 | Preparo sem mudança visível: suíte e lint no build-check, limpeza do catálogo, ensaio do ui-test, jornadas robustas | build-check verde com suíte e lint; jornada passa 2 vezes seguidas com dados cheios sem criar rotina duplicada |
| E1 | Tema e Aparência (12.3) | 4 combinações de tema e cor; abrir a frio com tema forçado diferente do sistema sem piscar; backup ida e volta com e sem os campos; teste da marca do resumo |
| E2 | Componentes (12.4) e a aba Listas como tela piloto | Prévias em claro, escuro e fonte 2.0; testes de semântica do `SwipeRow` e do foco do `QuickAddField` |
| E3 | Casca em todas as telas: `AppScaffold`, `AppTopBar`, `CollapsingFab`, formas do tema; B2, B3, F4 mínimo | Nenhum `LargeTopAppBar`, FAB estendido fixo ou `RoundedCornerShape` solto em `ui/`; paisagem com conteúdo no primeiro quadro nas 4 abas; última linha nunca coberta pelo FAB |
| E4 | Listas e fluxos: D5, U6, C1, U4, D8, retorno tátil | Adicionar 5 itens seguidos sem fechar o teclado; marcar manda para "Marcados" e desmarcar devolve à posição; toda ação de gesto disponível no leitor de tela |
| E5 | Widgets (12.5) | Os 3 widgets nas 4 combinações; trocar a Aparência redesenha em segundos; rótulos iguais em qualquer versão do Android |
| E6 | Atalhos (12.6) | Abrem o destino certo com o app fechado e aberto; testes do `AppLinks` |
| E7 | Fechamento: perfil regerado, abertura a frio, tamanho do APK, matriz manual | Critérios 5 a 9 da 11.6, com o critério 6 medido pelo protocolo da 11.8.3 |

Matriz manual: claro e escuro; fonte 1.0, 1.3 e 2.0; retrato e paisagem; cor dinâmica com dois
papéis de parede; capturas antes e depois das telas principais. Testes de emulador no APK de
release.

### 12.10 Riscos

| Risco | Mitigação |
|-------|-----------|
| Mudança em todas as telas sem teste de interface | E3 separada do miolo; critério por busca no código; matriz manual; capturas |
| Piscar de tema ou custo na abertura a frio | Desenho da 12.3; medição na E7 (**não feita**, ver 12.7; pendente para a v1.7.1) |
| Teclado e insets no `QuickAddField` com edge-to-edge | Teste manual com e sem teclado, retrato e paisagem |
| Voltar preditivo com painéis e o FAB novo | Roteiro do voltar preditivo da v1.6.2 repetido |
| Contraste das cores de rotina com cor dinâmica | Dois papéis de parede na matriz |
| Manifesto e classes criadas por nome (atalhos, widgets) | APK de release e verificação das regras keep (11.8.4) |
| Perfil não comparável linha a linha com o da v1.6.2 | Esperado; conferir critério 5 da 11.6 |

### 12.11 Triagem do backlog

- **v1.7.0:** título da barra de topo com fonte 2.0; rótulos abreviados de data; paisagem na aba
  Rotinas até onde o cabeçalho compacto resolver; regra única do "3/8"; teste da marca do
  resumo; jornadas do perfil; limpeza do catálogo; abertura a frio; suíte e lint no CI.
- **v1.7.1:** os itens da 12.8.
- **v1.8.0:** Desfazer definitivo da tarefa única adiantada e remoção do `TaskDraft.rebase`;
  Desfazer ao excluir pelo detalhe; repetição personalizada; rotina pausada que some da aba
  (resolve com "Todas as rotinas"); desfazer o Pular (resolve na tela Hoje); entrega do
  catch-up antes de gravar com título antigo ou aviso que pisca (reavaliar com o salvar na
  hora); timeline com fonte 2.0.
- **Depois da v1.9.0:** alarme próprio do widget Resumo, junto com o bloco em andamento (U10).
- **Só acompanhar:** correção do plugin `baselineprofile`; regra do `InputMerger` quando o
  Glance subir; ANR único visto no emulador; adiamento perdido ao revogar o alarme exato.
- **Encerrados:** Desfazer cobrindo a última linha e resumo cruzando a meia-noite (aceitos).

---

## 13. Emenda de 2026-11-16 — escopo e desenho da v1.7.1

Versão PATCH: sem migração (banco na versão 10), sem mudança no backup (versão 6), sem
dependência nova e sem mudança no manifesto. Parte da 12.8. As decisões D1 a D3 são do usuário.

### 13.1 Painel de nova tarefa

- **Rascunho em memória (decisão do usuário, P5).** Os campos do painel saem de dentro dele e
  passam para um `NewTaskDraft` (classe de dados pura, em `domain/`), guardado por um
  `NewTaskDraftStore` criado no `KalendrysApp` e entregue ao `TaskViewModel` e ao
  `CalendarViewModel`. O painel fica sem estado: recebe o rascunho e devolve as mudanças.
  Nada vai para o banco, o DataStore ou o backup.
- **Regras:** voltar, tocar fora ou arrastar para baixo fecha o painel e **guarda** o rascunho;
  Cancelar **descarta**; Salvar grava a tarefa e descarta. O rascunho dura enquanto o processo
  viver.
- **Um rascunho só (D2),** compartilhado entre a aba Tarefas e o Calendário. Aberto pelo
  Calendário, a data é o dia tocado, a não ser que o usuário já tenha mudado a data no rascunho.
- **Perda aceita:** se o sistema encerrar o processo com o painel aberto em segundo plano, o
  rascunho se perde (antes ele voltava). É o custo de não persistir.
- `NewTaskList` vai para `domain/` e a regra de qual lista gravar (`resolveListId`) vira método
  do `ShoppingRepository`; `ui/calendar` deixa de importar de `ui/tasks`. Criar lista e tarefa
  numa transação só fica para a v1.8.0, com a reforma do painel.
- A data sugerida (hoje, ou o dia tocado no Calendário) vale enquanto o usuário não mexer na
  data do rascunho; depois vale a dele. Um rascunho guardado e reaberto noutro dia acompanha a
  data nova.
- O store é parâmetro obrigatório dos dois ViewModels, sem valor padrão, para nenhuma tela
  ficar com um rascunho próprio por engano.
- Para a v1.8.0, na reforma do painel: as operações do rascunho e a observação das listas estão
  repetidas nos dois ViewModels. Não foram para o `KalendrysApp` para não pôr uma leitura do
  banco na abertura do app.
- Título do painel: fica fixo, fora da área que rola, **só em janela de altura normal**. Em
  altura compacta (paisagem de celular, o mesmo corte do FAB recolhido) o título rola junto com
  os campos. Motivo: com o título fixo e o teclado aberto em paisagem sobrava tão pouca altura
  que o Salvar ficava inalcançável (matriz da v1.7.1). **Limite aceito:** em paisagem com
  teclado o título pode sair de cena ao rolar; fica para a reforma do painel na v1.8.0.

### 13.2 Seletor de dias da semana

Sete alvos de 48dp somam 336dp e não cabem numa tela de 360dp com as margens (312dp úteis).
Regra: cada dia é uma célula com **48dp de altura** e largura de um sétimo da linha, sem vão
entre vizinhos; o círculo visual continua com 40dp. **Limite aceito:** em tela de 360dp a
célula tem cerca de 44dp de largura. Com fonte grande a linha continua quebrando em duas.

### 13.3 Diagnóstico de alarmes

- **Defeito:** a linha "Próximo alarme" lia o próximo despertador do sistema, que é de qualquer
  app. Sem permissão de alarme exato dizia "Nenhum" mesmo com lembretes agendados; com a
  permissão, podia mostrar o despertador do Relógio como se fosse do Kalendrys.
- **Regra (D3):** a linha mostra o próximo alarme **do app**, calculado dos dados com as mesmas
  funções puras que os agendadores usam (`ReminderRecurrence.nextTriggerMillis`,
  `RoutineSchedule.nextStart` e o horário do resumo diário). Uma função pura em `domain/`
  escolhe o mais próximo; a tela ganha um ViewModel que lê os repositórios e não consulta mais
  o despertador do sistema. Sem permissão exata, mostra o horário e avisa que pode atrasar.
- A tela **não** tem lógica própria de horário: se o cálculo dos agendadores mudar, o
  diagnóstico muda junto. Um teste compara os dois caminhos.
- **Fontes dos três candidatos** (decisão de 2026-11-16, na E3): o conjunto de tarefas com
  lembrete pendente é uma função só do `TaskRepository`, usada pelo reagendamento e pelo
  diagnóstico; nas rotinas, o candidato é o próximo início **que avisa** (rotinas com aviso
  ligado), lido pelo `RoutineRepository` sem agendar nada; o resumo vem de uma função pura
  usada também pelo `DailyScheduler`. O alarme da virada do dia e o alarme silencioso de
  rotina sem aviso não entram: não avisam o usuário.
- **O que a linha mostra é o próximo aviso previsto, não uma leitura do sistema.** O
  agendamento das rotinas é em cadeia (cada alarme agenda o seguinte), então o horário mostrado
  pode ainda não estar agendado.
- **Limite aceito: adiamentos ficam fora.** "Adiar" agenda um alarme direto, sem gravar nos
  dados; com um adiamento pendente, o diagnóstico pode mostrar um horário posterior ao do aviso
  adiado. Gravar o adiamento seria mudança de dados e não cabe em PATCH.
- **Limite aceito: resumo em dia vazio.** O resumo diário não notifica quando não há tarefa
  pendente nem rotina adiante; num dia assim o diagnóstico pode anunciar um resumo previsto que
  não aparece.
- **O diagnóstico mostra o próximo aviso que será ENTREGUE** (decisão na matriz, D-71-1). O
  agendador marca o alarme da ocorrência mesmo quando ela já foi concluída no dia, e a entrega
  a descarta; o diagnóstico pula essas ocorrências, de tarefa repetida e de rotina, usando o
  **mesmo predicado** que a entrega usa, extraído para uma função pura compartilhada. Aqui o
  diagnóstico pode divergir do instante que está no sistema, de propósito.
- Para a v1.8.0, com a reforma do Desfazer: o agendador deixar de marcar alarme para ocorrência
  já concluída. Não entra em PATCH porque desfazer a conclusão no mesmo dia teria de devolver
  o alarme, e hoje isso funciona justamente porque o alarme continua lá.

### 13.4 Mensagens com "Desfazer"

Toda mensagem com "Desfazer" usa a duração longa (cerca de 10 s), por uma função única em
`ui/components`; nenhuma tela escolhe a duração. Uma ação nova substitui a mensagem anterior,
sem fila. A mensagem cobre a última linha por mais tempo; continua aceito como sobreposição
transitória.

### 13.5 Miudezas

- Contador da lista cortado como "2/" na aba Rotinas com fonte 1.3: a etiqueta de progresso
  nunca quebra nem encolhe; quem cede espaço é o texto vizinho. Se exigir rearranjar a linha do
  bloco, fica para a v1.8.0.
- Campo de busca da aba Tarefas meio encoberto com o teclado: primeiro a causa; corrige se for
  na casca ou no preenchimento da tela, senão v1.8.0.
- CI: resumo de testes e lint no log do build-check; `setup-java` v5 nos dois workflows no
  mesmo commit, com SHA fixado e configuração idêntica, porque o `release.yml` só roda na tag.
- **Não entra:** o resto dos problemas de fonte 2.0 (faixa de 30 min e títulos longos) depende
  todo da timeline e vai para a v1.8.0.

### 13.6 Aparelho físico (D1)

O usuário tem um aparelho físico com depuração USB (Android 16) e **o Kalendrys instalado nele
com dados reais**.

- **Regra de segurança dos dados:** ninguém desinstala o app, limpa os dados nem instala por
  cima nesse aparelho antes de o usuário confirmar que exportou o backup. A variante de
  medição usa o mesmo pacote com outra assinatura, então instalá-la exige desinstalar o app e
  apaga os dados. Depois da medição, o usuário reinstala a versão publicada e restaura o backup.
- **Leitor de tela com fala real:** o TalkBack não aceita gesto injetado, então nenhum agente
  faz essa passada. O usuário executa um roteiro curto escrito pela tester (uma linha por
  verificação, resposta sim ou não) no APK de release. Cobre o que ficou sem fala na v1.7.0:
  abrir do cartão de lista, estado do item, cabeçalho "Marcados", ações de deslize, botão
  "Hoje", FAB recolhido e seletor de dias.
- **Abertura a frio (pendência da 12.7):** protocolo da 11.8.3 no aparelho, v1.6.2 (563137e)
  contra a v1.7.1 candidata, na ordem antes, depois, depois, antes. A v1.7.0 só é medida se a
  v1.7.1 passar de 10%, para saber de onde veio a diferença. Medição fora do protocolo não
  fecha a pendência.
- **Protocolo em aparelho físico** (decisão de 2026-11-16): o limite de 4 GB livres no host da
  11.8.3 existe porque o emulador roda no host. Em aparelho físico quem abre o app é o
  aparelho, então esse limite **não se aplica**; a memória do host é só anotada. No lugar dele
  valem as condições do aparelho: mesma faixa de bateria e de temperatura no início e no fim,
  sem modo de economia, tela ligada, nada instalando ou atualizando, e nenhum Gradle rodando no
  host durante as medidas. Os demais critérios não mudam: ordem antes, depois, depois, antes;
  os dois "antes" a até 10% um do outro; p90 até 20% acima da mediana.

### 13.7 Etapas e aceite

| Etapa | Conteúdo | Aceite |
|-------|----------|--------|
| E1 | Painel de nova tarefa (13.1) | Fechar pelo voltar e reabrir traz tudo de volta, também trocando de aba, girando a tela, pelo Calendário e pelo atalho; Cancelar e Salvar limpam; título inteiro em paisagem com fonte 1.0 e 2.0; `ui/calendar` não importa tipo de dado nem regra de `ui/tasks` (só o painel compartilhado); testes do rascunho e do store |
| E2 | Seletor de dias (13.2) | Teste de semântica: 48dp de altura de toque nos 7 dias, sem vão; sem corte em 360dp e em fonte 2.0; nome do dia e estado continuam lidos |
| E3 | Diagnóstico (13.3) | Mostra o mais próximo entre tarefa, rotina e resumo; "Nenhum" sem nada agendado; horário com aviso sem permissão exata; despertador do Relógio não aparece; relê ao voltar da tela do sistema |
| E4 | Desfazer (13.4) | Nenhuma duração curta junto de "Desfazer" em `ui/`; duas ações seguidas mostram só a última; Desfazer desfaz a ação certa |
| E5 | Miudezas (13.5) | "2/3" inteiro com fonte 1.0, 1.3 e 2.0; causa da busca registrada com captura |
| E6 | Fechamento | Roteiro de leitor de tela executado; abertura a frio pelo protocolo; perfil regerado com o passo manual; tamanho do APK; testes de emulador no APK de release |

Revisão do arquiteto na E1 e na E3. O resultado da abertura a frio e da passada de leitor de
tela é registrado na 13.9.

### 13.8 Riscos

| Risco | Mitigação |
|-------|-----------|
| Rascunho em memória compartilhado por duas telas é estado global novo | Um dono só (o store), painel sem estado, testes do store |
| Diagnóstico calculado diverge do que está agendado | Mesmas funções dos agendadores; teste comparando os dois caminhos |
| Perda dos dados reais do usuário na medição | Regra da 13.6: nada no aparelho antes do backup confirmado |
| Primeira execução do `release.yml` com a action nova é a própria publicação | Configuração idêntica à do build-check, que roda no PR |

### 13.9 Fechamento da v1.7.1

#### Abertura a frio (2026-11-16): MEDIDA, critério cumprido

Fecha a pendência da 12.7 e a da 11.8.3. Medida em aparelho físico (Android 16), pelo protocolo
da 11.8.3 com a regra de aparelho da 13.6: `StartupBenchmark`, 10 aberturas a frio por rodada,
tempo até o primeiro quadro, ordem antes, depois, depois, antes, sem Gradle no host durante as
medidas. Números do time; o arquiteto conferiu as contas, não refez a medição.

| Rodada | Versão | Mediana (ms) | p90 (ms) | p90 sobre a mediana |
|--------|--------|--------------|----------|---------------------|
| A1 | v1.6.2 (563137e) | 235,4 | 247,5 | +5,1% |
| D1 | v1.7.1 | 227,3 | 236,5 | +4,1% |
| D2 | v1.7.1 | 227,4 | 241,6 | +6,2% |
| A2 | v1.6.2 (563137e) | 225,7 | 246,3 | +9,1% |

- **Validade:** p90 dentro de 20% nas quatro rodadas; os dois "antes" diferem 4,3%.
- **Resultado:** média dos "antes" 230,6 ms contra 227,4 ms dos "depois": **-1,4%**. Por par,
  -3,4% e +0,8%. A v1.7.1 não é mais lenta que a v1.6.2; a leitura da Aparência antes do
  primeiro quadro (12.3) não custou tempo mensurável. Como ficou abaixo de 10%, a v1.7.0 não
  foi medida, e a regra dos 10% da 12.7 não gera defeito.
- **Condições do aparelho:** USB, bateria em 100% nas quatro, sem modo de economia, sem
  limitação térmica, nada instalando. **Desvio anotado:** a A1 começou cerca de 4 °C mais fria
  (29,8 °C, contra 33,8 a 34,5 °C nas outras) e foi a mais lenta. Não invalida a sessão: os
  dois "antes" concordam dentro do limite e a conclusão vale mesmo no pior par. Nas próximas
  medições, uma rodada de aquecimento descartada antes da A1.
- **Sem perfil (informativo):** só a A1, v1.6.2 sem compilação: 278,5 ms (p90 289,3). Não há
  o par da v1.7.1, então não mede o ganho do perfil nesta versão.
- **Host:** 3,25 a 3,84 GB livres, só anotado (13.6).
- **Perfil e tamanho:** Baseline Profile regerado com 34.362 linhas, 4.125 do app; APK de
  release com 3.308.867 bytes (+0,08% sobre a v1.7.0).

#### Leitor de tela com fala real: NÃO VERIFICADO, por decisão do usuário (2026-11-17)

A passada de leitor de tela em aparelho físico prevista na 13.6 **não foi feita**: o usuário a
tirou do escopo da v1.7.1. O critério correspondente da E6 (13.7) fica **não cumprido**, não
dispensado.

- **O que cobre a acessibilidade nesta versão:** os testes de semântica do Compose (estado do
  item no nó da linha, cabeçalho "Marcados" num nó só, ações de deslize com ação equivalente,
  seletor de dias com papel, nome e estado) e a verificação da tester no emulador.
- **O que ninguém verificou:** o que o TalkBack de fato **fala**, e a ordem do foco, nas telas
  migradas na v1.7.0 e nos pontos mexidos na v1.7.1. O emulador não exporta a descrição de
  estado, o papel de cabeçalho nem as ações personalizadas, e o TalkBack não aceita gesto
  injetado; por isso essa parte só se prova com uma pessoa ouvindo.
- **Limite conhecido da v1.7.1:** a acessibilidade por leitor de tela está garantida pela
  estrutura (semântica), não pelo uso real. Pode haver rótulo lido duas vezes, ordem de foco
  ruim ou frase estranha sem que nenhum teste acuse.
- **Backlog:** a passada com fala real continua pendente, sem versão marcada. Deve ser feita
  antes ou junto da v1.8.0, que reescreve o miolo das telas principais: verificar depois da
  reescrita evita conferir duas vezes as mesmas telas. O roteiro da tester fica guardado.

#### Aparelho do usuário

Depois da medição, o aparelho físico voltou para o usuário. Não será usado pelo time sem um
novo pedido dele; a regra de segurança dos dados da 13.6 continua valendo para qualquer uso
futuro.

#### Busca da aba Tarefas (E5): causa registrada, correção na v1.8.0

O defeito registrado na v1.7.0 ("busca meio sob a barra de topo com o teclado") **não é o que
acontece**. Causa achada pelo dev sênior, com capturas no emulador (2026-11-16); o arquiteto não
reproduziu.

- Com o teclado aberto, a barra de topo e a janela **não se deslocam**: a barra fica no mesmo
  lugar. A busca nunca fica sob a barra. A regra do teclado da 12.4 está funcionando.
- Retrato com fonte 1.0 e 2.0, e paisagem com fonte 1.0: busca inteira.
- **Paisagem com fonte 2.0:** a busca é cortada **embaixo, pelo teclado**. Entre a barra e o
  topo do teclado sobram cerca de 72dp, menos que o cabeçalho fixo da tela (busca e filtros).
- Não é defeito da casca nem do preenchimento: só se resolve com cabeçalho que rola ou recolhe,
  que é mudança do miolo da tela. Fica para a **v1.8.0**, junto do limite já aceito na 12.4 (aba
  Tarefas em paisagem com fonte 2.0), que tem a mesma origem.
- Critério da E5 (13.7): causa registrada, **cumprido**. Correção: v1.8.0.

#### Teste intermitente

O `CalendarViewModelTest` falhava de vez em quando. **Causa:** o tempo limite da espera era
contado no relógio virtual do teste, que avança sem esperar o trabalho real terminar.
**Correção, só no teste:** o limite passou a ser contado em tempo real. Nenhuma mudança no
código do app.

#### Situação dos critérios da E6

Abertura a frio: **cumprido**. Leitor de tela com fala real: **não cumprido**, e é o único.

---

## 14. Emenda de 2026-11-17 — escopo e desenho da v1.8.0

Base: `main` em 0381efe (v1.7.1 publicada). As decisões P1 a P7 são do usuário.

### 14.1 Divisão e renumeração (P1)

O escopo "Hoje e editores" da seção 7 são duas reescritas independentes mais uma mudança de
dados. Fica dividido:

| Versão | Nome | Conteúdo |
|--------|------|----------|
| **v1.8.0** | Hoje e dados | Tela Hoje (B1), timeline (D2), "Todas as rotinas", lista de tarefas com cabeçalho rolando (parte de D1), painel de nova tarefa num lugar só, Desfazer definitivo com migração, agendador alinhado à entrega |
| **v1.9.0** | Editores | Salvar na hora (C4, seção 5), editor de rotina reorganizado (D3), detalhe da tarefa (resto de D1), remoção do `TaskDraft.rebase` |
| **v1.10.0** | Adaptativo, calendário e busca | O que a seção 7 chamava de v1.9.0 |

Motivo: a Hoje não depende dos editores, e o salvar na hora reescreve exatamente as duas telas
de edição. Juntar obrigaria a matriz manual a cobrir a troca da tela inicial e a troca do modelo
de salvar de uma vez. Salvar na hora é mudança de comportamento e não cabe numa PATCH.

Onde as seções 2, 5, 6, 8 e 10 dizem "v1.8.0" para o salvar na hora (U5, emenda E-2) e para os
editores, leia-se **v1.9.0**. A tela Hoje (U2, seção 6, emenda E-3) continua na v1.8.0.

### 14.2 Dados: banco 10 para 11

- `completion_log` ganha três colunas **anuláveis**: `previousDueDate`, `previousReminderAt` e
  `rewrittenDueDate`. Guardam o que a conclusão adiantada de uma tarefa única sobrescreveu (a
  data e o lembrete anteriores) e a data que ela gravou. É o que a v1.6.0 mantinha num mapa em
  memória do `TaskRepository`.
- Como são só colunas anuláveis, a migração é **automática** (10 para 11), sem SQL à mão. O
  esquema 11 é exportado. Linhas antigas ficam com vazio, que significa "esta conclusão não
  reescreveu nada".
- O mapa em memória e o `Mutex` dele **saem**. Desfazer passa a restaurar data e lembrete a
  qualquer momento: depois de o processo morrer, e ao desmarcar a tarefa horas depois.
- A guarda continua: só restaura se a tarefa ainda está como a conclusão a deixou (mesma data,
  sem lembrete). Se o usuário editou no meio, a edição dele vence.
- **Toque duplo em Concluir** numa tarefa única: concluir uma tarefa única já encerrada devolve
  o registro que existe, não cria outro.
- **Backup: `SCHEMA_VERSION` continua 6.** Os três campos entram como opcionais no registro de
  conclusão; backup antigo restaura com vazio. É a regra da 4.1: campo opcional com padrão não
  sobe a versão. A restauração só valida a versão do backup e lê os campos pelo nome.
- O `TaskDraft.rebase` **não** sai agora: ele existe por causa do rascunho do detalhe e sai na
  v1.9.0, com o salvar na hora.

**Excluir pelo detalhe com Desfazer.** A tela do detalhe fecha e a mensagem aparece na tela de
onde o usuário veio. O detalhe abre a partir de três telas, e a tela de baixo não está composta
enquanto ele está por cima; por isso não se usa resultado de navegação nem ViewModel
compartilhado. Um objeto pequeno criado no `KalendrysApp`, no molde do `NewTaskDraftStore`,
guarda a oferta de Desfazer e sabe restaurar pelo `TaskRepository`; o ViewModel do detalhe
exclui e publica a oferta; o `KalendrysNavHost`, que está sempre composto, é o **único**
coletor e mostra a mensagem com a função única da 13.4. O deslize da lista de tarefas continua
como está.

**Onde a mensagem é desenhada** (decisão na E0): no espaço de mensagens da tela que está à
frente, não na moldura de fora da navegação. Desenhada por fora, ela cobria o FAB da aba por 10 s
e caía no mesmo lugar da mensagem da própria tela. O estado da mensagem continua sendo do topo
da navegação; ele é entregue ao `AppScaffold` por um valor de composição com padrão nulo, e o
`AppScaffold` o desenha junto da mensagem da tela, uma acima da outra, e as duas acima do FAB, sem
cobri-lo. Só a tela que está à frente desenha, para a mensagem não aparecer em dobro durante a
transição. Consequência: toda tela de destino precisa usar o `AppScaffold`.

**A oferta é um estado, não um evento** (decisão depois da revisão do tester): o objeto do app
guarda a oferta pendente até a mensagem terminar (Desfazer, tempo esgotado ou dispensada) ou
até outra oferta substituí-la. Se a tela for recriada com a mensagem visível (girar o aparelho),
a mensagem volta com o tempo reiniciado, em vez de o Desfazer sumir com a tarefa excluída.
A oferta tem **validade curta** a partir da criação: vencida, é descartada sem ser mostrada,
para a mensagem não reaparecer do nada quando o app é reaberto muito depois. A espera da mensagem
também é limitada pelo que resta do prazo, porque o tempo da mensagem só corre com a tela à
frente. Um toque em Desfazer numa mensagem visível sempre restaura: o prazo decide se a
mensagem aparece, não se o botão funciona. O coletor só fica ativo com a tela do app à frente:
sair da frente cancela a espera sem limpar a oferta, e voltar confere o prazo pelo relógio de
parede antes de mostrar. Isso cobre o botão de início, a tela apagada e o processo congelado.

**A oferta serve a duas exclusões** (decisão na P8): tarefa excluída pelo detalhe e rotina
excluída pelo editor. O objeto do app guarda uma oferta por vez, de um dos dois tipos, e é ele
que sabe restaurar cada um; o coletor único só escolhe o texto. As demais regras (prazo, tela à
frente, substituição) são as mesmas para os dois.

**Excluir e oferecer é uma operação só, no escopo do app.** O próprio objeto exclui e publica a
oferta; a tela só pede e fecha. Com a exclusão no escopo da tela, sair no instante entre excluir
e oferecer deixava a exclusão sem Desfazer. A exclusão de rotina feita em "Todas as rotinas"
passa pelo mesmo caminho, e a tela deixa de ter um Desfazer próprio.

**Terceiro tipo de oferta (E5): tarefa concluída na lista de tarefas.** Concluir e oferecer é
operação do mesmo objeto; desfazer é pelo registro da conclusão. A regra de uma oferta por vez
vale entre tipos: uma ação nova substitui a mensagem da anterior, que fica sem Desfazer.
**Pendência para a v1.9.0:** concluir e pular na Hoje ainda usam mensagem própria da tela e
não sobrevivem a girar; levar para o mesmo caminho.

**Qual conclusão desfazer:** quando uma tarefa tem mais de uma conclusão no mesmo dia, desmarcar
desfaz a **mais recente**. Desfazer a mais antiga devolvia a data errada e deixava um registro
solto.

### 14.3 Agendador alinhado à entrega

Fecha o item deixado na 13.3 (D-71-1).

- **Tarefas:** o `TaskRepository` passa a ser o dono do instante do próximo lembrete (a função
  que na v1.7.1 servia só ao diagnóstico), e o `ReminderScheduler` apenas marca o alarme no
  instante que recebe. Concluir uma tarefa repetida reagenda para a próxima ocorrência que será
  entregue; desfazer devolve a de hoje. O diagnóstico e o agendador voltam a ser a mesma conta,
  e o teste dos dois caminhos volta a exigir igualdade.
- **Rotinas:** o início concluído **não** é pulado, porque o alarme de início também redesenha
  os widgets. Só muda o tipo: exato se alguma rotina que avisa ainda não foi concluída naquele
  dia, silencioso se todas foram. **Limite aceito:** o alarme silencioso é aproximado e não
  acorda o aparelho, então o widget de um bloco já concluído pode avançar alguns minutos depois
  do horário; é o mesmo comportamento que as rotinas sem aviso sempre tiveram.
- **O alarme no sistema é sempre o resultado da conta.** Se a conta não devolve ocorrência, o
  alarme da tarefa é cancelado, não deixado como estava.
- **Ler e marcar sob a mesma trava.** Todo trecho do `TaskRepository` que lê o estado (tarefa e
  conclusões) e marca alarme roda sob a mesma trava que serializa concluir e desfazer. Sem isso,
  um catch-up que leu antes de um desfazer marca por cima o alarme antigo e o lembrete do dia
  sai atrasado. Ordem fixa das travas: a do catch-up por fora, a do repositório por dentro,
  nunca o contrário.
- Motivo: uma tarefa concluída de manhã deixava um despertador marcado para a tarde, visível no
  sistema, que não avisava nada.
- As regras do ADR-0003 (catch-up) não mudam. Os testes dele seguem sem asserção alterada.

### 14.4 Navegação e tela Hoje

Detalha a seção 6.

- `AppTab.Today` substitui `AppTab.Routines` e vira o destino inicial. Abas: Hoje, Tarefas,
  Calendário, Listas. O endereço `kalendrys://routines/{data}` continua aceito e abre a Hoje na
  data.
- Pacote novo `ui/today`. O `RoutinesScreen` atual é **dividido**: a timeline vai para arquivos
  próprios e o ViewModel vira `TodayViewModel`. Nenhuma regra nova na tela.
- **Uma fonte só para as tarefas:** a Hoje usa o `TodayOverview`, o mesmo retrato do widget e do
  resumo diário. Os números da tela e do widget têm de bater.
- **Seções (P2):** progresso do dia, próximo item, timeline, "Atrasadas", "Hoje" e "Sem data"
  (recolhida). Em outro dia da semana, a tela mostra a timeline e as tarefas daquele dia.
- **Concluídas (n)** (decisão na E3): seção recolhível no fim, no padrão do "Marcados (n)" das
  listas. Entra nela toda tarefa com conclusão **no dia mostrado**, pela data do registro e não
  pela data da tarefa, que não esteja já riscada na timeline; cada uma pode ser desmarcada.
  Sem ela, uma tarefa atrasada ou sem data concluída hoje sumia da tela, entrava na contagem do
  widget e não podia ser desmarcada. A seção "Hoje" fica só com as pendentes. Os pendentes vêm
  do `TodayOverview`; as concluídas, dos registros do dia. Dois testes de igualdade com o
  widget: pendentes e concluídas.
- **"Feitas hoje" tem uma definição só:** tarefas que existem e têm registro de conclusão hoje.
  O `TodayOverview` passa a contar assim, em vez de contar pelo estado da tarefa, que deixava de
  fora a repetida concluída antes do dia dela e a tarefa reaberta para outra data. Vale para a
  tela, o widget e o resumo diário.
- **A tela inteira é uma lista:** a faixa da semana rola junto com o conteúdo. Resolve a
  paisagem pela raiz, sem depender da folga do FAB (12.4, S-1).
- **Filtro por categoria (P3):** sai da Hoje e vai para "Todas as rotinas".
- **Botão + (P4):** cria tarefa. "Nova rotina" fica em "Todas as rotinas" e no menu da Hoje.
- **Todas as rotinas** (rota nova): ativas e pausadas; editar, pausar, retomar e arquivar com
  Desfazer. Resolve a rotina pausada que sumia da aba.
- **Pular (P5):** ganha Desfazer, e o bloco pulado continua visível no dia, esmaecido, com a
  ação de desfazer. Pular fica num menu curto ao segurar o bloco ("Pular este dia", "Remarcar
  este dia"); o bloco pulado tem um controle visível de desfazer. A mensagem com Desfazer, logo
  depois de pular, devolve o dia ao que era antes, inclusive uma remarcação; o desfazer do
  bloco, mais tarde, só tira o pular.
- **As ações do bloco valem para o dia da ocorrência, não para o dia mostrado.** Um bloco que
  chegou remarcado de outro dia pertence à ocorrência do dia de origem; pular, remarcar e
  desfazer agem nela. O bloco da timeline carrega esse dia, calculado no domínio.
- **Concluir em dia passado grava no próprio dia** (decisão depois da E3). Antes, concluir uma
  tarefa pela linha de um dia passado gravava a conclusão com o horário de agora: numa tarefa
  repetida, isso concluía a ocorrência de hoje e deixava a do dia passado pendente. A conclusão
  retroativa não tem efeito de hoje: não zera as marcações da lista anexada, não tira a
  notificação que está na tela e não mexe no alarme da próxima ocorrência. É a regra que as
  rotinas já seguiam. Exceção: tarefa única que a conclusão encerra tem a notificação retirada
  mesmo assim, porque ela não tem ocorrência de hoje para preservar. Na repetida, a notificação
  na tela é sempre a da **última ocorrência que já tocou** (o identificador é um por tarefa): a
  conclusão retroativa a tira quando conclui o dia dessa ocorrência, e a deixa quando a que
  está na tela é de outro dia. Sem isso, a notificação de ontem ficava depois de ontem ser
  concluído, e tocar em "Concluir" nela concluía a ocorrência de hoje antes da hora.
- **A notificação de lembrete leva o dia da ocorrência**, como a de rotina já leva. "Concluir"
  numa notificação de tarefa repetida grava a conclusão no dia daquela ocorrência, não em
  hoje; para tarefa única continua sendo agora. A regra anterior dependia de adivinhar qual
  ocorrência está na tela, e errava quando a de hoje foi perdida ou entregue com atraso. Com
  o dia na própria notificação, tocar "Concluir" numa notificação antiga nunca conclui a
  ocorrência de hoje nem tira o lembrete dela. Notificação emitida por versão anterior, sem o
  dia, continua valendo como agora.
- Sem deslize de linha na Hoje (regra da 4.2); o círculo de concluir continua visível, e para o
  leitor de tela concluir é uma ação da linha, com o estado na própria linha (mesmo padrão do
  item de lista, 12.4).
- **A rota interna da Hoje continua `routines`**, e não `today` como dizia a seção 6: o estado
  salvo pela v1.7.1 com a aba Rotinas aberta é restaurado direto na Hoje. Com outro nome de rota
  a restauração não acharia o destino.
- **Título da barra:** na data de hoje, "Hoje"; em outro dia, o dia mostrado, com o botão "Hoje"
  ao lado para voltar.
- **A Hoje acompanha a virada do dia.** A seleção é "acompanhando hoje" ou um dia escolhido. Em
  "acompanhando hoje", passada a meia-noite a tela vai para o dia novo sozinha; um dia escolhido
  que passa a ser o dia de hoje volta a acompanhar. O dia de hoje vem da mesma fonte de datas do
  `TodayRepository`, não do relógio lido direto no ViewModel.
- **Estado vazio:** a Hoje é a primeira tela de quem instala; o vazio fala do dia e oferece
  "Nova tarefa" e "Nova rotina". Para isso o `EmptyState` ganhou uma ação secundária opcional e
  passou a rolar quando o conteúdo não cabe, em vez de cortar; vale para todos os vazios.
- **Nome da ação de tirar uma rotina (P8, decisão do usuário):** "Excluir rotina" nos dois
  lugares, com a mensagem "Rotina excluída" e Desfazer. Por baixo continua o arquivamento: a
  rotina some de hoje em diante e os dias passados ficam no histórico. No editor a confirmação
  sai, pela regra da 5.4 (ação com Desfazer não pede confirmação), e a mensagem aparece na tela
  de origem.

### 14.5 Timeline (D2)

Faixa de 30 min e títulos longos com fonte 2.0: a altura do bloco **nunca é menor que o
conteúdo**. A duração da rotina dá a altura **mínima** da pílula (bloco mais longo continua mais
alto, como sempre foi); o título inteiro e os horários podem fazer o bloco passar disso. O que
sai é a altura fixa pela duração, que cortava o texto. Título sem limite de linhas e horário que
quebra em vez de virar reticências. Teste de medida, como o do seletor de dias (13.2): sem
reticências, sem corte na altura e cada linha dentro da caixa do texto.

### 14.6 Lista de tarefas

Busca e filtros viram itens da lista e rolam junto. Fecha os dois limites de paisagem com fonte
2.0 registrados na 12.4 e na 13.9. Consequências aceitas: os filtros deixam de ficar sempre à
vista, e o campo de busca perde o foco se for rolado para fora da tela (o texto digitado fica).
Os auxiliares de cabeçalho fixo da casca (`splitTop`, `ScreenPadding`) saem, por não terem mais
uso.

O Desfazer de concluir e de excluir na lista de tarefas passa para a oferta do app (14.2), e a
tela deixa de ter mensagem própria; com isso ele sobrevive a girar a tela e a trocar de aba.

### 14.7 Painel de nova tarefa num lugar só

- O painel passa a ser hospedado no topo da navegação, aberto por um pedido com a data
  sugerida, e as operações do rascunho têm um dono único. Some o bloco repetido nos dois
  ViewModels (13.1).
- O + do widget e o atalho abrem o painel por cima do que estiver na tela, inclusive com o
  detalhe de uma tarefa aberto.
- Criar lista e tarefa passa a ser uma transação só.
- Altura compacta: título e botões "Cancelar" e "Salvar" numa linha fixa no topo, campos
  rolando. Substitui a regra do título da 13.1.
- **Aberto ou fechado é estado da tela; rascunho e operações são do app** (decisão no desenho
  da E6). O pedido de abertura, com a data sugerida, é estado salvo da navegação: sobrevive a
  girar, e some quando a tela termina de verdade. Guardado no objeto do app, ele faria o painel
  reabrir sozinho ao voltar ao app muito depois, o mesmo defeito que a oferta de Desfazer teve
  (14.2). O objeto do app fica com o rascunho, as operações (abrir, alterar, cancelar, salvar no
  escopo do app), as listas do seletor e a limpeza da lista excluída no meio.
- Widget e atalho deixam de trocar de aba; com o app fechado, o painel abre sobre a Hoje.
- A lista criada junto com a tarefa nasce pela mesma função que cria lista avulsa.

### 14.8 Etapas e critérios de aceite

Um PR de integração. E0 e E1 primeiro e sozinhas, porque mexem em dados e alarmes. Revisão do
arquiteto na E0, na E1 e na E2 antes de seguir; na E3 e na E6 ao fim.

| Etapa | Conteúdo | Aceite |
|-------|----------|--------|
| E0 | Dados e Desfazer (14.2) | Migração 10 para 11 e 1 para 11 testadas; concluir tarefa única adiantada, encerrar o processo, desfazer: data e lembrete voltam; toque duplo gera um registro; backup antigo e novo, ida e volta; excluir pelo detalhe com Desfazer a partir das três telas de origem |
| E1 | Agendador (14.3) | Tarefa diária concluída antes do horário não tem alarme hoje (conferido no sistema) e desfazer devolve; rotina concluída vira alarme silencioso e o widget avança; teste dos dois caminhos com igualdade; testes do ADR-0003 sem asserção mudada |
| E2 | Navegação, casca da Hoje e "Todas as rotinas" (14.4) | App abre na Hoje; endereço antigo abre a Hoje na data; notificações e widgets já emitidos funcionam; atualizar por cima da v1.7.1 com a aba Rotinas aberta não quebra |
| E3 | Conteúdo da Hoje (14.4) | Números iguais aos do widget Resumo no mesmo instante; paisagem com fonte 1.0 e 2.0 mostra conteúdo e rola até o fim; Pular e Desfazer; desmarcar uma tarefa concluída no dia dela, inclusive em dia passado e depois de reabrir o app |
| E4 | Timeline (14.5) | Fonte 2.0 sem corte nem sobreposição em blocos de 15, 30 e 60 min e títulos de 60 caracteres |
| E5 | Lista de tarefas (14.6) | Paisagem com fonte 2.0 mostra tarefas, e a busca aparece inteira com o teclado |
| E6 | Painel único (14.7) | Os critérios da E1 da 13.7 repetidos; widget e atalho por cima de qualquer tela; "Salvar" alcançável em paisagem com teclado |
| E7 | Fechamento | Jornadas do perfil refeitas (a tela inicial muda); perfil regerado; abertura a frio (14.9); tamanho do APK; matriz manual; testes de semântica das telas novas |

Se a E6 atrasar, é a candidata a ir para a v1.9.0 sem quebrar o resto.

### 14.9 Abertura a frio e leitor de tela

- **Abertura a frio (P6):** medida no fechamento, no aparelho do usuário, como na v1.7.1
  (13.6 e 13.9), com uma rodada de aquecimento descartada antes. Importa mais que nas versões
  anteriores, porque a tela inicial muda. **O aparelho só é usado com liberação explícita do
  usuário e backup confirmado**; sem a liberação, o critério fica registrado como não medido.
- **Leitor de tela com fala real (P7): não haverá.** Decisão do usuário: a passada com uma
  pessoa ouvindo saiu do plano, nesta versão e como pendência; substitui o item de backlog
  registrado na 13.9. O item F3 do roteiro fica atendido **só** pelos testes de semântica do
  Compose e pela verificação da tester no emulador. Continua valendo o limite escrito na 13.9: a
  acessibilidade por leitor de tela está garantida pela estrutura, não pelo uso real.
- Por isso os testes de semântica passam a ser critério de aceite de toda tela nova: um nó por
  linha, estado e papel expostos, toda ação de gesto com ação equivalente, cabeçalhos marcados.

### 14.10 Fora da v1.8.0

- **v1.9.0:** salvar na hora; editor de rotina; detalhe da tarefa; `TaskDraft.rebase`; atalho
  com editor aberto perdendo o rascunho (some com o salvar na hora); repetição personalizada;
  entrega do catch-up com título antigo ou aviso que pisca; editor de rotina em paisagem com
  teclado.
- **v1.9.0, com o salvar na hora:** dentro do repositório, criar, editar, concluir e desfazer já
  passam pela mesma trava (14.3), então uma gravação não atropela a outra. O que sobra é a tela
  que grava a partir de uma cópia antiga da tarefa e sobrescreve o que mudou por fora. O salvar
  na hora resolve isso aplicando a edição sobre a linha mais recente (5.2); nesse desenho, a
  conclusão adiantada e o desfazer passam a alterar **só** data e lembrete.
- **Limite aceito:** o reinício das marcações da lista fica fora da transação de concluir e de
  desfazer. Se o processo morrer entre os dois, as marcações de uma tarefa repetida podem não
  voltar; o histórico e a tarefa ficam corretos.
- **v1.9.0, no detalhe da tarefa:** ação de desfazer a conclusão, para quem chega pelo
  Calendário. Na v1.8.0 o caminho é a Hoje, no dia da conclusão.
- **Comportamento desejado, só registro:** mudar a data de uma tarefa única já concluída a
  reabre, e a conclusão continua no histórico do dia em que aconteceu. A tarefa pode aparecer
  como concluída hoje e pendente na data nova ao mesmo tempo; as duas informações estão certas.
- **Limitação conhecida:** conclusões adiantadas gravadas antes do banco 11 não têm a data e o
  lembrete anteriores; desfazê-las reabre a tarefa na data da conclusão e sem lembrete.
- **Só registro:** quando a rotina também ocorre normalmente no dia para onde outra ocorrência
  foi remarcada, as duas aparecem como um bloco só, que pertence à remarcada; pulada a
  remarcada, a ocorrência própria do dia reaparece no horário normal.
- **Só registro:** pular um bloco remarcado troca a remarcação por pular no dia de origem. O
  bloco some do dia mostrado e aparece esmaecido só no dia de origem; no dia mostrado fica a
  mensagem com Desfazer. É consequência de haver uma exceção por dia, e é o desejado.
- **v1.9.0, com a lista de tarefas (D1):** a aba Tarefas mostra cada tarefa uma vez, pelo estado
  atual, então "Concluídas hoje" ali não é a mesma lista de "Concluídas" da Hoje (14.4), que é
  pelo registro do dia. Uma repetida concluída fora do dia aparece como concluída na Hoje e
  como próxima na aba Tarefas. Decidir na reforma da lista se as duas se igualam.
- **Adiamento no diagnóstico:** continua fora. Exige gravar o adiamento, que é dado novo com
  regras de reinício e de limpeza, para uma janela de 10 minutos. Candidato para a v1.9.0,
  junto com o adiamento que se perde ao reiniciar o aparelho, que é o ganho real.
- **Depois da v1.10.0:** alarme próprio do widget Resumo.
- **Só registro, sem ação:** voltar preditivo fechando menu; arrastar no cartão de lista
  abrindo a lista; canto de 4dp em menus e campos de texto.

### 14.11 Riscos

| Risco | Mitigação |
|-------|-----------|
| Migração em dado de histórico | Só colunas anuláveis, migração automática com teste; backup sem subir a versão |
| Mudança no caminho dos alarmes (E1) | Etapa isolada e revisada antes de seguir; testes do ADR-0003 intactos; alarme conferido no sistema |
| Troca da tela inicial pesar na abertura a frio | Medição da 14.9; jornadas do perfil refeitas |
| Estado salvo da v1.7.1 com a rota antiga ao atualizar | **Não verificado** se o Android descarta o estado salvo ao atualizar o app; teste de atualização por cima na E2 |
| Hoje e widget mostrarem números diferentes | Uma fonte só (`TodayOverview`); teste comparando os dois |
| Leitor de tela sem verificação com fala real | Testes de semântica como critério de aceite (14.9); limite conhecido |
| Tamanho da versão (8 etapas) | E0 e E1 isoladas; E6 pode ir para a v1.9.0 |

### 14.12 Plano de teste

- **Dados:** teste de migração 10 para 11 e da cadeia completa; `TaskRepository` com desfazer
  depois de recriar o repositório (processo novo); toque duplo; backup da v1.7.1 restaurado;
  ida e volta com os campos novos.
- **Alarmes:** os testes do ADR-0003 sem mudança; agendador e diagnóstico iguais para tarefa,
  rotina e resumo; concluir e desfazer no mesmo dia; rotina concluída com alarme silencioso.
- **Navegação:** `AppLinks` com o endereço antigo de rotinas; atalhos; destino inicial.
- **Telas novas:** ViewModels com tempo virtual; semântica (14.9); medida de texto com fonte 2.0
  onde houver risco de corte.
- **Manual, no APK de release:** matriz de tema, fonte 1.0, 1.3 e 2.0, retrato e paisagem;
  atualização por cima da v1.7.1 com dados; widgets e notificações já emitidos; varredura do
  log.

Dependências: nenhuma nova. `targetSdk` e `minSdk` não mudam. Manifesto: sem mudança prevista.

### 14.13 Achados da matriz no emulador (E2 a E6)

A matriz manual no APK de release passou sem bloqueante e trouxe cinco observações. Decisões:

- **Painel de nova tarefa e teclado (O-6): corrigir antes do fechamento.** O critério da E6 (Salvar
  ao alcance com o teclado aberto) não foi cumprido em retrato: o painel já termina acima do teclado
  (o `ModalBottomSheet` desconta o teclado sozinho, uma vez), mas Cancelar e Salvar estavam no fim
  da rolagem e ficavam fora da área visível. Regra: o conteúdo do painel termina **acima do
  teclado**; só os campos rolam; Cancelar e Salvar ficam **fora** da área que rola nas duas alturas
  (no fim do painel em altura normal, na linha do topo em altura compacta); o campo em foco é
  trazido para a área visível. Em altura compacta o painel não desenha a alça, para sobrar altura
  para o campo. Limite aceito: em paisagem com fonte 2.0 e teclado, o que se garante é o Salvar e a
  linha de texto do campo em foco; o rótulo e a borda do campo podem ficar cortados.
- **Horário do bloco com fonte grande (O-7): corrigir.** O horário do bloco nunca quebra no meio de
  um número. A linha é escolhida por medida, como a do tempo livre: a faixa inteira com a duração
  se couber em uma linha; se não couber, só a duração, porque o início e o fim já estão na coluna
  de horas ao lado. O leitor de tela continua recebendo a frase inteira. O teste de medida da 14.5
  ganha a condição de o horário ocupar uma linha.
- **Troca de relógio ou de fuso com a Hoje aberta (O-4): corrigir.** A data de hoje do
  `TodayRepository` passa a ser relida a cada `refresh()`, e não só depois da espera até a
  meia-noite, que conta tempo corrido e não vê o relógio mudar. O aviso de troca de hora e de fuso
  já chega ao `refresh()` pelo caminho dos alarmes e dos widgets. A Hoje também pede um `refresh()`
  ao voltar para a frente. Os outros usos de `currentDate()` e de `LocalDate.now()` (aba Tarefas,
  Calendário, detalhe) ficam como estão; fonte única de data para o app inteiro fica para a v1.9.0.
- **Círculo de concluir sem ação na lista de Tarefas (O-5): corrigir.** A tarefa que se repete e
  não acontece hoje não mostra controle de concluir: no lugar fica o ícone de repetição, sem toque
  próprio, no mesmo espaço, e o toque ali abre a tarefa como o resto da linha. Controle que não age
  não pode ter a aparência de um que age.
- **FAB sobre o círculo da última linha visível da Hoje (O-8): aceito, sem mudança.** É o
  comportamento do botão flutuante sobre uma lista; a folga no fim da lista garante que toda linha
  sai de baixo dele rolando. A Hoje tem o concluir à direita, do lado do botão, e por isso o caso é
  mais fácil de acontecer nela do que na aba Tarefas; rever a posição do concluir na Hoje fica
  registrado para a v1.9.0, como decisão de desenho.

O reteste das quatro correções no APK de release passou. Ele trouxe achados menores, todos de fonte
2.0 em tela estreita, que **não entram na v1.8.0** e ficam como dívida da v1.9.0, com o desenho já
decidido:

- **Faixa da semana quebrando o nome do dia** ("Dom" em duas linhas, 360dp): o nome do dia fica
  sempre em uma linha; se a forma de três letras não couber, a faixa inteira usa a forma de uma
  letra, a mesma para os sete dias, escolhida por medida.
- **Horário do bloco virando reticências** (bloco com sino e lista, 360dp e 320dp): quando nem a
  duração cabe, o horário não é desenhado; reticências sozinhas não informam nada, e o início e o
  fim continuam na coluna de horas. O leitor de tela continua com a frase inteira.
- **Título do bloco quebrando no meio da palavra** (320dp): limite aceito; o critério da 14.5 é não
  cortar, e ele vale. Avaliar hifenização automática na v1.9.0.
- **Ícone de repetição duas vezes na linha da tarefa repetida**: fica como está.
- **Painel de nova tarefa:** depois de girar a tela ou trocar a fonte o foco volta ao Título (o
  pedido de foco deve valer só na primeira abertura); em paisagem com teclado, arrastar o conteúdo
  para baixo pode fechar o painel, com o rascunho guardado. Os dois ficam para a v1.9.0, junto com
  os editores.

O fechamento dos casos que faltavam (Desfazer, conclusão retroativa, notificações, estados vazios)
passou sem bloqueante e trouxe mais quatro achados, nenhum para a v1.8.0:

- **Estado vazio da Hoje e da aba Tarefas em paisagem mostrando só o ícone e o título:** v1.9.0.
  A folga do botão flutuante é aplicada por fora do estado vazio e encolhe a área que rola. O
  `EmptyState` passa a receber o preenchimento da tela como preenchimento **de dentro** da rolagem,
  como uma lista, e a área que rola volta a ser a tela inteira. Nova tarefa continua no botão
  flutuante e Nova rotina no menu da barra.
- **Dia sem nada, com dados em outros dias:** intencional. O estado vazio com as duas ações é o do
  app sem conteúdo; num dia vazio a tela mantém a faixa da semana, e criar continua no botão
  flutuante e no menu. Esconder o "0 de 0" do progresso num dia sem itens fica como ajuste de texto
  para a v1.9.0.
- **Ocorrência não feita de uma tarefa repetida não aparece como atrasada:** é a regra desde que a
  repetição existe (a tarefa passa para a ocorrência seguinte). Mudar é decisão de produto e mexe
  no modelo, porque o estado passaria a ser por ocorrência; fica como pergunta ao usuário na
  v1.9.0.
- **Dias passados mostram as rotinas excluídas:** intencional, é a regra da 14.4 (excluir é
  arquivar; a rotina some de hoje em diante e os dias passados ficam no histórico).

### 14.14 Fechamento da v1.8.0

#### Baseline Profile

Regerado no fechamento, sobre o código final (as jornadas passam pela Hoje como tela inicial e
pelo painel de nova tarefa). A jornada que digita e salva no painel passou a buscar o campo de
novo a cada passo, porque o painel recompõe ao trazer o campo em foco para a área visível (14.13).

#### Abertura a frio (2026-11-21): NÃO MEDIDA

O aparelho do usuário não foi usado (14.9). A medição foi tentada no emulador, pelo protocolo da
11.8.3 (`StartupBenchmark`, 10 aberturas a frio por rodada, tempo até o primeiro quadro, com
perfil, uma rodada de aquecimento descartada), e a **sessão é inválida**. Números do time; o
arquiteto conferiu as contas, não refez a medição.

| Rodada | Versão | Mediana (ms) | p90 (ms) | p90 sobre a mediana |
|--------|--------|--------------|----------|---------------------|
| Aquecimento (descartada) | v1.7.1 | 884,4 | 957,1 | +8,2% |
| A1 | v1.7.1 | 1031,1 | 1354,2 | +31,3% |
| D1 | v1.8.0 | 987,8 | 1208,0 | +22,3% |
| D2 | v1.8.0 | 1016,0 | 1066,6 | +5,0% |
| A2 | v1.7.1 | não rodou | | |

- **Por que é inválida:** a A1 e a D1 passam do limite de 20% de p90 sobre a mediana, e sem a A2
  não há os dois "antes" para comparar. O mesmo build da v1.7.1 deu 884 e 1031 ms na mesma
  sessão, uma diferença de 17%. A memória livre do host ficou acima de 4 GB o tempo todo
  (mínimo de 4,99 GB) e a temperatura constante: a dispersão é do emulador, não do host.
- **O que os números dizem:** nada. Não mostram a v1.8.0 mais lenta nem mais rápida que a
  v1.7.1, e não podem ser usados como "antes" de outra sessão.
- **Por que a A2 não rodou:** um widget acordou a v1.8.0 entre a limpeza dos dados e a
  instalação da v1.7.1, o banco foi recriado na versão 11 e a v1.7.1 não abre um banco mais novo.
  Regra para as próximas sessões que trocam de versão: **instalar a versão antes de limpar os
  dados**, nunca o contrário.
- **Pendência:** a troca da tela inicial pela Hoje é a mudança desta versão com mais chance de
  pesar na abertura (14.9), e ela fica **sem medida**. Medir no aparelho físico, v1.7.1 contra
  v1.8.0, quando o usuário liberar; até lá a regra dos 10% não tem número para aplicar.

**Mudança no arnês de medição.** Com widgets do app na tela inicial do emulador, o agendador do
sistema reabria o processo logo depois de o benchmark matá-lo, para rodar o trabalho de
atualização dos widgets, e toda rodada falhava na checagem de que o app não está rodando antes
da abertura a frio. O `StartupBenchmark` passou a ir para a tela inicial, esperar o trabalho
terminar e matar o processo de novo antes de cada abertura. Só o módulo de medição mudou; o app
não. O mesmo trecho foi aplicado ao "antes" numa cópia temporária, para os dois lados medirem
igual. Em aparelho com widgets do app na tela inicial, toda medição futura precisa desse passo.

#### Leitor de tela

Sem passada com fala real, por decisão do usuário (14.9). A verificação ficou nos testes de
semântica e na árvore de acessibilidade conferida no emulador, sem o TalkBack ligado.

## 15. Emenda de 2026-11-21 — escopo e desenho da v1.9.0

MINOR "Editores", sobre a v1.8.0 (229662d). Sem migração de banco, sem dependência nova e sem
mudança no manifesto. Cumpre a divisão da 14.1: salvar na hora (seção 5), editor de rotina
reorganizado (D3), detalhe da tarefa (resto de D1) e remoção do `TaskDraft.rebase`. As decisões
P1 a P7 são do usuário.

### 15.1 Decisões do usuário

| # | Decisão |
|---|---------|
| P1 | Tarefa repetida não feita passa a aparecer como atrasada, **na forma curta e fora desta versão**: v1.10.0, com parecer próprio (15.9) |
| P2 | "Reverter alterações" no menu dos dois editores, valendo enquanto a tela está aberta. É o primeiro item a cortar se o prazo apertar |
| P3 | Depois de "Criar rotina" a tela **fecha**, como hoje. Corrige a 5.1 |
| P4 | Ordem do editor de rotina (15.5) |
| P5 | "Concluídas hoje" da aba Tarefas passa a ser pelo registro do dia, igual à Hoje |
| P6 | No detalhe da tarefa, um botão desfaz a conclusão que vale agora; o histórico de dias anteriores é só leitura ali |
| P7 | Abertura a frio no fechamento, no aparelho do usuário, só com liberação explícita dele e backup confirmado |

### 15.2 O problema e a regra

Até a v1.8.0 as duas telas de edição guardam uma cópia da entidade e gravam a linha inteira
(`TaskRepository.updateTask`, `RoutineRepository.update`). Com o salvar na hora isso sobrescreve
o que mudou por fora enquanto a tela estava aberta: concluir pela notificação, pausar, excluir a
lista vinculada. O `TaskDraft.rebase` existia para remendar esse caso no detalhe da tarefa.

**Regra: a tela não guarda cópia da entidade. Ela mostra a linha do banco e envia mudanças, não
objetos.**

- **Domínio** (puro, testável sem Android): `TaskChange` e `RoutineChange`, tipos fechados com
  um caso por campo ou grupo de campos. Na tarefa: título, observações, local, vínculo de lista,
  e o conjunto data, hora e repetição como **um** campo, que nunca é aplicado pela metade. Na
  rotina: título, observações, horário, duração, dias, intervalo de semanas, vigência, lembrar,
  categoria, cor, ícone e vínculo de lista. Cada mudança sabe se aplicar sobre uma entidade e
  dizer se o resultado é válido (regras da 5.3).
- **Repositório:** `TaskRepository.edit(taskId, change)` e `RoutineRepository.edit(routineId,
  change)` leem a linha **mais recente** dentro da trava que já existe (14.3), aplicam a mudança
  e gravam **só as colunas daquela mudança**, nunca a linha inteira. Há gravações que não passam
  por essas travas (excluir uma lista desvincula tarefas e rotinas por fora), e só a gravação por
  coluna impede que uma delas seja desfeita. Pela mesma regra, pausar, retomar, arquivar e
  desarquivar gravam só a própria coluna. Mudança inválida não grava. Entidade que não existe
  mais: nada acontece.
- **Alarmes:** só são refeitos quando a mudança mexe em horário, repetição, dias, vigência ou
  lembrar. Mudança de texto, cor, ícone, categoria ou lista não toca em alarme. A ordem das travas
  da 14.3 não muda.
- **As telas deixam de chamar** `updateTask(task)` e `update(routine)`. O `TaskDraft` e o
  `rebase` são apagados. O `RoutineDraft` fica **só** para a criação de rotina.
- **Concluir e desfazer a conclusão** passam a alterar só data e lembrete, por atualização
  parcial no DAO; nunca regravam título, texto ou lista a partir de uma leitura antiga.
- **Criar lista e vincular é uma transação** no repositório, para tarefa e para rotina já
  criadas. Não sobra lista avulsa se a operação falhar no meio, e a lógica deixa de existir
  repetida nos dois ViewModels.

### 15.3 Texto, saída da tela e Reverter

- **Campo de texto tem buffer local.** Enquanto a pessoa digita, o campo mostra o que ela
  digitou, não o que o banco reemite; sem isso o cursor pula e teclas se perdem. Grava 500 ms
  depois da última tecla (5.2) e **sempre** ao sair da tela e ao ir para segundo plano, no
  escopo do app, o mesmo já usado pelas ofertas de Desfazer e pelo painel de nova tarefa.
- **O texto do campo é estado da tela, atualizado na hora da tecla.** O que o campo mostra não
  pode dar a volta por fluxos do ViewModel antes de voltar para ele: com digitação rápida, o
  campo recebe de volta um texto atrasado, e letras somem ou trocam de lugar, e o texto errado é
  gravado. O buffer guarda o texto como estado de composição, escrito de forma síncrona na
  própria tecla, e a tela lê esse estado direto. O ViewModel recebe a tecla para a espera e a
  gravação, não para devolver o texto ao campo.
- Texto que mudou por fora só entra no campo se não houver digitação pendente nele **e** se for
  diferente do que o próprio campo gravaria. A gravação apara espaços e quebras do começo e do
  fim; sem a segunda condição, o banco devolveria o texto aparado e apagaria o espaço ou a quebra
  de linha que a pessoa acabou de digitar no fim.
- **Título vazio** não grava; ao sair, o campo volta ao último título válido (5.3).
- **Uma fila de gravações por editor, na ordem do toque.** O escopo do app roda em várias
  threads, e duas gravações lançadas soltas não têm ordem garantida: a mais velha pode chegar
  por último. Todas as gravações de um editor (texto, campos discretos, exercícios, Reverter)
  passam por uma fila única, consumida no escopo do app, que termina ao esvaziar depois de a
  tela fechar. Quando a mudança depende do valor atual (marcar e desmarcar um dia), ela é
  calculada sobre a linha lida na hora de gravar, não sobre o que a tela mostrava. A fila é
  peça comum dos dois editores, como o buffer de texto.
- **Reverter alterações (P2):** retrato dos campos do editor tirado na abertura, guardado no
  ViewModel. Reverter envia de volta **só os campos que a pessoa mudou nesta sessão**, um a um, e
  não mexe no que não é do editor (conclusão, pausa, arquivamento). Mandar de volta todos os
  campos do retrato desfaria por tabela o que mudou por fora, como a data reescrita por uma
  conclusão adiantada. Na rotina inclui os exercícios, com o retrato que já servia ao descartar.
  Não sobrevive à morte do processo. Aceito.
  **Reverter pula o campo que mudou por fora depois da edição.** Um campo mexido na sessão só
  volta ao retrato se o valor que está no banco ainda é o que o editor gravou por último. Caso
  que motivou a regra: a pessoa mexe na data, conclui a tarefa única adiantada (a conclusão
  reescreve data e lembrete) e toca em Reverter; devolver a data da abertura reabriria a tarefa,
  e o Reverter estaria desfazendo o efeito de uma conclusão, que não é campo do editor.
- **Voltar preditivo** passa a funcionar nos editores de entidade existente, que não têm mais
  diálogo de descarte.

### 15.4 Detalhe da tarefa

- Campos gravando na hora, pela 15.2 e pela 15.3. Somem "Salvar", `hasChanges`, `canSave` e o
  "Descartar alterações?".
- **Concluir** pelo detalhe passa pela oferta do app (14.2), como excluir já passa: a mensagem
  aparece mesmo se a tela fechar.
- **Desfazer conclusão (P6):** quando a tarefa está concluída (hoje, numa repetida; encerrada,
  numa única), a ação principal passa a desfazer essa conclusão. Atende quem chega pelo
  Calendário (14.10). O histórico de outros dias continua só leitura; corrigir um dia passado é
  na Hoje, naquele dia (14.4).
- **Excluir** continua com "Desfazer" pela oferta do app, mas só exclui **depois das gravações
  pendentes** e com a linha lida do banco naquele momento. A oferta guarda a tarefa inteira para
  reinserir; partir de uma cópia atrasada faria o Desfazer devolver um texto antigo e perder uma
  edição já gravada.

### 15.5 Editor de rotina (D3)

- **Modo edição** (rotina já criada): grava na hora. **Modo criação:** rascunho e botão "Criar
  rotina"; nada é gravado nem agendado antes dele; voltar com campos preenchidos pede
  confirmação. **Depois de criar, a tela fecha (P3).** A lista escolhida na criação só é criada
  junto com a rotina, numa transação, como o painel de nova tarefa já faz.
- **Ordem das seções (P4):** Título; Quando (início, término ou duração, dias, a cada N
  semanas); Lembrar; Categoria; Lista; Exercícios (só na categoria de exercício); Aparência
  (ícone e cor); e, recolhidos em "Mais opções", Vigência (início e fim) e Observações. Pausar,
  Duplicar, Reverter alterações e Excluir ficam no menu.
  "Mais opções" abre sozinho quando já há vigência definida ou observações, decidido uma vez na
  abertura da tela; depois disso só o toque da pessoa abre ou fecha. Vigência escondida faria a
  rotina sumir de um dia sem ninguém ver por quê.
- **Excluir não desfaz os exercícios da sessão.** Com o salvar na hora não há sessão a descartar:
  o Desfazer devolve a rotina como está gravada, com os exercícios como estão. Substitui o que a
  14.4 dizia sobre desfazer as edições de exercício antes de excluir pelo editor.
- Estados inválidos inalcançáveis, como na 5.3: o último dia não desmarca; o fim da vigência só
  oferece datas a partir do início.
- O arquivo da tela é dividido por seção. Em paisagem com teclado, vale a regra do painel
  (14.13): o campo em foco fica visível e a ação de criar fica ao alcance.
  Em altura compacta o "Criar rotina" sai do rodapé e vai para a barra do topo, como ação em
  destaque: com o teclado aberto, barra do topo e rodapé juntos não deixavam altura para o campo.

### 15.6 Hoje e aba Tarefas

- **Desfazer da Hoje pela oferta do app:** pular um bloco e concluir pela Hoje ganham alvos
  próprios na oferta (14.2), e a Hoje perde as mensagens próprias. A mensagem passa a sobreviver
  a girar a tela, como as outras.
  Fazer e oferecer é uma operação só da oferta, no escopo do app, como excluir e concluir tarefa já
  eram. Concluir uma rotina só oferece quando a conclusão foi de fato gravada: um segundo toque
  (widget e Hoje juntos) não gera um Desfazer que apagaria a conclusão do primeiro.
- **Desfazer direto descarta a oferta do mesmo alvo.** Desmarcar um bloco pela Hoje, ou tirar o
  pular pelo bloco esmaecido, age na hora e sem oferta; se houver uma oferta pendente para a
  mesma rotina no mesmo dia, ela é descartada. Sem isso, a mensagem antiga continuaria na tela
  e o Desfazer dela apagaria uma conclusão feita depois por outro caminho.
  O mesmo vale para a tarefa: desmarcar pela Hoje ou desfazer a conclusão pelo detalhe descarta a
  oferta daquele registro. Ali o Desfazer atrasado já não fazia nada, mas a mensagem ficava na tela.
- **"Concluídas hoje" na aba Tarefas (P5):** pela mesma definição de feitas hoje da Hoje (14.4),
  o registro do dia. Consequência aceita: a repetida concluída adiantada aparece em "Concluídas
  hoje" e também em "Próximas". As duas linhas estão certas.
  **Correção de 2026-11-24:** a redação anterior dizia "com a data seguinte", como se a conclusão
  adiantada consumisse a próxima vez. Não consome, nem consumia na v1.8.0: numa tarefa repetida,
  concluir num dia que não é dia dela só grava o registro daquele dia; a próxima ocorrência
  continua onde estava e aparece pendente no dia dela. Se concluir adiantado deve valer pela
  próxima vez é pergunta de produto e entra no parecer da v1.10.0, junto com a repetida não
  feita (15.9, P1): as duas dependem da mesma regra, a de qual ocorrência um registro satisfaz.

### 15.7 Dívidas de tela que entram

Os desenhos são os da 14.13: nome do dia da faixa da semana em uma linha (três letras ou uma
letra para os sete, por medida); horário do bloco em três degraus (faixa inteira, só a duração,
nada); estado vazio com o preenchimento da tela por dentro da rolagem; foco no Título do painel
só na primeira abertura; progresso sem "0 de 0" em dia sem itens; arrastar não fecha o painel em
paisagem com teclado; aviso de lint do painel. Repetição personalizada sem dia válido fica
inalcançável no editor, e o domínio lê com tolerância o dado que já existir assim.

Hifenização dos títulos de bloco: **ensaiada e cortada.** Com fonte 2.0 em 320dp o resultado ficou
misto (uma palavra com hífen, outra quebrada sem hífen), o que é pior que a quebra uniforme de
antes. O título volta ao estilo da v1.8.0 e vale o limite aceito na 14.13.

**Horário escondido e leitor de tela.** No terceiro degrau o horário não é desenhado, e um nó sem
largura some da árvore de acessibilidade: o bloco era lido sem hora. Regra: a frase inteira do
horário fica num nó que **sempre tem tamanho** (a linha que contém o horário, o sino e a lista),
não no texto que pode não ser desenhado. Teste de semântica no terceiro degrau: o bloco continua
expondo a faixa inteira.

A conferir antes de virar trabalho, porque podem já ter sumido: atalho do app com editor aberto
perdendo o rascunho; diálogo de descartar no painel de nova tarefa; entrega do catch-up com
título antigo ou aviso que pisca.

### 15.8 Etapas e critérios de aceite

| Etapa | Conteúdo | Aceite |
|-------|----------|--------|
| E0 | Dívidas de tela (15.7), sem tocar em modelo | Testes de medida e de composição por item; capturas em 320 e 360dp com fonte 2.0 e em paisagem |
| E1 | Repositório do salvar na hora (15.2), sem tela | Cada mudança aplicada sobre linha alterada por fora (concluir, pausar, lista excluída) sem perder nenhum dos dois lados; alarme refeito só quando deve; título vazio nunca gravado; ordem das travas preservada. Revisão do arquiteto linha a linha antes dos testes |
| E2 | Detalhe da tarefa (15.3, 15.4). Depois da E1 | Concluir pela notificação com o detalhe aberto não perde a digitação nem a conclusão; sair no meio da digitação grava; título vazio volta ao último válido; testes de semântica |
| E3 | Editor de rotina (15.3, 15.5). Depois da E1, em paralelo com a E2 | Nenhum alarme antes de "Criar rotina"; pausar por fora com o editor aberto não é desfeito por uma edição; estados inválidos inalcançáveis; testes de semântica |
| E4 | Hoje e aba Tarefas (15.6). Depois da E2 | Mensagens da Hoje sobrevivem a girar; "Concluídas hoje" igual nas duas telas |
| E5 | Fechamento | Jornadas do Baseline Profile reescritas (as da v1.8.0 tocam em "Salvar" nos editores) e perfil regerado; tamanho do APK; matriz manual; atualização por cima da v1.8.0 com editor aberto; abertura a frio pela P7 |

Uma rodada de Gradle por etapa; não se acumula código sem compilar de mais de uma etapa.

### 15.9 Fora da v1.9.0

- **v1.10.0, com parecer próprio (P1):** tarefa repetida não feita como atrasada, na forma
  curta: a ocorrência perdida fica atrasada **até o dia da próxima ocorrência** e então some; só
  uma aberta por tarefa. Uma diária nunca aparece atrasada. Calculável com os registros de
  conclusão que já existem, sem coluna nova. Muda a regra de estado no domínio e tudo que lê
  dela: aba Tarefas, Hoje, widgets, resumo diário e a oferta de concluir pela lista. A forma
  longa (cobrar cada ocorrência perdida) exigiria estado por ocorrência e migração; rejeitada.
- **v1.10.0:** o repositório passar o início calculado para os alarmes de rotina; fonte única de
  data para o app; posição do concluir na Hoje; adiamento gravado e no diagnóstico; e o escopo
  que já era da v1.10.0 (adaptativo, calendário, busca, target 36).
- **Só registro:** o lembrete de tarefa e o aviso de bloco que já estão na tela não são reemitidos
  quando o título muda; continuam com o texto de quando tocaram, e o próximo sai com o título
  novo. É como sempre foi. Também: a duração não reagenda o alarme da rotina, que é só do início.
- **Só registro:** repetição personalizada gravada sem dia utilizável, se existir em dado antigo,
  continua classificada como na v1.8.0. A tolerância fica só na leitura do editor, onde ela
  aparece como "não repete"; tolerar só em parte da classificação seria pior.
- **Limite conhecido:** a aba Tarefas lê a data de hoje e os registros do dia de dois relógios
  diferentes. Na virada da meia-noite com a aba aberta, o filtro pela data da tela impede que um
  registro de ontem conte hoje, mas o caso não tem teste, por falta de relógio injetável ali.
  Resolve-se com a fonte única de data, na v1.10.0.
- **Só registro:** na aba Tarefas não há desmarcar pela seção "Concluídas hoje". Uma conclusão
  feita por engano se desfaz pela mensagem, pelo detalhe da tarefa ou pela Hoje.
- **Só registro:** concluir vários itens em seguida na Hoje deixa só o Desfazer do último, porque
  a oferta é uma por vez; antes as mensagens da Hoje entravam em fila.
- **Só registro:** o bloco que veio remarcado de outro dia é concluído no dia mostrado, não no dia
  da ocorrência; pular e remarcar usam o dia da ocorrência. Já era assim na v1.8.0.
- **Backlog:** pular uma rotina que deixou de existir ainda gera a mensagem com Desfazer, que não
  faz nada.

### 15.10 Riscos

| Risco | Mitigação |
|-------|-----------|
| Perda de dado por sobrescrita com leitura antiga | É o motivo da E1: mudança aplicada sobre a linha mais recente, dentro da trava |
| Rajada de cancelar e agendar alarme a cada toque | Alarme só refeito quando o horário muda; texto com espera de 500 ms |
| Cursor pulando e tecla perdida em campo de texto | Buffer local é regra, não opção (15.3) |
| Reverter desfazendo o que mudou por fora | Reverter só envia mudanças dos campos do editor |
| Restaurar backup com editor aberto | Caso de teste próprio; a tela mostra o banco, então acompanha |
| Coluna nova pedida no meio do caminho | Nenhuma prevista; banco continua 11 e o backup continua na versão 6. Se alguma etapa pedir, para e volta ao arquiteto |
| Jornadas do perfil quebradas pela saída do "Salvar" | Reescrita prevista na E5 |
| Memória do host | Etapas pequenas, um Gradle por vez, um dev pleno além do sênior |

### 15.11 Abertura a frio (P7)

A v1.8.0 saiu sem medida (14.14). No fechamento da v1.9.0, com liberação explícita do usuário e
backup confirmado, mede-se no aparelho dele v1.7.1, v1.8.0 e v1.9.0 numa sessão só, pelo
protocolo da 11.8.3 com as regras da 13.6 e da 14.14 (instalar antes de limpar os dados; o passo
de espera do arnês). Sem a liberação, o critério fica registrado de novo como não medido. Não se
tenta no emulador.

### 15.12 Fechamento da v1.9.0 (2026-11-24)

Commit final `774fba6` (versionCode 10900). CI verde nesse commit: 1088 testes, 0 falhas.

**Baseline Profile.** Regerado em `e0aaacd`, 33780 linhas. Cobre `ui/edit`, o detalhe da tarefa,
Hoje, Tarefas, Listas, Calendário, Ajustes e as ofertas de desfazer. **Lacuna aceita:**
`ui/routines` e `ui/routineeditor` não entram, porque a jornada que percorre as rotinas sai cedo,
em silêncio, sem criar a rotina de exemplo, e o teste passa mesmo assim. A v1.8.0 já tinha a
mesma lacuna; não é regressão, mas o editor de rotina, entrega central desta versão, abre sem
perfil. Vai para a v1.10.0: corrigir a jornada, e **toda jornada passa a falhar quando não acha
o caminho**, em vez de sair cedo. O aceite de um perfil gerado é pela presença dos pacotes no
arquivo, não pelo teste verde.

Tentou-se uma segunda geração com a jornada corrigida, e ela **falhou**: caiu em 1m33s, na
primeira iteração, porque a jornada das tarefas saiu do app. O passo de voltar aceitou o widget
da tela inicial, que tem a mesma descrição "Nova tarefa", por procurar o elemento sem restringir
ao pacote do app; a jornada das rotinas nem chegou a rodar. Pela regra de uma tentativa só,
ficou o perfil de `e0aaacd`, que não foi tocado. As jornadas corrigidas estão em `02e3c97`
(dev/forja), fora da v1.9.0. Regra que sai daqui: todo seletor de jornada restringe ao pacote do
app.

**APK de release com R8.** 3.372.207 B, contra 3.354.191 B da v1.8.0 (+18.016 B, +0,54%).
Verificação do R8 sem erro. O perfil está no APK.

**Abertura a frio: NÃO MEDIDA.** O aparelho do usuário não foi liberado (P7), e no emulador não
se tenta (15.11, 14.14). O critério fica em aberto pela segunda versão seguida; a medida de
v1.7.1, v1.8.0 e v1.9.0 numa sessão só continua pendente da liberação.

**Matriz manual no release final: aprovada, nada bloqueante.** Sem falha fatal nem ANR em toda a
sessão; dados do emulador restaurados e conferidos no fim.

- Atualização por cima da v1.8.0 com o editor de rotina e com o detalhe da tarefa abertos e texto
  não salvo: o app abre, o que estava gravado fica intacto, o alarme da rotina continua; o texto
  não salvo da v1.8.0 se perde, como esperado. Banco 11 nas duas versões.
- Editores no release: digitação em rajada correta nos dois; matar o app um segundo depois de
  digitar não perde nada; Reverter; título vazio volta ao último válido; o Desfazer de rotina
  concluída sobrevive a girar e a trocar de aba.
- Cinco telas em seis combinações de largura, fonte e orientação: nada cortado sem rolagem e
  botões ao alcance, com a exceção registrada abaixo.
- Alarmes: mudar o início refaz o alarme na hora e o aviso toca; mudar só o título ou a duração
  não mexe no alarme; pausar, retomar e ligar ou desligar o aviso refletem no alarme; o lembrete
  de tarefa mudado no detalhe toca; concluir pela notificação com o detalhe aberto mantém a nota
  digitada.
- Backup: exportação na versão 6 com banco 11; restaurar com gravação pendente num editor aberto
  termina com os dados restaurados e os alarmes refeitos.

**Achados do fechamento, fora da v1.9.0:**

- **Título do editor de rotina cortado em paisagem com fonte 2.0 e teclado aberto.** Entre a
  barra do topo e o teclado sobra menos altura do que o campo ocupa, e o texto aparece pela
  metade. Dá para digitar, e com fonte 1.0 não ocorre. A causa é a barra do topo fixa em altura
  compacta; a correção (a barra rolar junto ou encolher nessa altura, em todas as telas de
  edição) é do trabalho adaptativo da v1.10.0.
- **Aviso de rotina pode atrasar com o aparelho dormindo.** Defeito que já existia na v1.8.0. Há
  um único alarme para todas as rotinas, no próximo início de qualquer bloco. Quando esse início
  não avisa (aviso desligado, ou já concluído no dia), o alarme é silencioso e não acorda o
  aparelho; o alarme do bloco seguinte, que avisa, só é agendado quando o silencioso dispara. Com
  o aparelho dormindo, o aviso seguinte sai só quando ele acordar. Não foi reproduzido, só lido no
  código. A correção exige separar o alarme que avisa (exato, no próximo início que avisa e ainda
  não foi concluído) do alarme que só redesenha os widgets, com parecer próprio. Não entra numa
  versão já fechada e testada; é o **primeiro item da próxima versão**, antes do escopo novo.

## 16. Emenda de 2026-11-25 — escopo e desenho da v1.10.0

Base: `main` em d67327d (v1.9.1 publicada). As decisões P1 a P12 são do usuário.

### 16.1 Decisões do usuário

> **A P12 foi substituída pela 16.10**, que traz as decisões seguintes do usuário sobre rotina, tarefa e lista.

| # | Decisão |
|---|---------|
| P1 | O escopo que a 14.1 chamava de v1.10.0 fica **dividido em duas versões** (16.2) |
| P2 | No dia da atualização, as repetidas já perdidas aparecem atrasadas de uma vez, sem tratamento especial; somem sozinhas na próxima ocorrência de cada uma |
| P3 | Concluir adiantado uma tarefa repetida **vale pela próxima ocorrência**, limitado a uma ocorrência à frente: ela aparece concluída no dia dela e o lembrete daquele dia não toca. Primeiro corte se o prazo apertar |
| P4 | Trilho lateral em tela larga **e** no celular em paisagem (v1.11.0) |
| P5 | Busca global por uma lupa na barra do topo das quatro abas; inclui tarefas concluídas e rotinas pausadas, marcadas como tal; não inclui o excluído; a busca da aba Tarefas continua como filtro da aba (v1.11.0) |
| P6 | Calendário abre em mês, recolhe para semana por um puxador e ao rolar a lista do dia, e lembra a escolha só enquanto o app está aberto (v1.11.0) |
| P7 | Excluir pelo detalhe da tarefa deixa de pedir confirmação; fica só o Desfazer, como na rotina |
| P8 | A posição do concluir na Hoje não muda nesta versão |
| P9 | Material 3 Expressive fica fora |
| P10 | Aparelho do usuário **não liberado**: a abertura a frio continua não medida (16.9) |
| P11 | Target 36, não 37; o 37 pede parecer próprio (v1.11.0) |
| P12 | No editor de rotina, a seção "Lista de compras" só aparece quando a rotina tem lista; sem lista, o "Adicionar lista de compras" fica dentro de "Mais opções". A mesma regra vale para o detalhe da tarefa. Entra na v1.10.0 (16.6) |

### 16.2 Divisão (P1)

> **Substituída pela 16.10.2:** são três versões; a v1.10.0 é "Rotinas e tarefas".

| Versão | Nome | Conteúdo |
|--------|------|----------|
| **v1.10.0** | Tarefas repetidas | Base técnica (16.3), regra das repetidas (16.4, 16.5), conteúdo da rotina no editor (16.6), P7 |
| **v1.11.0** | Adaptativo, calendário e busca | Barra ou trilho (B5), título cortado em altura compacta, calendário recolhível (D4), busca global (B4), target 36 (F6) |

Motivo, o mesmo da 14.1: juntas, a matriz manual teria de cobrir uma mudança de regra lida por
cinco superfícies mais o lembrete de tarefa, a troca da casca de todas as telas, duas telas novas
e o target. O target 36 não tem prazo externo, porque a distribuição é pelo GitHub, e vem depois
do adaptativo porque o Android 16 deixa de respeitar restrição de janela em tela larga.

Onde a 15.9 e a 15.12 dizem "v1.10.0" para o adaptativo, o calendário, a busca, o target 36 e o
título cortado do editor em paisagem, leia-se **v1.11.0**.

### 16.3 Base técnica, sem mudar comportamento

- **Relógio único do app.** A data de hoje era lida direto do relógio em onze arquivos de
  produção. Passa a existir uma peça só, criada no `KalendrysApp`, que dá agora, fuso e a data de
  hoje como fluxo; repositórios e ViewModels a recebem por parâmetro. É pré-requisito da 16.4,
  que depende de "hoje" e precisa de teste de virada de dia, e fecha o limite da meia-noite da
  aba Tarefas (15.9).
  Regras da peça (`AppClock`, revisada em 68051ed):
  - **Só dois primitivos**, o instante de agora e o fuso, lidos a cada chamada. Hoje, a hora local
    e o fluxo da data saem deles, para que agora e hoje não possam discordar, nem num relógio de
    teste. O relógio de teste tem um instante só; avançá-lo move tudo.
  - **O domínio continua puro:** recebe data, instante e fuso por parâmetro, nunca o relógio.
  - **Tela só lê o relógio para valor que é da própria tela** (data inicial de um seletor, o
    minuto corrente, um rótulo de "hoje"). O que decide o que aparece numa lista vem do estado do
    ViewModel.
  - **As entidades não carimbam a criação sozinhas.** A data de criação da rotina entra na regra
    de ocorrência, então um valor padrão lido do sistema era uma leitura de hoje escondida. Quem
    cria passa o instante. O esquema do banco não muda.
  - **Preso por teste:** um teste varre o código de produção e falha em qualquer leitura direta
    de hora, data ou fuso do sistema fora de duas exceções escritas nele: a própria peça e a
    migração já publicada, que não se altera.
  - Limite conhecido, igual ao de antes: o fluxo da data não reemite quando a hora ou o fuso
    mudam com a coleta em andamento; só na meia-noite seguinte ou ao recomeçar.
- **Instante pronto para os alarmes de rotina.** O repositório passa ao `RoutineAlarms` o
  instante já convertido, em vez de data e hora locais convertidas lá com o fuso do sistema
  (ADR-0003, 8.6). Os extras de dia e minuto do alarme, que ninguém lê, saem.
- **Jornadas do Baseline Profile** corrigidas (15.12), com duas regras: todo seletor restrito ao
  pacote do app, e toda jornada falha quando não acha o caminho.
- Miúdos: a mensagem de pular uma rotina que não existe mais; o botão flutuante cobrindo a última
  linha da aba Tarefas em 320dp com fonte 2.0.

### 16.4 Tarefa repetida: qual ocorrência um registro satisfaz

> **Movida pela 16.10.2:** esta regra e a 16.5 passam para a **v1.11.0**. O desenho não muda.

Até a v1.9.1 a tarefa que se repete nunca ficava atrasada: o estado era só "hoje" ou "próxima".
E concluir num dia que não é dia dela só gravava o registro daquele dia (15.6).

Uma regra só responde às duas coisas, sem coluna nova, contando os registros de conclusão numa
janela. **Janela:** da ocorrência anterior a hoje (inclusive) até a próxima (exclusive).

| Registros na janela | Significado |
|---------------------|-------------|
| Nenhum, e hoje não é dia dela | A ocorrência anterior está **atrasada**, até o dia da próxima |
| Um | A ocorrência anterior está feita |
| Dois ou mais | A próxima está feita **adiantada** (P3), e só ela |

- Forma curta (15.9, P1 da v1.9.0): só uma ocorrência aberta por tarefa. Uma diária nunca atrasa.
  Tarefa sem repetição não muda.
- Concluir uma repetida atrasada grava o registro de hoje e ela sai de "Atrasadas".
- Lógica pura em `domain/`, numa função só. O domínio ganha a ocorrência **anterior** a uma data,
  para cada tipo de repetição, inclusive a personalizada.
- Os registros são lidos por tarefa a partir de uma data, numa consulta para a lista inteira.
- Banco continua 11 e o backup na versão 6.

A tabela é a regra de produto; os casos de borda (registro no próprio dia da ocorrência, primeira
ocorrência sem anterior, tarefa que deixou de repetir) são fechados na revisão da E1.

### 16.5 Quem lê a regra

Aba Tarefas, Hoje, widgets, resumo diário e a oferta de concluir pela lista leem da mesma função;
os números têm de ser os mesmos nas superfícies para os mesmos dados.

Ocorrência consumida por conclusão adiantada (P3): no dia dela a tarefa aparece concluída, o
lembrete daquele dia não é agendado nem entregue pelo catch-up, e desfazer a conclusão devolve a
ocorrência e o lembrete. A regra do lembrete está no ADR-0003, seção 9. É a parte de maior risco
da versão e fica em commit próprio.

### 16.6 Conteúdo da rotina no editor

> **Substituída pela 16.10:** rotina não tem lista; a lista é de uma tarefa só (16.10.4, 16.10.5).

**Em aberto na data desta emenda.** A P12 decidiu a lista de compras. Depois dela o usuário pediu
para unificar lista de compras e exercícios e tirar as categorias das rotinas; o desenho proposto
e as perguntas P13 a P18 aguardam resposta, e esta subseção é reescrita quando chegarem. Nenhum
código desta parte antes disso.

O que já vale: a seção de lista só existe quando a rotina tem lista; sem lista não há título,
frase nem cartão, e o único lugar para anexar é "Mais opções"; anexar faz a seção aparecer na
hora e a tela rola até ela; desvincular faz sumir; Reverter e a criação seguem as regras da 15.5.

### 16.7 Etapas da v1.10.0

> **Substituída pela 16.10.6:** a v1.10.0 passou a ter as etapas E0 e T1 a T4; E1 e E2 abaixo são da v1.11.0.

| Etapa | Conteúdo | Aceite |
|-------|----------|--------|
| E0 | Base técnica (16.3) | Suíte verde sem teste apagado; nenhuma leitura direta do relógio em `ui` e `data` fora da peça única; teste de virada de dia na aba Tarefas |
| E1 | Regra das repetidas no domínio (16.4), sem tela | Teste por tipo de repetição, virada de dia e fuso. Revisão do arquiteto linha a linha antes dos testes |
| E2 | Leitores da regra e lembrete (16.5). Depois da E1 | Mesmos números em Hoje, aba Tarefas, widget e resumo; ocorrência consumida não toca; atualização por cima da v1.9.1 sem aviso duplicado |
| EX | Conteúdo da rotina no editor (16.6) e P7. Em paralelo, depois das respostas | Os da 16.6 |
| E3 | Fechamento | Perfil regerado e aceito por linhas por pacote, inclusive rotinas; tamanho do APK; matriz manual; atualização por cima da v1.9.1 |

Ordem de corte: primeiro a P3 (a regra do atraso fica); depois nada mais da E0 à E2.

### 16.8 Fora da v1.10.0

- **v1.11.0:** o que a 16.2 lista.
- **Sem versão marcada:** lista e detalhe lado a lado; adiamento que sobrevive a reinício e
  aparece no diagnóstico; alarme próprio do widget Resumo; Material 3 Expressive.
- **Com parecer próprio no ADR-0003, quando pedido:** reaviso de bloco em troca de fuso para
  oeste e marca do catch-up que anda para trás quando o relógio atrasa (ADR-0003, 8.6).

### 16.9 Riscos e abertura a frio

| Risco | Mitigação |
|-------|-----------|
| Regra lida por cinco lugares divergindo | Uma função só e teste de números iguais |
| Muitas repetidas atrasadas de uma vez no dia da atualização | Aceito (P2); somem na próxima ocorrência |
| P3 mexe no lembrete de tarefa: aviso perdido ou duplicado | Commit próprio, ADR-0003 seção 9, primeiro corte |
| Relógio único toca muitos arquivos | E0 sozinha, sem mudança de comportamento, antes de tudo |
| Coluna nova pedida no meio do caminho | Nenhuma prevista; se alguma etapa pedir, para e volta ao arquiteto |

Abertura a frio: **não medida** desde a v1.8.0 (14.14, 15.12). O aparelho do usuário não foi
liberado (P10) e no emulador não se tenta. Fica registrado de novo como não medido.

### 16.10 Emenda de 2026-11-25 (segunda) — rotina, tarefa e lista

Depois da P12 o usuário redefiniu a distinção entre rotina e tarefa, em quatro rodadas de
perguntas (P13 a P46). Esta subseção é o resultado final e **substitui** a P12 da 16.1, a 16.2,
a 16.6 e a 16.7. Corrige também o ADR-0001 (seção 9 de lá). As decisões são do usuário.

#### 16.10.1 A distinção

- **Rotina:** bloco de hábito com horário e duração, que se repete. Tem **tipo**, Nenhum ou
  Exercício, escolhido ao criar e ao editar. Só o tipo Exercício tem conteúdo: os exercícios.
  Rotina **não tem lista**. As categorias criadas pelo usuário deixam de existir nas telas.
- **Tarefa:** coisa pontual, com ou sem data e horário. É a **única dona de lista**, de compras
  ou em geral. Com data e horário, aparece na Hoje entre as rotinas, no horário dela, com formato
  próprio; isso já existia desde a v1.8.0 (14.5) e não muda. Tarefa não ganha duração.
- **Lista:** pertence a **uma tarefa só**. Lista sem tarefa não existe. Excluir a tarefa exclui a
  lista e os itens.
- **Não há aba Listas.** A barra fica com Tarefas, Hoje e Calendário.

#### 16.10.2 Divisão das versões

| Versão | Nome | Conteúdo |
|--------|------|----------|
| **v1.10.0** | Rotinas e tarefas | Base técnica (16.3) e esta subseção |
| **v1.11.0** | Tarefas repetidas | 16.4 e 16.5, com a P2 e a P3 |
| **v1.12.0** | Adaptativo, calendário e busca | O que a 16.2 dava à v1.11.0 |

Não se juntam a v1.10.0 e a regra das repetidas: uma tem migração, backup novo, troca de
navegação e reescrita do editor; a outra muda a regra de estado em cinco superfícies e o lembrete
de tarefa. A regra das repetidas fica mais importante depois desta emenda, porque a compra
recorrente passa a ser tarefa repetida.

#### 16.10.3 Dados: banco 11 para 12

Nenhuma tabela é recriada. Na ordem:

1. Coluna nova de **tipo** na rotina. Rotina em categoria do tipo Exercício vira Exercício; todas
   as outras, Nenhum.
2. Tabela nova de **exercícios salvos**.
3. O vínculo de lista é apagado de **todas as rotinas**. Nenhuma rotina é apagada.
4. **Lista usada por mais de uma tarefa:** fica com a tarefa mais antiga; cada uma das outras
   recebe uma cópia da lista com os itens (P44). Assim nenhuma tarefa perde a lista que mostrava.
5. Vínculo de tarefa para lista que não existe é limpo.
6. Lista sem tarefa é **apagada com os itens**: as avulsas e as que só rotinas usavam. Itens sem
   lista também.
7. Índice único no vínculo de lista da tarefa: o banco passa a recusar duas tarefas na mesma
   lista. Só é criado depois dos passos 4 a 6. **Decidido na revisão da T1 (87ac09c): o índice
   fica.** O banco 11 não tinha índice com esse nome, os passos 4 a 6 garantem a unicidade antes
   dele, e o restauro de backup passa pelas mesmas regras antes de gravar. Consequência: todo
   caminho que grava o vínculo de lista numa tarefa confere antes se a lista é de outra, em vez
   de deixar o banco recusar.

As regras dos passos 4 a 6 e a do tipo estão **congeladas**: uma migração publicada depende
delas. Regra nova numa versão futura é função nova, não mudança nessas.

A coluna antiga do vínculo na rotina e a tabela de categorias continuam no banco, sem uso:
tirá-las exige recriar a tabela de rotinas, que é a dos alarmes. Exercícios de rotinas que já
foram de categoria Exercício e mudaram continuam guardados e voltam se o tipo for Exercício.

O descarte dos passos 3 e 6 foi **autorizado pelo usuário**, que tem dados reais no aparelho.
Condição da release (P43): backup exportado pela v1.9.1 antes de atualizar; depois da migração não
há volta sem ele. O CHANGELOG e a nota da versão avisam.

**Backup versão 7.** Leva o tipo e os exercícios salvos; deixa de levar vínculo de lista em
rotina. Restaurar um backup da versão 6 ou anterior aplica as **mesmas regras** dos passos 1 e 3
a 6. As regras são funções puras em `domain/`, usadas pela migração e pelo restauro, com teste de
que os dois caminhos dão o mesmo resultado para os mesmos dados. O restauro da versão 7 passa
pelas mesmas funções, que não mudam nada em dado já correto.

#### 16.10.4 Lista dentro da tarefa

- **Adicionar lista** cria a lista na hora, com o nome da tarefa. Não há mais escolha entre nova
  e existente, nem seletor. O painel de nova tarefa continua podendo criar a lista junto.
- A seção de lista do detalhe da tarefa fica à vista, como era. A lista abre na mesma tela de
  lista de sempre: itens, adicionar rápido, marcar, total, zerar marcados, dica de gestos.
- **Excluir lista** (no detalhe e na tela da lista) apaga a lista e os itens, com confirmação
  (P45). Não existe mais desvincular. O Reverter do detalhe não desfaz criar nem excluir lista.
- **Excluir a tarefa** apaga a tarefa, a lista e os itens numa transação. A oferta de Desfazer
  guarda os três em memória e os devolve juntos, com os mesmos identificadores, no mesmo molde em
  que já guardava a tarefa inteira (14.2). Se o processo morrer antes, a exclusão vale. Excluir
  pelo detalhe não pede confirmação (P7): a proteção é o Desfazer.
- A lista de uma tarefa sem repetição já concluída continua com ela.
- **Link de lista.** Continua existindo, porque notificações e ofertas abrem a lista por ele. A
  regra de pilha E-1 muda: a lista abre sobre o detalhe da tarefa dona, e esse sobre a aba
  Tarefas. Lista que não existe mais abre a aba Tarefas.
- **Atalho do app.** A primeira redação dizia que o atalho "Listas" saía. Está **corrigida**: o
  Android desativa um atalho fixado na tela inicial quando o atalho estático correspondente
  some do app. O atalho estático fica, com o **mesmo identificador**, o rótulo "Tarefas" e
  abrindo a aba Tarefas; quem tinha o antigo fixado continua com um atalho que funciona.
- **Rota e link antigos da aba.** A rota da aba Listas e o link que a abria redirecionam para a
  aba Tarefas. Quem atualizar com a aba Listas aberta tem o estado salvo restaurado sem erro, como
  foi feito com a rota das rotinas na v1.8.0.
- O carrinho de progresso some dos blocos de rotina e aparece na linha da tarefa na Hoje.

#### 16.10.5 Rotina: tipo e exercícios

- Ordem do editor: Título; Quando; Lembrar; Tipo; Exercícios (só no tipo Exercício); Aparência;
  "Mais opções" com Vigência e Observações. Saem as seções Categoria e Lista.
- Os exercícios podem ser montados já na **criação**; são gravados junto com a rotina.
- Trocar de Exercício para Nenhum **guarda** os exercícios, sem perguntar; voltam se o tipo
  voltar. O tipo entra nos campos do Reverter.
- **Exercícios salvos:** um salvo é um exercício (nome, séries, repetições, carga, descanso).
  Aplicar um salvo **copia**; não há vínculo vivo. São escolhidos, editados e excluídos num painel
  do próprio editor, e criados pela opção de salvar no diálogo do exercício. **Excluir um salvo
  pede confirmação**, pelo mesmo critério do excluir lista (P45): não há Desfazer.
- Exercícios montados na criação são gravados com a rotina mesmo que o tipo escolhido no fim seja
  Nenhum; ficam guardados, pela mesma regra da troca de tipo.
- "Todas as rotinas" perde o filtro e o nome da categoria na linha. A tela de categorias sai de
  Ajustes.

#### 16.10.6 Etapas da v1.10.0

| Etapa | Conteúdo | Aceite |
|-------|----------|--------|
| E0 | Base técnica (16.3). Feita e revisada em 68051ed | Os da 16.7 |
| T1 | Dados (16.10.3): migração, entidades, funções das regras, backup 7 e restauro, excluir e restaurar tarefa com lista, fim dos caminhos de lista em rotina | Teste de migração com banco real para cada caso; restauro de backup antigo igual à migração; ida e volta do backup 7. Revisão do arquiteto linha a linha antes dos testes |
| T2 | Rotina (16.10.5) | Semântica do editor nos dois tipos e na criação; salvo é cópia; Reverter |
| T3 | Navegação e listas (16.10.4): três abas, adicionar e excluir lista, pilha do link, atalho, Desfazer com lista, carrinho na linha da tarefa | Voltar a partir de lista aberta por notificação nunca sai do app; atalho antigo não quebra; excluir tarefa e desfazer devolve lista e itens |
| T4 | Fechamento | Jornadas e perfil, tamanho do APK, matriz manual, atualização por cima da v1.9.1 com dados de cada caso da migração, aviso no CHANGELOG |

T2 corre em paralelo com T1 e T3. Ordem de corte: primeiro a tarefa aninhada no bloco (16.10.8,
último item da T3); depois os exercícios salvos.

#### 16.10.7 Riscos

| Risco | Mitigação |
|-------|-----------|
| Migração que apaga dados num aparelho com dados reais | Autorização do usuário, backup como condição da release, aviso no CHANGELOG, teste com banco real de cada caso |
| Índice único falhando na migração e travando a abertura do app | Criado por último, depois da limpeza; teste com listas compartilhadas e vínculos órfãos; pode sair na revisão da T1 |
| Regras da migração e do restauro divergindo | Funções puras únicas e teste cruzado |
| Lista de tarefa concluída e escondida fica fora de alcance | Aceito; não é dado perdido, a tarefa a mantém |
| Barra mexida agora e de novo na v1.12.0 | Aceito pelo usuário ao tirar a aba agora |
| Dado parado no banco (coluna e tabela sem uso) | Registrado; limpeza só com parecer próprio |

**Confirmado pelo usuário em 2026-11-25**, depois da primeira redação desta subseção: a cópia da
lista compartilhada para as outras tarefas (P44, passo 4 da 16.10.3), a confirmação ao excluir
lista, sem Desfazer (P45), e o nome da lista nova igual ao da tarefa (P46). A emenda está
aprovada.

Pendente na data desta emenda:

- O resultado da conferência visual da Hoje com tarefas entre rotinas, pedida pelo usuário; se
  trouxer ajuste, entra na T3.

#### 16.10.8 Tarefa dentro do horário de uma rotina, na Hoje (P47 a P50)

Observação do usuário: a linha do tempo ordena pelo início e o bloco é uma linha só, então uma
tarefa das 7:30 aparece **depois** do bloco das 7:00 às 8:00, embora esteja dentro do horário
dele. Com as compras virando tarefa, o caso fica mais comum.

Decisão (P47): a tarefa fica **aninhada no cartão da rotina**, entre o cabeçalho e o fim do bloco,
com a faixa colorida da rotina contínua de cima a baixo. Não se parte o bloco em linhas soltas: ele
é uma linha com um toque, um menu de segurar, um círculo de concluir, um destaque de em andamento
e um nó de leitura, e cada pedaço teria de repetir ou esconder tudo isso.

- **O domínio decide quem está dentro de quem**, em função pura na montagem da linha do tempo:
  cada bloco leva as tarefas que caem na janela dele, e elas saem da trilha de fora. A tela só
  desenha.
- **Limites (P49):** tarefa no minuto de início está dentro; no minuto do fim, fora, depois do
  bloco. Bloco sem duração não contém nada.
- **Rotinas sobrepostas:** a tarefa fica no bloco que começou por último entre os que a contêm.
- **Bloco pulado não contém nada:** as tarefas voltam para a trilha de fora, porque o pulado já é
  tratado como se não existisse (14.5). Bloco concluído mantém as tarefas com o estado delas.
- **Cabeçalho sem mudança:** título, horário, círculo de concluir, exercícios, toque e segurar.
  Cada tarefa de dentro tem a própria hora, caixa de concluir, título e carrinho. O fim do bloco é
  só a faixa fechando, sem linha de horário (P48).
- **Leitor de tela:** o bloco continua um nó; cada tarefa de dentro é um nó próprio, lido em
  seguida, dizendo de qual rotina está dentro.
- Não mudam: tarefa atrasada e sem horário (fora da trilha), widgets, Desfazer, intervalos livres
  e ligações entre blocos. A contagem de feitos continua somando as tarefas de dentro; tem teste.

Entra na v1.10.0 como **último item da T3**, em commit próprio, e é o **primeiro corte** da versão
(P50), antes dos exercícios salvos. **O desenho só é aprovado depois das capturas** do caso atual
em 360dp e em 320dp com fonte 2.0: o risco é a largura que sobra para o título da tarefa de dentro.

**Desenho fechado depois das capturas (2026-11-25).** A conferência na v1.9.1 confirmou o relato e
mostrou o defeito de verdade: a **coluna de horas volta no tempo**. O bloco mostra o início em
cima e o fim embaixo; as tarefas de dentro vêm depois, então a coluna lê 07:00, 08:00, 07:00,
07:30. Com isso o desenho fica assim:

- A faixa colorida do bloco (a pílula) **se alonga** para cobrir o cabeçalho e as tarefas de
  dentro. O rótulo do fim do bloco continua na coluna de horas, **embaixo da faixa alongada**,
  depois das tarefas: a coluna volta a ler em ordem. Não é uma linha nova; é o rótulo que já
  existia, no lugar certo.
- A tarefa de dentro **mantém a linha de hoje**: hora na coluna de horas, o losango sobre a faixa,
  a caixa tracejada e o círculo de concluir. **Não há recuo a mais**, então a largura do título é a
  mesma de hoje, e o risco de 320dp com fonte 2.0 que condicionava a aprovação não se confirma.
- **Bloco pulado** continua sem conter tarefas e passa a mostrar **só o início** na coluna de
  horas, para não deixar o fim dele antes das tarefas que vêm em seguida.
- **Limite aceito:** entre duas rotinas sobrepostas a coluna ainda recua (o fim da primeira vem
  antes do início da segunda). É a própria sobreposição; aninhar não resolve.

**Leitura da tarefa na trilha.** Achado da mesma conferência, e entra na T3 **mesmo que o
aninhamento seja cortado**: a linha da tarefa não lê a hora (o rótulo está fora da árvore de
leitura) nem diz que é uma tarefa. Passa a ler tipo, hora, título e estado, e, quando aninhada, de
qual rotina está dentro. É a mesma classe do horário escondido da 15.7.

Outros achados da conferência, fora desta versão: indicador de repetição na tarefa da trilha e
data e hora na linha das atrasadas vão para a v1.11.0, junto com a regra das repetidas; trilha
curta em paisagem com fonte 2.0 é da v1.12.0; o widget não intercala tarefas e rotinas, o que não
é defeito e fica sem versão. Não são defeitos: a mensagem de Desfazer cobrir a linha de baixo, o
botão flutuante cobrir o círculo de uma linha que passa por baixo ao rolar, e a quebra de palavra
em 320dp com fonte 2.0 (limite aceito na 14.13).

**Confirmado pelo usuário em 2026-11-25:** o desenho aninhado com os três ajustes acima; a leitura
da tarefa na T3 mesmo que o aninhamento seja cortado; o indicador de repetição e a data e hora
nas atrasadas na v1.11.0 (P52); e o widget como está, com a decisão sobre intercalar tarefas e
rotinas adiada para depois de usar a v1.10.0 (P51).

**Como ficou no código (revisão da T3, 03bb8ea).** A trilha continua uma lista plana: o cabeçalho
do bloco, as tarefas marcadas com a rotina que as contém, e uma linha de fim do bloco depois da
última, que não é lida nem conta no dia. Limite conhecido: como a ordem é pelo início, uma rotina
curta inteira dentro da janela de outra, ou um bloco pulado, pode cair entre o cabeçalho e as
tarefas de dentro; a faixa do bloco de fora fica interrompida nessa linha e retoma na tarefa.
