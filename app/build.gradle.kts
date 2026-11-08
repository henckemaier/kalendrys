plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.kalendrys.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kalendrys.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // App pessoal, instalado fora da Play Store: assinado com a mesma chave do debug para
            // atualizar por cima da versão já instalada sem perder os dados. Para publicar na
            // Play Store, criar uma chave de upload própria e trocar esta linha.
            signingConfig = signingConfigs.getByName("debug")
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
