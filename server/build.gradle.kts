plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

application {
    // Точка входа — fun main() в server/src/main/kotlin/.../Application.kt
    mainClass.set("tj.khujand.solana.trading.bot.server.ApplicationKt")
}

// Модуль shared содержит Compose-UI (App.kt) и тянет Compose-зависимости в рантайм-classpath
// сервера. Один и тот же jar может попасть в дистрибутив дважды — берём первый экземпляр,
// иначе distTar/distZip/installDist падают на дубликате.
tasks.withType<AbstractCopyTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

dependencies {
    implementation(projects.shared)

    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.contentNegotiation)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.serialization.kotlinxJson)

    implementation(libs.sqldelight.sqliteDriver)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.logback.classic)
}

kotlin {
    jvmToolchain(11)
}
