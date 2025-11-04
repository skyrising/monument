package de.skyrising.guardian.gen

import com.google.gson.JsonObject
import de.skyrising.guardian.gen.mappings.MappingTarget
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZonedDateTime
import java.util.*
import java.util.concurrent.CompletableFuture

interface DagNode<T : DagNode<T>> {
    val parents: List<T>
}

data class VersionInfo(val id: String, val type: String, val url: URI, val time: ZonedDateTime, val releaseTime: ZonedDateTime, var unobfuscated: Boolean, override val parents: MutableList<VersionInfo> = mutableListOf()) : Comparable<VersionInfo?>, DagNode<VersionInfo> {
    override fun compareTo(other: VersionInfo?): Int {
        if (other == null) return 1
        val releaseTimeCompare = releaseTime.compareTo(other.releaseTime)
        if (releaseTimeCompare != 0) return releaseTimeCompare
        val timeCompare = time.compareTo(other.time)
        if (timeCompare != 0) return timeCompare
        return id.compareTo(other.id)
    }

    override fun hashCode() = Objects.hash(id, type, releaseTime, time)
    override fun equals(other: Any?) = other is VersionInfo && id == other.id && type == other.type && releaseTime == other.releaseTime && time == other.time
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
        if (!obj.has("downloads") || !obj["downloads"]!!.asJsonObject.has("artifact")) continue
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
    Timer(version.id, "downloadJar.server.unpack").use {
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
}