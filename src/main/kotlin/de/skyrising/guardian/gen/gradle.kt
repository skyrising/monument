package de.skyrising.guardian.gen

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture


fun extractGradleAndExtraSources(version: VersionInfo, out: Path): CompletableFuture<Unit> =
    supplyAsync(TaskType.EXTRACT_RESOURCE) {
        useResourceFileSystem {
            copyCached(it.resolve("gradle_env"), out, RESOURCE_CACHE_DIR)
            copyCached(it.resolve("extra_src"), out.resolve("src/main/java"), RESOURCE_CACHE_DIR)
        }
    }.thenCompose {
        generateGradleBuild(version, out)
    }

private data class Dependency(val dependency: String, val type: String = "implementation") {
    override fun toString() = "$type(\"$dependency\")"
}

fun generateGradleBuild(version: VersionInfo, dir: Path): CompletableFuture<Unit> = getMojangVersionManifest(version).thenApply {
    generateGradleBuild(it, dir)
}

fun generateGradleBuild(manifest: JsonObject, dir: Path) {
    PrintWriter(Files.newBufferedWriter(dir.resolve("build.gradle.kts"), StandardCharsets.UTF_8)).use { out ->
        val libs = manifest["libraries"]!!.asJsonArray
        val byCondition = mutableMapOf<String, MutableSet<Dependency>>()
        if (manifest["releaseTime"]!!.asString < "2025-10-28") {
            byCondition[""] = mutableSetOf(
                Dependency("com.google.code.findbugs:jsr305:3.0.1", "compileOnly")
            )
        }
        for (lib in libs) {
            // TODO: natives? Just getting the java parts is enough to fix syntax highlighting
            //  and the decompiled code probably won't run anyway
            val obj = lib.asJsonObject
            val rules = obj["rules"]?.asJsonArray
            byCondition.computeIfAbsent(rulesToString(rules)) { mutableSetOf() }.add(Dependency(obj["name"].asString))
        }
        val javaVersion = manifest["javaVersion"]?.asJsonObject?.get("majorVersion")?.asInt ?: 8
        val gradleJavaVersion = if (javaVersion <= 8) "JavaVersion.VERSION_1_$javaVersion" else "JavaVersion.VERSION_$javaVersion"
        out.println("""
            plugins {
                java
                application
            }

            java {
                sourceCompatibility = $gradleJavaVersion
                targetCompatibility = $gradleJavaVersion
            }
            
            tasks.withType<JavaCompile> {
                options.encoding = "UTF-8"
                options.release = $javaVersion
            }

            repositories {
                maven("https://libraries.minecraft.net/") {
                    name = "Minecraft Libraries"
                }
                mavenCentral()
            }
            
            val os_name = System.getProperty("os.name").lowercase().let {
                when {
                    it.contains("win") -> "windows"
                    it.contains("linux") -> "linux"
                    it.contains("mac") -> "osx"
                    else -> null
                }
            }
            
            dependencies {
        """.trimIndent())
        var first = true
        for ((condition, set) in byCondition.entries) {
            if (first) first = false
            else out.println()
            var indent = ""
            if (condition.isNotEmpty()) {
                out.print("    if (")
                out.print(condition)
                out.println(") {")
                indent = "    "
            }
            for (dep in set) {
                out.println("    $indent$dep")
            }
            if (condition.isNotEmpty()) {
                out.println("    }")
            }
        }
        out.println("}")
    }
    PrintWriter(Files.newBufferedWriter(dir.resolve("settings.gradle.kts"), StandardCharsets.UTF_8)).use { out ->
        out.println("""
            rootProject.name = "minecraft"
            """.trimIndent())
    }
}

private fun rulesToString(rules: JsonArray?): String {
    if (rules == null) return ""
    val allows = mutableListOf<String>()
    val disallows = mutableListOf<String>()
    for (rule in rules) {
        val obj = rule.asJsonObject
        val allow = obj["action"].asString == "allow"
        val dest = if (allow) allows else disallows
        if (obj.has("os")) {
            dest.add("os_name ${if (allow) "==" else "!="} \"${obj["os"].asJsonObject["name"].asString}\"")
        }
    }
    val allowStr = allows.joinToString(" || ")
    val disallowStr = disallows.joinToString(" && ")
    val sb = StringBuilder()
    if (allows.size > 1 && disallows.isNotEmpty()) sb.append('(').append(allowStr).append(')')
    else sb.append(allowStr)
    if (allows.isNotEmpty() && disallows.isNotEmpty()) sb.append(" && ")
    sb.append(disallowStr)
    return sb.toString()
}