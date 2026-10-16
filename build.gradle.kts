import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.9.0"
}

// The release workflow passes the tag as -PappVersion=0.1.0; a suffix like -rc1 is dropped because installers need x.y.z.
val appVersion = ((findProperty("appVersion") as String?) ?: "0.1.0").removePrefix("v").substringBefore("-")
require(Regex("""\d+\.\d+\.\d+""").matches(appVersion)) { "appVersion must look like 1.2.3, got $appVersion" }

group = "dev.agentmeter"
version = appVersion

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    // Codex CLI archives older sessions as .jsonl.zst
    implementation("com.github.luben:zstd-jni:1.5.6-3")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "dev.agentmeter.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "AgentMeter"
            packageVersion = appVersion
            description = "Local token usage and cost dashboard for AI coding agents"
            vendor = "Agent Meter contributors"
            copyright = "MIT License"
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.sql")
            windows {
                iconFile.set(rootProject.file("packaging/icon.ico"))
                menuGroup = "Agent Meter"
                shortcut = true
                dirChooser = true
                upgradeUuid = "4ee33374-a5ea-49f8-add0-0816b3a20c21" // fixed, so a newer MSI upgrades the old one
            }
            macOS {
                iconFile.set(rootProject.file("packaging/icon.icns"))
                // macOS rejects an app version that starts with 0, so 0.y.z is packaged as 1.y.z (app and DMG metadata).
                packageVersion = appVersion.let { if (it.startsWith("0.")) "1." + it.removePrefix("0.") else it }
            }
            linux {
                iconFile.set(rootProject.file("packaging/icon.png"))
                shortcut = true
            }
        }
    }
}
