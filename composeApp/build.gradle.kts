import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    kotlin("plugin.serialization") version "1.9.22" // ← ДОБАВЬ ЭТУ СТРОКУ

}

kotlin {
   // jvmToolchain(17) // Gradle сам найдет или скачает нужный JDK с jpackage
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    // Добавьте это:
    jvm()
    
    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.eddsa)
            implementation(libs.kotlin.bip39.jvm)

        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.datetime)

            implementation(libs.multiplatform.settings.no.arg)

            implementation(compose.materialIconsExtended)
//            implementation(libs.compose.material.icons.extended)

            // ===== КЛЮЧЕВЫЕ ЗАВИСИМОСТИ KTOR =====
            // Общие зависимости (работают на всех платформах)
            implementation(libs.ktor.client.core)          // Ядро Ktor
            implementation(libs.ktor.client.content.negotiation) // Для JSON
            implementation(libs.ktor.serialization.kotlinx.json) // Сериализация
            implementation(libs.ktor.client.logging)       // Логирование

            // Сериализация Kotlinx
            implementation(libs.kotlinx.serialization.json)

            // Корутины (скорее всего уже есть, но для уверенности)
            implementation(libs.kotlinx.coroutines.core)
        }

        iosMain.dependencies {
            // Ktor для iOS (использует Darwin/NSURLSession)
            implementation(libs.ktor.client.darwin)
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.ktor.client.okhttp) // для desktop
            implementation(libs.logback.classic) // Добавьте эту строку
            implementation(libs.eddsa)
            implementation(libs.kotlin.bip39.jvm)

        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "tj.khujand.solana.trading.bot"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "tj.khujand.solana.trading.bot"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
}
compose.desktop {
    application {
        mainClass = "tj.khujand.solana.trading.bot.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "tj.khujand.solana.trading.bot"
            packageVersion = "1.0.0"
        }
    }
}

tasks.register<JavaExec>("runTelegramBot") {
    group = "application"
    description = "Run Telegram bot JVM entrypoint"
    dependsOn("jvmMainClasses")

    val runTask = tasks.named<JavaExec>("run")
    classpath = runTask.get().classpath
    mainClass.set("tj.khujand.solana.trading.bot.TelegramBotMainKt")

    doFirst {
        val token = System.getenv("TELEGRAM_BOT_TOKEN")
        if (token.isNullOrBlank()) {
            throw GradleException("TELEGRAM_BOT_TOKEN is required")
        }
    }
}

