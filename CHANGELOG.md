# Changelog

Mudanças visíveis do Kalendrys por versão. Regra de versão em `docs/VERSIONING.md`; fluxo de
publicação em `docs/RELEASING.md`.

## v1.10.0 — Rotinas e tarefas (versionCode 11000)

> **⚠️ Antes de atualizar, exporte o backup na v1.9.1** (Configurações → Dados → Exportar backup). Esta versão
> muda o banco (**11 → 12**) e o backup (**versão 7**), e **apaga dados** na atualização:
>
> - **rotinas perdem a lista de compras** (a lista e os itens são apagados; a rotina fica);
> - **listas avulsas, sem tarefa, são apagadas** com os itens;
> - uma lista usada por **várias tarefas** fica com a mais antiga, e cada uma das outras recebe
>   uma **cópia** com os itens;
> - as **categorias de rotina deixam de aparecer** (a rotina de categoria Exercício vira do tipo
>   Exercício; as outras, Nenhum).
>
> **Não há volta para a v1.9.1 sem esse backup** (a v1.9.1 recusa um backup da versão 7). Restaurar na v1.10.0 um backup antigo (versão 6
> ou anterior) aplica as mesmas regras.

Decisão de arquitetura: `docs/architecture/ADR-0002-design-system-e-roteiro-de-ui.md` (seções 16.3
e 16.10) e `docs/architecture/ADR-0001-listas-de-compras-independentes.md` (seção 9). Sem
dependência nova e sem permissão nova.

### Novidades

- **Rotina e tarefa com papéis separados.** A rotina é o bloco de hábito, com horário e duração,
  que se repete; a tarefa é a coisa pontual e a única dona de lista.
- **Tipo da rotina**, Nenhum ou Exercício, escolhido ao criar e ao editar, no lugar da categoria.
  Só o tipo Exercício tem exercícios. Trocar para Nenhum guarda os exercícios, que voltam se o
  tipo voltar.
- **Exercícios já na criação** da rotina, gravados junto com ela, e no Duplicar.
- **Exercícios salvos**: um salvo é um exercício (nome, séries, repetições, carga, descanso),
  criado pela opção de salvar no diálogo do exercício. Aplicar um salvo **copia** o exercício, sem
  vínculo. Os salvos são escolhidos, editados e excluídos num painel do próprio editor; excluir
  um salvo pede confirmação.
- **Lista dentro da tarefa**: uma lista por tarefa. **Adicionar lista** cria a lista na hora, com
  o nome da tarefa; não há mais escolha de lista existente. **Excluir lista** (no detalhe e na
  tela da lista) apaga a lista e os itens, com confirmação.
- **Hoje: tarefa dentro do bloco de rotina.** A tarefa com horário dentro do horário de uma rotina
  aparece **dentro do bloco**, com a faixa da rotina alongada até o fim dela, e a coluna de horas
  lê em ordem. O carrinho de progresso da lista sai do bloco de rotina e fica na linha da tarefa.
- **Leitor de tela na Hoje**: a linha da tarefa lê "Tarefa", a hora, o título e o estado e, quando
  está dentro de um bloco, de qual rotina.

### Mudanças de comportamento

- **A aba Listas sai.** São três abas: Hoje, Tarefas e Calendário. A lista é alcançada pela
  tarefa dona; aberta por uma notificação, ela abre sobre o detalhe da tarefa, e lista que não
  existe mais abre a aba Tarefas. Quem atualizar com a aba Listas aberta reabre na Hoje, sem erro.
- **O atalho "Listas"** do ícone passa a se chamar **"Tarefas"** e abre a aba Tarefas; quem o
  tinha fixado na tela inicial continua com um atalho que funciona.
- **Excluir a tarefa apaga a lista e os itens**; o Desfazer devolve os três. Excluir pelo detalhe
  da tarefa **não pede mais confirmação**: a proteção é o Desfazer.
- **Categorias de rotina saem das telas**: do editor, do filtro e da linha de "Todas as rotinas"
  e de Ajustes.
- A **aba Tarefas vira o dia à meia-noite** com a aba aberta, pelo relógio único do app.
- Pular uma rotina que não existe mais (ou arquivada) não grava nada nem oferece Desfazer.
- O botão flutuante não cobre mais a última linha da aba Tarefas em 320dp com fonte grande.

### Limitações conhecidas

- **Rotinas sobrepostas em parte** ainda fazem a coluna de horas da Hoje recuar (o fim da primeira
  vem antes do início da segunda). Uma rotina curta inteira dentro de outra, ou um bloco pulado,
  pode interromper a faixa do bloco de fora.
- A lista de uma tarefa sem repetição já concluída continua com ela e fica fora de alcance.
- Reordenar exercícios custa dois toques por movimento (menu da linha).
- Marcar "Salvar para reutilizar" ao editar um exercício cria um salvo novo a cada vez.

### Interno

- **Banco 12** (migração automática, sem recriar tabela): tipo na rotina, tabela de exercícios
  salvos, vínculo de lista apagado das rotinas, cópia das listas compartilhadas, limpeza de
  vínculos órfãos e de listas sem tarefa, e índice único no vínculo de lista da tarefa. A coluna
  antiga do vínculo na rotina e a tabela de categorias continuam no banco, sem uso.
- **Backup versão 7**, com o tipo e os exercícios salvos e sem vínculo de lista em rotina. O
  restauro de backup antigo e a migração usam as mesmas funções puras (`domain/ListOwnership`).
- **Relógio único** (`AppClock`): agora, fuso e a data de hoje vêm de uma peça só, recebida por
  parâmetro; um teste de varredura falha em qualquer leitura direta do relógio do sistema fora
  dela. O repositório passa o instante pronto aos alarmes de rotina.
- **Baseline Profile**: não regerado; é o da v1.9.0, sem as telas de rotinas (lacuna conhecida,
  para a v1.11.0). As jornadas foram corrigidas para as três abas e passam a falhar quando não
  acham o caminho.
- **APK de release** com R8: 3.360.179 B (−12.028 B sobre os 3.372.207 B da v1.9.1).
- **Abertura a frio**: **não medida**.
- **Acessibilidade**: leitor de tela com fala real não verificado, coberto por testes de
  semântica.

## v1.9.1 — Aviso de rotina (versionCode 10901)

Versão corretiva, só com este defeito. Decisão de arquitetura:
`docs/architecture/ADR-0003-entrega-de-lembretes-por-catch-up.md` (seção 8). Sem migração de
banco (Room continua na versão 11), sem mudança no backup (formato 6), sem dependência nova e sem
permissão nova.

### Correções

- **Aviso de início de rotina com o aparelho dormindo**: quando o bloco anterior era de uma rotina
  sem aviso (aviso desligado ou já concluída no dia), o aviso do bloco seguinte podia atrasar até
  o aparelho acordar, ou se perder, se passasse de 60 minutos. Existia desde a v1.8.0. Agora o
  aviso tem alarme próprio, que acorda o aparelho no horário.

### Mudanças de comportamento

- O **ícone de despertador** na barra de status e o **próximo alarme** na tela de bloqueio
  aparecem sempre que houver uma rotina com aviso pela frente, mesmo com um bloco sem aviso antes
  dela.
- O **diagnóstico de alarmes** mostra o mesmo horário de aviso de rotina que está agendado no
  sistema.

### Limitação conhecida

- Sem a permissão de alarme exato, o aviso ainda pode atrasar alguns minutos; fabricante que
  encerra o app em segundo plano continua fora do alcance da correção.

### Interno

- Dois alarmes de rotina, calculados juntos pelo repositório: o de aviso, no próximo início que
  avisa e ainda não foi concluído no dia (o `PendingIntent` de antes, para quem atualiza por cima
  da v1.9.0), e o silencioso dos widgets, só quando vem antes dele. Os dois chamam o mesmo
  catch-up.
- **Baseline Profile**: não regerado; é o da v1.9.0.
- **Abertura a frio**: não medida.

## v1.9.0 — Editores (versionCode 10900)

Decisão de arquitetura: `docs/architecture/ADR-0002-design-system-e-roteiro-de-ui.md` (seção 15).
Sem migração de banco (Room continua na versão 11), sem mudança no backup (formato 6), sem
dependência nova e sem permissão nova.

### Novidades

- **Detalhe da tarefa e editor de rotina gravam enquanto se digita**: o texto é gravado 500 ms
  depois da última tecla e sempre ao sair da tela ou ao ir para segundo plano. Somem o "Salvar" e
  o "Descartar alterações?" do detalhe da tarefa e da edição de rotina. Título vazio não é
  gravado: ao sair, o campo volta ao último título válido.
- **Reverter alterações**, no menu dos dois editores: devolve só os campos mudados enquanto a tela
  está aberta, sem mexer em conclusão, pausa ou exclusão, nem num campo que mudou por fora depois
  da edição. Vale até fechar a tela.
- **Desfazer conclusão no detalhe da tarefa**: com a tarefa concluída (hoje, numa repetida;
  encerrada, numa única), a ação principal desfaz essa conclusão. O histórico de dias anteriores
  continua só leitura ali; um dia passado se corrige na Hoje, naquele dia.
- **Editor de rotina reorganizado**: Título; Quando (início, término ou duração, dias, a cada N
  semanas); Lembrar; Categoria; Lista; Exercícios (na categoria de exercício); Aparência; e, em
  "Mais opções", Vigência e Observações (aberto sozinho quando a rotina já tem uma delas).
  Pausar, Duplicar, Reverter alterações e Excluir ficam no menu.
- **Criar rotina**: nada é gravado nem agendado antes do botão "Criar rotina", e a tela continua
  fechando depois dele; voltar com campos preenchidos pede confirmação. Em altura compacta
  (paisagem com teclado) o botão sai do rodapé e vai para a barra do topo.

### Mudanças de comportamento

- **Editar grava só o campo alterado**, sobre a versão mais recente da tarefa ou da rotina:
  concluir pela notificação, pausar ou excluir a lista vinculada com o editor aberto não é mais
  desfeito pela edição.
- **Alarmes só são refeitos quando a mudança mexe em horário, repetição, dias, vigência ou
  aviso**; mudar título, texto, cor, ícone, categoria ou lista não toca no alarme.
- **"Concluídas hoje" da aba Tarefas** segue o registro de conclusões do dia, como a Hoje e o
  widget. A tarefa repetida concluída adiantada aparece em "Concluídas hoje" e também em
  "Próximas".
- **Desfazer de concluir e de pular rotina na Hoje** sobrevive a girar a tela e à troca de aba
  por até 30 s, como nas outras telas. É um Desfazer por vez: concluir vários itens em seguida
  deixa só o do último.
- **Desfazer direto descarta a mensagem**: desmarcar pela Hoje, tirar o pular pelo bloco
  esmaecido ou desfazer a conclusão pelo detalhe da tarefa tira da tela a mensagem pendente do
  mesmo item.
- **Concluir pelo detalhe da tarefa** mostra a mensagem com Desfazer mesmo se a tela fechar.
- **Excluir pelo editor de rotina** devolve, no Desfazer, a rotina como está gravada, com os
  exercícios como estão.
- **Voltar preditivo** nos editores de tarefa e de rotina já existentes.
- Ajustes de tela: o nome do dia na faixa da semana fica sempre numa linha (três letras ou uma);
  o horário do bloco da linha do tempo encolhe em três degraus (faixa inteira, só a duração,
  nada), sem reticências; o estado vazio preenche a tela por dentro da rolagem; o progresso do
  dia não mostra "0 de 0" em dia sem itens; o painel de nova tarefa só põe o foco no Título na
  primeira abertura e não fecha ao arrastar em paisagem com teclado.

### Nota

- Numa tarefa repetida, concluir adiantado (num dia que não é dia dela) só grava o registro
  daquele dia: a próxima ocorrência continua onde estava e aparece pendente no dia dela. Era
  assim também na v1.8.0. Se concluir adiantado deve valer pela próxima vez é decisão da
  v1.10.0.

### Problemas conhecidos

- **Aviso de rotina pode atrasar com o aparelho dormindo** quando o início anterior de outra
  rotina não avisa (aviso desligado ou já concluída no dia): o aviso seguinte sai só quando o
  aparelho acordar. Existe desde a v1.8.0; a correção é o primeiro item da próxima versão.
- **Título do editor de rotina cortado** em paisagem com fonte grande (2.0) e teclado aberto: o
  texto aparece pela metade, mas dá para digitar. Com fonte 1.0 não ocorre. Correção no trabalho
  adaptativo da v1.10.0.

### Interno

- **Abertura a frio**: **não medida** nesta versão; o aparelho de medição não estava disponível.
- **Baseline Profile** regerado para os editores, o detalhe da tarefa, Hoje, Tarefas, Listas,
  Calendário, Ajustes e as ofertas de Desfazer. Lacuna conhecida: as telas de rotinas (Todas as
  rotinas e o editor de rotina) não entram, como na v1.8.0.
- **APK de release**: 3.372.207 B (+0,54% sobre a v1.8.0).
- **Acessibilidade**: leitor de tela com fala real não verificado, coberto por testes de
  semântica. O bloco com o horário escondido continua expondo a faixa inteira ao leitor de tela.
- `TaskChange` e `RoutineChange` no domínio e `edit` nos repositórios; o `TaskDraft` e o
  `rebase` foram apagados; o `RoutineDraft` fica só para a criação. Fila única de gravações por
  editor e buffer de campo de texto (`ui/edit`). Criar lista e vincular numa transação só.

## v1.8.0 — Hoje e dados (versionCode 10800)

Decisão de arquitetura: `docs/architecture/ADR-0002-design-system-e-roteiro-de-ui.md` (seção 14).
Banco **10 → 11** com migração automática (só colunas opcionais no histórico de conclusões); o
backup continua no formato 6, com campos opcionais novos. Sem dependência nova e sem permissão
nova.

### Novidades

- **Tela Hoje** como tela inicial, no lugar da aba Rotinas (abas: Hoje, Tarefas, Calendário,
  Listas):
  - progresso do dia, próximo item e linha do tempo das rotinas;
  - tarefas em três seções, **Atrasadas**, **Hoje** e **Sem data** (recolhida), com os mesmos
    números do widget Resumo;
  - **Concluídas (n)**, recolhível no fim: tudo o que foi concluído no dia mostrado, que pode ser
    desmarcado dali;
  - a tela inteira rola, inclusive a faixa da semana, também em paisagem;
  - passada a meia-noite, a tela vai sozinha para o dia novo;
  - o botão "+" cria tarefa, e "Nova rotina" fica no menu.
- **Todas as rotinas**: tela nova com as rotinas ativas e pausadas, o filtro por categoria e as
  ações editar, pausar, retomar e excluir, com Desfazer. A rotina pausada não some mais.
- **Pular com Desfazer**: o bloco pulado continua visível no dia, esmaecido, com o controle de
  desfazer. Pular e remarcar ficam num menu ao segurar o bloco.
- **Conclusão retroativa**: concluir uma tarefa pela linha de um dia passado grava a conclusão
  naquele dia. Numa tarefa repetida, isso não conclui mais a ocorrência de hoje.
- **Desfazer definitivo da conclusão adiantada**: concluir antes do dia uma tarefa única e
  desfazer devolve a data e o lembrete mesmo depois de fechar o app ou horas depois.
- **Excluir pelo detalhe da tarefa com Desfazer**: a mensagem aparece na tela de onde se veio.
- **Linha do tempo com fonte grande**: o bloco cresce para caber o título inteiro e o horário,
  em vez de cortar; a duração dá só a altura mínima.

### Mudanças de comportamento

- **Excluir rotina** pelo editor deixa de pedir confirmação e passa a oferecer **Desfazer** por
  até 30 s; a ação se chama **Excluir** em todo lugar (por baixo continua o arquivamento: os dias
  passados ficam no histórico).
- Tocar **Concluir** numa notificação **de ontem** de uma tarefa repetida marca ontem como feito;
  o lembrete de hoje continua valendo.
- O **"+" do widget** e o atalho **Nova tarefa** abrem o painel onde a pessoa estiver, sem trocar
  para a aba Tarefas; em paisagem, **Salvar** fica sempre visível no topo do painel.
- Na **lista de tarefas**, o Desfazer de concluir ou excluir não some mais ao girar a tela e
  acompanha a troca de aba por até 30 s.
- Na lista de tarefas, a **busca e os filtros rolam junto** com a lista. A tarefa repetida que
  não acontece hoje mostra o ícone de repetição no lugar do círculo de concluir.
- **Toque duplo em Concluir** numa tarefa única grava uma conclusão só.
- **"Feitas hoje"** conta as tarefas com conclusão registrada hoje, igual na Hoje, no widget e no
  resumo diário.
- O alarme de uma tarefa concluída antes do horário não fica mais marcado no sistema para o mesmo
  dia; desfazer devolve o alarme.

### Limitação conhecida

- Conclusões adiantadas feitas antes da v1.8.0 continuam sendo desfeitas sem devolver a data e o
  lembrete originais; as novas devolvem.

### Interno

- **Abertura a frio**: **não medida**. A sessão no emulador foi inválida pelo protocolo: a
  dispersão passou do limite de 20% (p90 +31,3% na rodada da v1.7.1 e +22,3% na da v1.8.0), e a
  segunda rodada da v1.7.1 não rodou porque ela não abre sobre o banco 11. As medianas ficam só
  como registro (com perfil, tempo até o primeiro quadro): v1.7.1 1.031,1 ms; v1.8.0 987,8 e
  1.016,0 ms.
- **Acessibilidade**: leitor de tela com fala real não verificado; as telas novas são cobertas
  por testes de semântica, que passam a ser critério de aceite de toda tela nova.
- Painel de nova tarefa com um dono só no topo da navegação; criar lista e tarefa numa
  transação só.
- Baseline Profile regerado com as jornadas da tela Hoje.

## v1.7.1 — Acessibilidade e pendências do redesenho (versionCode 10701)

Decisão de arquitetura: `docs/architecture/ADR-0002-design-system-e-roteiro-de-ui.md` (seção 13).
Sem migração de banco (Room versão 10), sem mudança no backup (formato 6), sem dependência nova
e sem permissão nova.

### Mudanças de comportamento

- **Painel de nova tarefa guarda o rascunho**: fechar pelo voltar, tocando fora ou arrastando
  para baixo guarda o que foi digitado; o Cancelar descarta e o Salvar grava. O rascunho é um
  só para a aba Tarefas e o Calendário e dura enquanto o app estiver aberto (se o sistema
  encerrar o app em segundo plano, ele se perde).
- **"Desfazer" com duração longa** (cerca de 10 s) em todas as telas; uma ação nova substitui a
  mensagem anterior.

### Correções

- **Diagnóstico de alarmes**: a linha passa a ser "Próximo aviso do Kalendrys", calculada dos
  lembretes, rotinas e resumo do app. Antes lia o próximo despertador do sistema: sem a
  permissão de alarme exato dizia "Nenhum" e, com ela, podia mostrar o despertador do Relógio.
  Sem alarme exato, avisa que pode atrasar; ocorrências já concluídas no dia não aparecem.
- **Seletor de dias da semana**: cada dia tem 48dp de altura de toque, sem vão entre vizinhos,
  e as linhas se repartem por igual quando os sete não cabem.
- **Contador da lista** ("2/3") nunca é cortado; na linha da rotina quem cede espaço é o
  horário.
- **Título do painel de nova tarefa** em janela baixa (paisagem): rola junto com os campos, para
  o Salvar continuar alcançável com o teclado aberto.

### Interno

- **Abertura a frio**: medida em aparelho físico, v1.7.1 1,4% mais rápida que a v1.6.2 (fecha
  a pendência da v1.7.0).
- **Acessibilidade**: leitor de tela com fala real nao verificado, coberto por testes de
  semantica.
- O build-check imprime no log o resumo dos testes e do lint; `setup-java` v5 nos dois
  workflows.
- Baseline Profile regerado com o código final.

## v1.7.0 — Redesenho: fundação visual e fluxos rápidos (versionCode 10700)

Decisão de arquitetura: `docs/architecture/ADR-0002-design-system-e-roteiro-de-ui.md` (seção 12).
Sem migração de banco (Room versão 10) e sem permissão nova; o backup continua no formato 6.

### Novidades

- **Aparência** (Configurações): tema Sistema, Claro ou Escuro e Material You (cor do papel de
  parede) opcional. Padrão, inclusive para quem atualiza: **Sistema, com Material You
  desligado**. A escolha vai no backup; restaurar um backup antigo mantém a aparência atual.
- **Visual novo em todas as telas**: barra superior compacta e fixa, botão "+" que recolhe ao
  rolar, cantos e espaçamentos do tema, paisagem utilizável e largura máxima em telas largas.
- **Listas**:
  - adição rápida pelo campo no rodapé, vários itens seguidos sem fechar o teclado;
  - itens marcados vão para o grupo **Marcados**, que recolhe;
  - deslizar o item marca ou remove (com "Desfazer") e o toque edita; uma dica única, na
    primeira vez, explica o gesto;
  - cartões e categorias: o toque abre e o menu de três pontos tem as ações, com confirmação;
  - estados vazios com a ação de criar.
- **Atalhos do ícone** (toque longo no ícone do app): *Nova tarefa* e *Listas*.

### Mudanças de comportamento

- **Widgets seguem a Aparência do app.** Quem usa o aparelho em tema claro passa a ver os
  widgets claros (antes eram sempre escuros). Com Material You ligado, os widgets também usam
  a cor do papel de parede.
- **Datas abreviadas padronizadas** em todo o app e nos widgets (ex.: "Seg, 16 nov"), iguais em
  qualquer versão do Android.

### Correções

- **Teclado**: abrir o teclado não empurra mais a tela para cima; ela encolhe acima dele.
- **Leitor de tela**: o item da lista anuncia se está marcado, os cabeçalhos são lidos como
  cabeçalhos e toda ação de gesto (deslizar) tem uma ação equivalente.
- **Listas**: corrigido um defeito latente em que dois itens adicionados ao mesmo tempo podiam
  ficar com a mesma posição.

### Interno

- Testes de interface do Compose na JVM (`ui-test-junit4` e `ui-test-manifest`, pela BOM, só
  nos testes; nada vai para o APK de release).
- O build-check passa a rodar também os testes unitários e o lint em todo PR.
- Baseline Profile regerado com as jornadas ajustadas à interface nova.

## v1.6.2 — Toolchain atualizada (versionCode 10602)

Decisão de arquitetura: `docs/architecture/ADR-0002-design-system-e-roteiro-de-ui.md` (seção 11).
Sem mudança de tela, de banco (Room versão 10), de backup ou de permissões.

### Novidades

- **Voltar preditivo**: o gesto de voltar do Android mostra a prévia da tela anterior.

### Correções

- **Widgets no APK de release**: corrigidas as regras de proteção do R8, que no AGP 9 deixaram de
  manter o construtor sem argumentos de classes criadas por reflexão; sem isso os widgets ficavam
  "carregando" para sempre.

### Interno

- **Toolchain**: Gradle 9.6.1, Android Gradle Plugin 9.5.1, Kotlin 2.4.21, KSP 2.3.14,
  compileSdk 37 (targetSdk 35 e minSdk 26 inalterados), Compose BOM 2026.09.00, Room 2.8.5 e
  `benchmark`/`baselineprofile` 1.5.0.
- **Baseline Profile** regerado com a toolchain nova.
- **CI**: o build-check roda em todo PR para a `main` (build de release, `aapt` e
  conferência dos construtores mantidos pelas regras do R8).

## v1.6.1 — Lembretes que não se perdem (versionCode 10601)

Decisão de arquitetura: `docs/architecture/ADR-0003-entrega-de-lembretes-por-catch-up.md`.

### Correções

- **Entrega de lembretes por catch-up**: o alarme passa a ser só o despertar. Lembretes de
  tarefa, avisos de bloco de rotina e o resumo diário são entregues por uma rotina única, chamada
  pelo alarme, ao abrir o app, ao voltar para ele e depois do reinício do aparelho. Antes, sem a
  permissão de alarme exato, um alarme atrasado podia ser substituído pelo de amanhã e o aviso de
  hoje sumia.
- **Tolerância de 60 minutos**: o que atrasou até 60 minutos é entregue como lembrete normal
  (Concluir, Adiar, Abrir lista). Lembretes de tarefa com mais atraso viram um aviso único
  "N lembretes perdidos"; avisos de bloco e o resumo com mais atraso são descartados.
- **Reinício, Doze e app parado**: nenhum lembrete pulado nem duplicado; um alarme atrasado que
  chega depois da entrega não mostra nada de novo.
- **Bloco de rotina já terminado não é avisado**: um aviso de início atrasado não sai se o bloco
  (com duração) já acabou.
- **Resumo diário de ontem não sai depois da meia-noite**: o resumo mostra só o dia corrente.
- **Repetição personalizada** (a cada N dias/semanas, dias da semana) contada certo na recuperação
  de lembretes.
- **Widget Resumo do dia**: o "A seguir" não fica mais parado num item que já começou (F-1).

### Mudanças de comportamento

- Criar ou editar uma tarefa ou rotina com horário já passado no dia não gera lembrete atrasado
  nem perdido. Restaurar um backup não entrega lembretes do passado.

## v1.6.0 — Lista de compras na tarefa (versionCode 10600)

Decisões de arquitetura: `docs/architecture/ADR-0001-listas-de-compras-independentes.md` (v1.6.0)
e `docs/architecture/ADR-0002-design-system-e-roteiro-de-ui.md` (emendas E-1 e E-5).

### Novidades

- **Lista de compras na tarefa**: o detalhe da tarefa e o painel de nova tarefa (em Tarefas e no
  Calendário) anexam uma lista, com "Criar nova" ou "Usar existente".
- **Progresso (3/8)** na linha da tarefa, no bloco da timeline, no widget Hoje, no resumo diário
  e nas notificações.
- **Concluir uma tarefa repetida zera a lista**; o "Desfazer" devolve as marcações.
- **Oferta "Concluir tarefa"** ao marcar o último item da lista, inclusive para tarefa única com
  data futura.
- **Lembrete de tarefa com lista**: ação "Abrir lista", e o lembrete some ao concluir ou excluir
  a tarefa.
- **Aviso de bloco com lista**: Concluir, Abrir lista e Adiar (sem Pular). Bloco sem lista segue
  com Concluir, Pular e Adiar.
- **Link interno** `kalendrys://list/{id}` para abrir a lista.
- **Retorno tátil** leve ao marcar um item.

### Mudanças de comportamento

- Desfazer a conclusão de uma tarefa única concluída antes do dia devolve a data e o lembrete
  (melhor esforço: se o app foi encerrado no meio, desfaz sem restaurar).

## v1.5.1 — Polimento: idioma, fonte grande e lint (versionCode 10501)

### Correções

- **App só em pt-BR**, independente do idioma do aparelho: datas, dias da semana, meses,
  seletores de data e hora, widgets, notificações e textos do leitor de tela. O seletor de dias
  passa a mostrar Dom…Sáb e quebra a linha em fonte grande em vez de cortar.
- **Fonte grande**: as horas da timeline de Rotinas e da lista do dia do Calendário ficam numa
  linha; o intervalo ("Tempo livre") não quebra mais; o rótulo "Calendário" da barra de abas e
  os chips de categoria cabem numa linha.
- **Espaço inferior padronizado** sob o botão flutuante (+) em Tarefas, Rotinas, Calendário,
  Listas, tela da lista e Categorias: a última linha fica sempre acima dele.

### Interno

- Lint sem erros: o BOM do CSV de histórico passa a ser escrito por escape (o arquivo exportado
  continua idêntico); removidas 2 strings antigas de compras sem uso desde a v1.5.0.
- Testes intermitentes de ViewModel estabilizados (encerramento ordenado do banco e das
  corrotinas).

## v1.5.0 — Listas de compras independentes (versionCode 10500)

Decisão de arquitetura: `docs/architecture/ADR-0001-listas-de-compras-independentes.md`.

### Novidades

- **Aba Listas** (4ª aba): reúne as listas de compras, com progresso (ex.: 3/8), total e a
  etiqueta de quem usa cada uma; criar, renomear e excluir listas.
- **Lista de compras independente**: a lista deixa de pertencer a uma rotina do tipo Compras.
  Qualquer rotina pode anexar uma lista (nova ou existente) pelo cartão do editor, e uma lista
  pode ser compartilhada por mais de uma rotina. Lista sem rotina vira uma lista avulsa.
- **Tela da lista**: marcar, adicionar, editar e remover itens (com "Desfazer"), total e
  renomear; tudo é salvo na hora.
- **Reinício ao concluir o bloco de hoje**: concluir a rotina hoje (pela tela, pelo widget ou
  pela notificação) zera as marcações da lista anexada; o "Desfazer" as devolve. Marcar um dia
  passado não mexe na lista.
- **"Desmarcar todos" com "Desfazer"** na tela da lista.
- Diálogos de nome da lista abrem com foco e teclado.

### Dados e compatibilidade

- **Migração automática** (Room v9 → v10): as listas existentes, presas a rotinas do tipo
  Compras, viram listas independentes vinculadas à mesma rotina, com itens e marcações mantidos.
- **Backup v6**: inclui as listas e os vínculos. A v1.5.0 lê os backups antigos (v1 a v5),
  convertendo as listas antigas pela mesma regra da migração.

### Mudanças de comportamento

- O **"Descartar"** do editor de rotina não reverte mais as edições de itens da lista (os itens
  são salvos na hora na tela da lista); ele continua revertendo os campos da rotina e o vínculo
  com a lista.
- Um **backup v6 não abre na v1.4.1** ou anterior (é recusado como incompatível). Atualize o app
  antes de restaurar um backup feito na v1.5.0.
