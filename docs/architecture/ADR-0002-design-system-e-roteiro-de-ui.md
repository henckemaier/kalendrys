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

- B1: tela Hoje (seção 6). D2: timeline.
- D3: editor de rotina reorganizado. D1: lista e detalhe de tarefa.
- C4: salvar na hora (seção 5).
- F3: leitor de tela.

### v1.9.0 — MINOR (adaptativo, calendário e busca)

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
deslize. Uma dica única, na primeira vez, explica o gesto; o registro de que ela já foi vista
fica no DataStore, fora de `AppSettings` e fora do backup.

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
- `KalendrysApp` redesenha os widgets quando a Aparência muda.
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
- Limpeza: sai a entrada `kotlin-android` do catálogo e do `build.gradle.kts` da raiz.

### 12.8 v1.7.1

- Alvo de toque de 48dp no seletor de dias da semana (F2).
- Diagnóstico de alarmes correto quando não há permissão de alarme exato.
- Painel de nova tarefa: **o rascunho é guardado enquanto o app estiver aberto** e só é
  descartado no Cancelar (decisão do usuário); `resolveListId` sai do arquivo de tela para o
  repositório; título cortado em paisagem.
- Passada de leitor de tela nas telas migradas, e o que o uso da v1.7.0 mostrar.

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
| Piscar de tema ou custo na abertura a frio | Desenho da 12.3; medição na E7 |
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
