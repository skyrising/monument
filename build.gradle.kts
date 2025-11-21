import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.nio.file.Files
import kotlin.io.path.deleteIfExists

plugins {
    alias(libs.plugins.jvm)
    alias(libs.plugins.shadow)
    application
}

fun run(command: String) = Runtime.getRuntime().exec(command.split(" ").toTypedArray(), null, projectDir).let { p ->
    p.errorStream.bufferedReader().forEachLine(System.err::println)
    p.waitFor()
    buildString {
        p.inputStream.bufferedReader().forEachLine { line -> append(line).append('\n') }
    }.trim()
}

version = run("git describe --tags --dirty --long --match v*.*").split('-')
    .filter { !it.startsWith("g") }
    .joinToString(".")
    .replace(".dirty", "+dirty")
    .substring(1)

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

tasks.jar {
    archiveClassifier.set("slim")
    manifest {
        attributes["Implementation-Version"] = project.version
        attributes["Git-Commit"] = run("git rev-parse HEAD")
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    doLast {
        val jar = archiveFile.get().asFile.toPath()
        val link = jar.resolveSibling("monument-latest.jar")
        link.deleteIfExists()
        Files.createSymbolicLink(link, jar.fileName)
    }
}