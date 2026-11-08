# Publicando um Release do Kalendrys

Complementa `docs/VERSIONING.md` (a regra de versionamento) com o fluxo operacional de release
via GitHub Actions (`.github/workflows/release.yml`).

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
