package de.skyrising.guardian.gen

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import de.skyrising.guardian.gen.mappings.MappingTarget
import java.io.PrintWriter
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZonedDateTime
import java.util.*
import java.util.concurrent.CompletableFuture

interface DagNode<T : DagNode<T>> {
    val parents: List<T>
}

data class VersionInfo(val id: String, val type: String, val url: URI, val time: ZonedDateTime, val releaseTime: ZonedDateTime, override val parents: MutableList<VersionInfo> = mutableListOf()) : Comparable<VersionInfo?>, DagNode<VersionInfo> {
    override fun compareTo(other: VersionInfo?): Int {
        if (other == null) return 1
        val releaseTimeCompare = releaseTime.compareTo(other.releaseTime)
        if (releaseTimeCompare != 0) return releaseTimeCompare
        val timeCompare = time.compareTo(other.time)
        if (timeCompare != 0) return timeCompare
        return id.compareTo(other.id)
    }
    override fun toString() = id
}

fun parseVersionManifest(obj: JsonObject): Map<String, VersionInfo> {
    val set = GSON.fromJson<TreeSet<VersionInfo>>(obj.require("versions"))
    val map = LinkedHashMap<String, VersionInfo>()
    for (version in set) map[version.id] = version
    return map
}

fun linkVersions(versions: Map<String, VersionInfo>): VersionInfo? {
    val sorted = TreeSet(versions.values)
    var previous: VersionInfo? = null
    for (version in sorted) {
        if (version.parents.isEmpty() && previous != null) {
            version.parents.add(previous)
        }
        previous = version
    }
    return previous
}

/**
 * Find all nodes on paths from [base] (or the beginning if null) to [head]
 *
 * If [filter] is specified all nodes not passing the filter will be ignored
 *
 * If no path exists an empty set is returned
 *
 * @return a topologically ordered set
 */
fun <T: DagNode<T>> findPredecessors(head: T, base: T?, filter: (T) -> Boolean = { true }): Set<T> {
    if (head == base) {
        if (!filter(head)) return emptySet()
        return setOf(head)
    }
    val result = linkedSetOf<T>()
    for (prev in head.parents) {
        val allPrev = findPredecessors(prev, base, filter)
        if (base == null || allPrev.contains(base)) {
            result.addAll(allPrev)
        }
    }
    if ((base == null || result.contains(base)) && filter(head)) result.add(head)
    return result
}

fun <T: DagNode<T>> createCommits(subGraph: Collection<T>, creator: (T, List<CommitTemplate>) -> CommitTemplate): List<CommitTemplate> {
    val map = mutableMapOf<T, CommitTemplate>()
    val commits = mutableListOf<CommitTemplate>()
    for (node in subGraph) {
        val parents = findParentsWithSkips(node, map)
        val commit = creator(node, parents)
        map[node] = commit
        commits.add(commit)
    }
    return commits
}

fun <T: DagNode<T>, S> findParentsWithSkips(node: T, map: Map<T, S>): List<S> {
    val parents = mutableListOf<S>()
    for (parent in node.parents) {
        val parentMapping = map[parent]
        if (parentMapping != null) {
            parents.add(parentMapping)
        } else {
            parents.addAll(findParentsWithSkips(parent, map))
        }
    }
    return parents
}

fun downloadFile(manifest: JsonObject, download: String, file: Path, listener: ((DownloadProgress) -> Unit)? = null): CompletableFuture<Boolean> {
    return startDownload(manifest["downloads"]?.asJsonObject, download, file, listener)
}

fun downloadLibraries(manifest: JsonObject): CompletableFuture<List<Path>> {
    val libs = manifest["libraries"]?.asJsonArray ?: return CompletableFuture.completedFuture(emptyList())
    val futures = mutableListOf<CompletableFuture<Path>>()
    for (lib in libs) {
        val obj = lib.asJsonObject
        val rules = obj["rules"]?.asJsonArray
        if (rules != null) {
            var enabled = true
            for (rule in rules) {
                val ruleObj = rule.asJsonObject
                val action = ruleObj["action"].asJsonPrimitive.asString == "allow"
                if (ruleObj.has("os")) {
                    if (ruleObj["os"]!!.asJsonObject["name"]!!.asString != "osx") continue
                }
                enabled = action
            }
            if (!enabled) continue
        }
        futures.add(downloadLibrary(obj["downloads"]!!.asJsonObject))
    }
    return CompletableFuture.allOf(*futures.toTypedArray()).thenApply {
        futures.map(CompletableFuture<Path>::get).toList()
    }
}

private fun downloadLibrary(obj: JsonObject): CompletableFuture<Path> {
    val artifact = obj["artifact"]!!.asJsonObject
    val path = LIBS_CACHE_DIR.resolve(artifact["path"]!!.asString)
    return download(URI(artifact["url"]!!.asString), path, null).thenApply { path }
}

private fun startDownload(downloads: JsonObject?, download: String, file: Path, listener: ((DownloadProgress) -> Unit)?): CompletableFuture<Boolean> {
    if (downloads == null) return CompletableFuture.completedFuture(false)
    val url = downloads[download]?.asJsonObject?.get("url")?.asString ?: return CompletableFuture.completedFuture(false)
    return download(URI(url), file, listener).thenApply { true }
}

private val inProgress = mutableMapOf<Path, CompletableFuture<Path>>()

fun getJar(version: VersionInfo, target: MappingTarget): CompletableFuture<Path> {
    val id = version.id
    if (target == MappingTarget.MERGED) {
        val merged = JARS_MERGED_DIR.resolve("$id.jar")
        val ip = inProgress[merged]
        if (ip != null) return ip
        if (Files.exists(merged)) return CompletableFuture.completedFuture(merged)
        val client = getJar(version, MappingTarget.CLIENT)
        val server = getJar(version, MappingTarget.SERVER)
        return deduplicate(inProgress, merged, CompletableFuture.allOf(client, server).thenCompose {
            mergeJars(id, client.get(), server.get(), merged)
        }.thenApply {
            merged
        })
    }
    val dir = if (target == MappingTarget.CLIENT) JARS_CLIENT_DIR else JARS_SERVER_DIR
    val path = dir.resolve("$id.jar")
    val ip = inProgress[path]
    if (ip != null) return ip
    if (Files.exists(path)) return CompletableFuture.completedFuture(getRealJar(version, path, target))
    return deduplicate(inProgress, path, downloadFile(version, target.id, path).thenApply { getRealJar(version, path, target) })
}

fun getRealJar(version: VersionInfo, jar: Path, target: MappingTarget): Path {
    if (target != MappingTarget.SERVER) return jar
    val realPath = jar.resolveSibling("server-${version.id}.jar")
    if (Files.exists(realPath)) return realPath
    val fs = getJarFileSystem(jar)
    val versionListFile = fs.getPath("META-INF/versions.list")
    if (!Files.exists(versionListFile)) {
        fs.close()
        return jar
    }
    val files = Files.readAllLines(versionListFile)
    for (file in files) {
        val parts = file.split('\t')
        if (parts[1] == version.id || files.size == 1) {
            val archivedPath = fs.getPath("META-INF/versions", parts[2])
            Files.copy(archivedPath, realPath)
            return realPath
        }
    }
    throw IllegalStateException("Could not find ${version.id} in the server.jar version.list")
}

private data class Dependency(val dependency: String, val type: String = "implementation")

fun generateGradleBuild(version: VersionInfo, dir: Path): CompletableFuture<Unit> = getMojangVersionManifest(version).thenApply {
    generateGradleBuild(it, dir)
}

fun generateGradleBuild(manifest: JsonObject, dir: Path) {
    PrintWriter(Files.newBufferedWriter(dir.resolve("build.gradle"), StandardCharsets.UTF_8)).use { out ->
        val libs = manifest["libraries"]!!.asJsonArray
        val byCondition = mutableMapOf<String, MutableSet<Dependency>>()
        byCondition[""] = mutableSetOf(
            Dependency("com.google.code.findbugs:jsr305:3.0.1", "compileOnly")
        )
        for (lib in libs) {
            // TODO: natives? Just getting the java parts is enough to fix syntax highlighting
            //  and the decompiled code probably won't run anyway
            val obj = lib.asJsonObject
            val rules = obj["rules"]?.asJsonArray
            byCondition.computeIfAbsent(rulesToString(rules)) { mutableSetOf() }.add(Dependency(obj["name"].asString))
        }
        out.println("apply plugin: 'java'")
        out.println("apply plugin: 'application'")
        out.println()
        val javaVersion = manifest["javaVersion"]?.asJsonObject?.get("majorVersion")?.asInt ?: 8
        val gradleJavaVersion = if (javaVersion <= 8) "JavaVersion.VERSION_1_$javaVersion" else "JavaVersion.VERSION_$javaVersion"
        out.println("sourceCompatibility = $gradleJavaVersion")
        out.println("targetCompatibility = $gradleJavaVersion")
        out.println()
        out.println("repositories {")
        out.println("    maven {")
        out.println("        name = 'Minecraft Libraries'")
        out.println("        url = 'https://libraries.minecraft.net/'")
        out.println("    }")
        out.println("    mavenCentral()")
        out.println("}")
        out.println()
        out.println("def os_name = null")
        out.println("def prop = System.properties['os.name'].toLowerCase()")
        out.println("if (prop.contains('win')) os_name = 'windows'")
        out.println("else if (prop.contains('mac')) os_name = 'osx'")
        out.println("else if (prop.contains('linux')) os_name = 'linux'")
        out.println()
        out.println("dependencies {")
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
                out.print("    ")
                out.print(indent)
                out.print(dep.type)
                out.print(" \"")
                out.print(dep.dependency)
                out.println("\"")
            }
            if (condition.isNotEmpty()) {
                out.println("    }")
            }
        }
        out.println("}")
        out.println()
        out.println("tasks.withType(JavaCompile) {")
        out.println("    options.encoding = \"UTF-8\"")
        if (javaVersion > 8) {
            out.print("    it.options.release = ")
            out.println(javaVersion)
        }
        out.println("}")
    }
    PrintWriter(Files.newBufferedWriter(dir.resolve("settings.gradle"), StandardCharsets.UTF_8)).use { out ->
        out.println("rootProject.name = 'minecraft'")
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