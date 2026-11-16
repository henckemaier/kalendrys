plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    // Também fixa o kotlin-gradle-plugin na versão do catálogo: o AGP 9 (Kotlin embutido) usa o KGP
    // do classpath e, sozinho, pediria o 2.2.10. Sem o plugin kotlin-android, quem puxa o 2.4.21 é
    // este; se ele sair, confira `./gradlew buildEnvironment`.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
