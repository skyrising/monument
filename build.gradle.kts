import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.jvm)
    alias(libs.plugins.shadow)
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get()))
    }
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.java.get()))
    }
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") { name = "FabricMC" }
}

dependencies {
    testImplementation(kotlin("test"))
    implementation(libs.gson)
    implementation(libs.stitch)
    implementation(libs.tomlj)
    implementation(libs.bundles.asm)
    implementation(libs.jopt.simple)
    implementation(libs.jimfs)
    implementation(libs.jline.terminal)

    compileOnly(libs.bundles.decompilers)
}

application {
    mainClass.set("de.skyrising.guardian.gen.MonumentKt")
}