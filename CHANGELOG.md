# Changelog

Mudanças visíveis do Kalendrys por versão. Regra de versão em `docs/VERSIONING.md`; fluxo de
publicação em `docs/RELEASING.md`.

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
