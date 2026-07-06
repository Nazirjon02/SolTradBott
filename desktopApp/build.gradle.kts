import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)

    // Рантайм-зависимости commonMain не доходят транзитивно до JVM-консьюмера (доходят только
    // jvmMain), поэтому подключаем явно то, что десктоп реально использует в рантайме.
    implementation(libs.kotlinx.datetime)
    implementation(libs.sqldelight.runtime)

    // Провайдер SLF4J для Ktor (иначе выводится "No SLF4J providers were found").
    runtimeOnly(libs.logback.classic)
}

compose.desktop {
    application {
        mainClass = "tj.khujand.solana.trading.bot.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "tj.khujand.solana.trading.bot"
            packageVersion = "1.0.0"

            // jdeps не видит java.sql: JDBC-драйвер SQLite регистрируется через
            // ServiceLoader, поэтому jlink не включает модуль автоматически.
            modules("java.sql", "java.naming", "java.management")
        }
    }
}
