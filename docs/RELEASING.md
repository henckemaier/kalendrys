# Publicando um Release do Kalendrys

Complementa `docs/VERSIONING.md` (a regra de versionamento) com o fluxo operacional de release
via GitHub Actions (`.github/workflows/release.yml`).

O **build-check** (`.github/workflows/build-check.yml`) roda em todo PR para a `main` (testes unitários, lint, `assembleRelease`, `aapt` e a conferência do R8) e precisa estar verde antes do merge e da tag; os dois workflows mantêm o mesmo preparo, JDK e actions (mesmos SHAs).

## Passo a passo

1. **Bump da versão** — edite **somente** `appVersionName` em `app/build.gradle.kts`
   (SemVer `MAJOR.MINOR.PATCH`). O `versionCode` é derivado automaticamente pela fórmula
   `MAJOR*10000 + MINOR*100 + PATCH` — não edite o `versionCode` à mão.
2. **Commit + PR + merge na `main`** (nunca direto na `main`; merge pelo gate do dono do repo).
3. **Tag e push**:
   ```sh
   git checkout main && git pull --ff-only
   git tag v<versionName>        # ex.: git tag v1.2.0
   git push origin v<versionName>
   ```
   O push da tag `v*` dispara o workflow de release.

## O que o workflow faz

1. **Valida** que a tag é exatamente `v` + `appVersionName` do `build.gradle.kts`; e recalcula
   o `versionCode` esperado pela fórmula. Se a tag não casar, **falha e não publica**.
2. Builda `:app:assembleRelease` **assinado** com o `signingConfig "upload"` (o da T3),
   lendo a keystore e as senhas dos **GitHub Secrets** (decodificados só em runtime do CI).
3. Confere, via `aapt`, que o APK gerado tem o `versionName`/`versionCode` esperados.
4. Cria o **GitHub Release** da tag e **anexa o APK assinado** (com release notes geradas).

## Subindo o Gradle (wrapper)

O CI builda com o wrapper (`./gradlew`), que confere o `distributionSha256Sum` de
`gradle/wrapper/gradle-wrapper.properties` antes de usar a distribuição. Ao subir o Gradle:

1. Regere o wrapper **duas vezes** (a segunda já roda na versão nova e atualiza jar e scripts):
   `./gradlew wrapper --gradle-version <X.Y.Z> --distribution-type bin`.
2. Atualize `distributionSha256Sum` com o SHA-256 oficial de
   `https://services.gradle.org/distributions/gradle-<X.Y.Z>-bin.zip.sha256`.
3. Confira o `gradle/wrapper/gradle-wrapper.jar` contra
   `https://services.gradle.org/distributions/gradle-<X.Y.Z>-wrapper.jar.sha256`
   (ou https://gradle.org/release-checksums/). Se não bater, não commite.

## Construtores mantidos pelas regras keep (R8)

Desde o AGP 9 o R8 aplica o modo estrito das regras keep: `-keep class X` sem membros mantém só
a classe, não o construtor sem argumentos. Classe instanciada por reflexão sem ele quebra só no
release (v1.6.2: `InstantiationException` no `OverwritingInputMerger` do WorkManager e widgets
carregando para sempre). Depois de `./gradlew :app:assembleRelease`, rode:

```
bash scripts/check-r8-keep-constructors.sh
```

Ele cruza `usage.txt` (o que o R8 removeu) com `seeds.txt` (o que as regras mantêm) em
`app/build/outputs/mapping/release` e falha citando a classe. Correção: acrescentar
`{ public <init>(); }` à regra em `app/proguard-rules.pro`; ou, se a classe nunca é instanciada por
reflexão, registrá-la com o motivo em `app/r8-keep-ctor-exceptions.txt`. O build-check roda o
mesmo comando em todo PR. Não use `android.r8.strictFullModeForKeepRules=false` (vetado).

## Regerando o Baseline Profile

Com um emulador ou aparelho Android 13+ conectado: `./gradlew --no-daemon
:app:generateReleaseBaselineProfile -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`
(sem essa propriedade o teste conectado desinstala o app no fim e apaga os dados e widgets).

1. Gere com o app **sem dados** (num aparelho compartilhado: copie os dados com `run-as`, esvazie,
   gere e restaure). As jornadas criam itens "Perfil:" e, com muitas rotinas, não acham o bloco
   fora da tela.
2. **Passo manual** com o plugin `baselineprofile` 1.5.0 e o AGP 9.5.1: a geração apaga os perfis
   versionados em `app/src/release/generated/baselineProfiles/` e não traz o novo do aparelho.
   Copie-o de `additional_test_output`:
   `adb pull /sdcard/Android/media/com.kalendrys.baselineprofile/additional_test_output/BaselineProfileGenerator_generate-startup-prof.txt`
   e grave o arquivo como `baseline-prof.txt` e como `startup-prof.txt` (idênticos).
3. Confira: arquivos não vazios, com classes `com/kalendrys/app`, e `assets/dexopt/baseline.prof`
   dentro do APK de release.

## Medindo a abertura a frio

O critério e o protocolo estão no ADR-0002 (11.8.3; resultado da v1.7.1 na 13.9): `StartupBenchmark`
antes e depois, por pares em sequência no mesmo aparelho, de preferência na ordem antes, depois,
depois, antes (A-D-D-A); compara-se a mediana **com perfil**; p90 até 20% acima da mediana em cada
medição; os dois "antes" diferindo até 10%. O método que funcionou (v1.7.1): compilar antes os APKs
de benchmark dos dois commits (`:app:assembleBenchmarkRelease :baselineprofile:assembleBenchmarkRelease`,
o "antes" num worktree temporário de caminho curto) e medir só com `adb install -r` e
`am instrument -w -e class com.kalendrys.baselineprofile.StartupBenchmark ...`.

- **Aparelho do usuário:** todo comando com o serial (`adb -s <serial>`, `ANDROID_SERIAL=<serial>`
  no Gradle). Nada instala, desinstala ou limpa dados no aparelho do usuário sem o **backup
  exportado e confirmado** por ele. A variante de medição tem outra assinatura: medir obriga a
  desinstalar o app dele.
- **Aparelho físico:** o limite de 4 GB de memória livre do host **não vale** (a carga é do aparelho;
  só se anota). Valem, iguais no início e no fim de cada medição: bateria e temperatura na mesma
  faixa, sem economia de bateria, tela desbloqueada e acesa, nada instalando ou atualizando no
  aparelho, e **nenhum Gradle no host** durante as medidas.
- **Aquecimento:** faça **uma rodada descartada** antes da primeira medição. Na v1.7.1 a A1 começou
  uns 4 °C mais fria que as outras e foi a mais lenta.
- **Tempo:** no celular cada abertura leva uns 45 s (rastro do perfetto por iteração). Uma medição
  com as duas variantes (com perfil e sem compilação, 10 aberturas cada) leva uns 16 min, e as 4
  passam de 1 h; só com perfil, uns 8 min cada. Combine o tempo com o dono do aparelho antes.
- Neste aparelho o Macrobenchmark grava o `benchmarkData.json` em
  `/sdcard/Android/media/com.kalendrys.baselineprofile/` (não em `additional_test_output`).

## Secrets necessários

Configurar em **Settings → Secrets and variables → Actions**:

| Secret | Conteúdo |
|--------|----------|
| `KALENDRYS_UPLOAD_KEYSTORE_BASE64` | a keystore de upload (`.jks`) em **base64** |
| `KALENDRYS_UPLOAD_STORE_PASSWORD`  | senha do store |
| `KALENDRYS_UPLOAD_KEY_ALIAS`       | alias da chave |
| `KALENDRYS_UPLOAD_KEY_PASSWORD`    | senha da chave |

Gerar o base64 da keystore:
```sh
base64 -w0 upload.jks           # Linux
base64 -i upload.jks            # macOS
```

> A keystore e as senhas **nunca** são commitadas (ver `.gitignore` + `keystore.properties.example`).
> Sem os secrets (ex.: build local), o `signingConfig "upload"` cai no fallback da chave de
> debug — o build continua funcionando, apenas não fica assinado para a Play Store.
