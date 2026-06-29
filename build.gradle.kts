import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.22"
    application
    id("com.github.johnrengelman.shadow") version "7.0.0"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "19"
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of("19"))
    }
}


kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-receivers")
    }
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.yaml:snakeyaml:2.6")
    implementation("com.github.ajalt.clikt:clikt:5.0.1")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("com.github.jtoman.MainKt")
}
