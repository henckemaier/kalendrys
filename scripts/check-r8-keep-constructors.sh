#!/usr/bin/env bash
# Confere, no build de release, que nenhuma classe mantida por uma regra keep perdeu o construtor
# sem argumentos no R8. Desde o AGP 9 o R8 aplica o modo estrito das regras keep: "-keep class X"
# sem membros mantém só a classe, e o construtor padrão some. Classes instanciadas por reflexão
# (InputMerger do WorkManager, ActionCallback do Glance) quebram em tempo de execução. Foi o
# bloqueante da v1.6.2: widgets carregando para sempre.
#
# Cruza os arquivos que o R8 já gera em app/build/outputs/mapping/release:
#   usage.txt  -> o que foi removido (procura "<init>()" não privado sob a classe);
#   seeds.txt  -> as classes mantidas por alguma regra keep (linhas sem ":").
# Exceções conhecidas, com o motivo de cada uma: app/r8-keep-ctor-exceptions.txt.
#
# Uso (depois de ./gradlew :app:assembleRelease):
#   bash scripts/check-r8-keep-constructors.sh [pasta-do-mapping] [arquivo-de-excecoes]
set -euo pipefail

MAP="${1:-app/build/outputs/mapping/release}"
EXC="${2:-app/r8-keep-ctor-exceptions.txt}"

for f in "$MAP/usage.txt" "$MAP/seeds.txt" "$EXC"; do
  if [ ! -f "$f" ]; then
    echo "::error::$f não encontrado. Rode ./gradlew :app:assembleRelease antes (o R8 gera usage.txt e seeds.txt)."
    exit 1
  fi
done

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# Classes cujo construtor sem argumentos (público, protegido ou de pacote) foi removido.
tr -d '\r' < "$MAP/usage.txt" \
  | awk '/^[^ ]/ { cls = $0; sub(/:$/, "", cls) }
         /^    (public |protected )?void <init>\(\)$/ { print cls }' \
  | sort -u > "$tmp/removidos"
# Classes mantidas por regra keep (a linha da classe, sem membro).
tr -d '\r' < "$MAP/seeds.txt" | grep -v ':' | sort -u > "$tmp/mantidas" || true
# Exceções: um nome de classe por linha; "#" inicia o motivo.
tr -d '\r' < "$EXC" | sed -e 's/#.*//' -e 's/[[:space:]]*$//' -e '/^$/d' | sort -u > "$tmp/excecoes"

comm -12 "$tmp/removidos" "$tmp/mantidas" > "$tmp/achadas"
comm -23 "$tmp/achadas" "$tmp/excecoes" > "$tmp/novas"

if [ -s "$tmp/novas" ]; then
  while read -r cls; do
    echo "::error::$cls é mantida por uma regra keep, mas o R8 removeu o construtor sem argumentos."
  done < "$tmp/novas"
  echo "O que fazer: se a classe é instanciada por reflexão (pelo nome), acrescente o construtor à"
  echo "regra em app/proguard-rules.pro, por exemplo: -keep class * extends <Base> { public <init>(); }"
  echo "Se não é (o construtor nunca é chamado por reflexão), registre-a em $EXC com o motivo."
  exit 1
fi

echo "OK: nenhuma classe mantida por regra keep perdeu o construtor sem argumentos."
if [ -s "$tmp/achadas" ]; then
  echo "Exceções conhecidas presentes ($EXC):"
  sed 's/^/  /' "$tmp/achadas"
fi
