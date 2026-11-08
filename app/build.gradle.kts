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

android {
    namespace = "com.kalendrys.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kalendrys.app"
        minSdk = 26
        targetSdk = 35
        // Convenção: a cada publicação na Play Store, incrementar versionCode em +1 (inteiro,
        // nunca reutilizado nem diminuído) e ajustar versionName para o nome visível (SemVer:
        // MAJOR.MINOR.PATCH). Ex.: próxima publicação → versionCode = 2, versionName = "1.0.1".
        versionCode = 1
        versionName = "1.0"
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

    debugImplementation(libs.androidx.compose.ui.tooling)
}
