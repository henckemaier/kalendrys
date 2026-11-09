import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.baselineprofile)
}

// Chave de upload da Play Store, lida de keystore.properties (fora do git) ou de variáveis de
// ambiente (CI). Veja keystore.properties.example para gerar a chave e preencher os campos.
// Ausente → o release continua assinado com a chave de debug (comportamento pessoal atual).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
fun uploadKey(prop: String, env: String): String? =
    keystoreProps.getProperty(prop) ?: System.getenv(env)

val uploadStoreFile = uploadKey("storeFile", "KALENDRYS_UPLOAD_STORE_FILE")
val uploadStorePassword = uploadKey("storePassword", "KALENDRYS_UPLOAD_STORE_PASSWORD")
val uploadKeyAlias = uploadKey("keyAlias", "KALENDRYS_UPLOAD_KEY_ALIAS")
val uploadKeyPassword = uploadKey("keyPassword", "KALENDRYS_UPLOAD_KEY_PASSWORD")
val hasUploadKeystore = uploadStoreFile != null && uploadStorePassword != null &&
    uploadKeyAlias != null && uploadKeyPassword != null

// Versionamento (regra em docs/VERSIONING.md). O versionName em SemVer MAJOR.MINOR.PATCH é a
// ÚNICA fonte: o versionCode é DERIVADO pela fórmula MAJOR*10000 + MINOR*100 + PATCH, com
// MINOR e PATCH em 0..99, garantindo um inteiro estritamente crescente sem contagem manual.
// Isto SUBSTITUI a convenção anterior (T3) de incrementar o versionCode manualmente em +1 —
// mesmo espírito (SemVer + código monotônico), mecânica agora automática. Para subir de versão,
// edite SOMENTE appVersionName; o release é publicado por push da tag "v$appVersionName"
// (ver .github/workflows/release.yml).
val appVersionName = "1.2.1"
val appVersionCode = appVersionName.split(".").let { parts ->
    require(parts.size == 3) { "versionName deve ser MAJOR.MINOR.PATCH, recebido: $appVersionName" }
    val (major, minor, patch) = parts.map(String::toInt)
    require(minor in 0..99 && patch in 0..99) {
        "MINOR e PATCH devem estar em 0..99 para o versionCode derivado: $appVersionName"
    }
    major * 10000 + minor * 100 + patch
}

android {
    namespace = "com.kalendrys.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kalendrys.app"
        minSdk = 26
        targetSdk = 35
        // versionName/versionCode derivados acima (ver appVersionName). Para subir de versão,
        // edite appVersionName; o versionCode é recalculado pela fórmula automaticamente.
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        // Chave de upload só é registrada quando keystore.properties / variáveis de ambiente
        // estão presentes; caso contrário o release cai na chave de debug (abaixo).
        if (hasUploadKeystore) {
            create("upload") {
                storeFile = file(uploadStoreFile!!)
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Com a chave de upload configurada (keystore.properties / env), assina para a Play
            // Store. Sem ela, mantém o comportamento pessoal: assina com a chave de debug, para
            // instalar por cima da versão já instalada sem perder os dados.
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true // versão exibida em Configurações > Sobre
    }

    // Os schemas versionados do Room entram como assets só da variante debug: o Robolectric lê
    // os assets mesclados do debug (android_merged_assets), então o MigrationTestHelper encontra
    // os JSONs de cada versão no `:app:testDebugUnitTest` — e o APK de release não os carrega.
    sourceSets {
        getByName("debug").assets.srcDirs(files("$projectDir/schemas"))
    }

    testOptions {
        unitTests {
            // Robolectric precisa dos recursos/assets mesclados (schemas, manifest) no classpath.
            isIncludeAndroidResources = true
        }
    }
}

// Relatórios de estabilidade/skipping do Compose, sob demanda:
// ./gradlew :app:compileReleaseKotlin -PcomposeReports=true  →  app/build/compose_compiler/
composeCompiler {
    stabilityConfigurationFile = layout.projectDirectory.file("compose_stability.conf")
    if (project.findProperty("composeReports") == "true") {
        reportsDestination = layout.buildDirectory.dir("compose_compiler")
        metricsDestination = layout.buildDirectory.dir("compose_compiler")
    }
}

// Histórico versionado do schema do Room, base para as próximas migrações.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.androidx.datastore.preferences)

    // Instala o Baseline Profile na primeira abertura (sem depender da Play Store).
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.junit) // AndroidJUnit4 para os testes de Room/Robolectric

    debugImplementation(libs.androidx.compose.ui.tooling)
}
