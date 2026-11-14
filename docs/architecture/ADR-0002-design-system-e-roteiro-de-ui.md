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
