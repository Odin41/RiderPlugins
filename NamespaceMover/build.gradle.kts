plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

group = "com.github.namespacemover"
version = "1.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        rider("2025.2.1") {
            useInstaller = false
        }
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")
}

val javaVersion = JavaVersion.VERSION_21
java {
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks {
    test { useJUnitPlatform() }
    buildSearchableOptions { enabled = false }
    instrumentCode { enabled = false }

    // Disable patchPluginXml — it overwrites <name> tag with <n> during build.
    // plugin.xml is managed manually and must not be modified by Gradle.
    patchPluginXml { enabled = false }
}
