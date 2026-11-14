# Changelog

Mudanças visíveis do Kalendrys por versão. Regra de versão em `docs/VERSIONING.md`; fluxo de
publicação em `docs/RELEASING.md`.

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
