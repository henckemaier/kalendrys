# Versionamento e Releases do Kalendrys

Regra **padronizada** de versionamento do app e de publicação de releases.
Fonte da verdade dos números: `app/build.gradle.kts` (`defaultConfig.versionName` e
`defaultConfig.versionCode`). Esta regra é a base do esquema de releases (workflow de
GitHub Actions e atualizador in-app).

## 1. `versionName` — SemVer `MAJOR.MINOR.PATCH`

Sempre três componentes numéricos (ex.: `1.2.0`, nunca `1.2`).

| Componente | Quando incrementar | Efeito |
|-----------|--------------------|--------|
| `MAJOR`   | Quebra de compatibilidade ou refatoração grande | zera `MINOR` e `PATCH` |
| `MINOR`   | Feature nova retrocompatível | zera `PATCH` |
| `PATCH`   | Correção de bug / ajuste pequeno | — |

Restrição para o `versionCode` derivado funcionar: `0 ≤ MINOR ≤ 99` e `0 ≤ PATCH ≤ 99`.
Ao chegar em `x.y.99` + 1 correção, sobe-se o `MINOR` (vira `x.(y+1).0`).
Simetricamente, ao chegar em `x.99.z` e surgir uma feature nova, sobe-se o `MAJOR` (o `MINOR`
não passa de 99): vira `(x+1).0.0` — a monotonicidade do `versionCode` é preservada.

## 2. `versionCode` — **derivado** do `versionName`

Fórmula única, sem contagem manual:

```
versionCode = MAJOR * 10000 + MINOR * 100 + PATCH
```

| versionName | cálculo            | versionCode |
|-------------|--------------------|-------------|
| 1.0.0       | 1*10000+0+0        | 10000       |
| 1.0.1       | 1*10000+0+1        | 10001       |
| 1.2.0       | 1*10000+2*100+0    | 10200       |
| 1.2.10      | 1*10000+2*100+10   | 10210       |
| 2.0.0       | 2*10000+0+0        | 20000       |

Como todo `versionName` novo é estritamente maior em SemVer, o `versionCode` resultante é
**estritamente crescente e monotônico** — atende à exigência da Play Store de "inteiro
nunca reutilizado nem diminuído", sem risco de erro humano. Recomenda-se **derivar o
`versionCode` automaticamente** no `build.gradle.kts` a partir do `versionName`, para que o
dev só edite uma linha (ver seção 4).

> **Compatibilidade com a convenção anterior (T3):** a convenção antiga comentada no
> `build.gradle.kts` pedia incremento manual de `versionCode` em +1 a cada publicação. Esta
> regra **substitui** esse mecanismo pela fórmula derivada (o espírito — SemVer + código
> monotônico — é o mesmo; muda só a mecânica, que deixa de ser manual).

## 3. Tags e Releases

- A tag de release é `v` + `versionName`. Ex.: `versionName = "1.2.0"` → tag `v1.2.0`.
- O workflow de release (GitHub Actions) dispara em tags que casam `v*`.
- O atualizador in-app compara a tag/`versionName` do **latest release** do repositório com
  a versão local instalada e oferece a atualização quando a remota é maior.

## 4. Como subir de versão

1. Decida o incremento SemVer (MAJOR / MINOR / PATCH) conforme a tabela da seção 1.
2. Edite **`versionName`** em `app/build.gradle.kts` para o novo valor `MAJOR.MINOR.PATCH`.
3. Ajuste o **`versionCode`** para `MAJOR*10000 + MINOR*100 + PATCH`
   (ou, se o `build.gradle.kts` já derivar automaticamente, nada a fazer aqui).
4. Commit numa branch (nunca direto na `main`); PR; merge pelo gate do dono do repo.

Implementação recomendada no `build.gradle.kts` (deriva o `versionCode` do `versionName`,
elimina o passo 3) — **mudança em arquivo quente, aplicar só com aprovação do Tech Lead**:

```kotlin
val appVersionName = "1.0.0"
val appVersionCode = appVersionName.split(".").let { (maj, min, pat) ->
    maj.toInt() * 10000 + min.toInt() * 100 + pat.toInt()
}
// ...
defaultConfig {
    versionName = appVersionName
    versionCode = appVersionCode
}
```

## 5. Como publicar um release

```sh
# 1. a versão (versionName/versionCode) já está mergeada na main
git checkout main && git pull --ff-only
# 2. cria e publica a tag — isso dispara o workflow de release
git tag v1.2.0
git push origin v1.2.0
```

O push da tag `v*` aciona o workflow de Actions que builda e publica o release; o atualizador
in-app passa a enxergar a nova versão pelo latest release.

## 6. Estado atual e baseline

No momento desta regra o `build.gradle.kts` está em `versionName = "1.0"` / `versionCode = 1`
(ainda **não publicado** na Play Store). O alinhamento à regra (baseline
`versionName = "1.0.0"`, `versionCode = 10000`) é uma mudança no `build.gradle.kts` (arquivo
quente) a ser feita em branch isolada, com aviso prévio ao Tech Lead — não aplicada por este
documento.
