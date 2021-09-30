package de.skyrising.guardian.gen

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

const val MC_VERSIONS_GITHUB = "ssh://git@github.com/skyrising/mc-versions.git"
val MC_VERSIONS_CACHE_DIR: Path = CACHE_DIR.resolve("mc-versions")
val MC_VERSIONS_DATA_DIR: Path = MC_VERSIONS_CACHE_DIR.resolve("data")

fun getMcVersions(): CompletableFuture<Map<String, VersionInfo>> =
    if (Files.notExists(MC_VERSIONS_CACHE_DIR)) {
        git(CACHE_DIR, "clone", MC_VERSIONS_GITHUB, CACHE_DIR.relativize(MC_VERSIONS_CACHE_DIR).toString())
    } else {
        git(MC_VERSIONS_CACHE_DIR, "pull")
    }.thenApply {
        parseMcVersions()
    }

private fun parseMcVersions(): Map<String, VersionInfo> {
    val map = parseVersionManifest(GSON.fromJson(Files.newBufferedReader(MC_VERSIONS_DATA_DIR.resolve("version_manifest.json")))).mapValues {
        val v = it.value
        VersionInfo(v.id, v.type, MC_VERSIONS_DATA_DIR.resolve(v.url.toString()).toUri(), v.time, v.releaseTime)
    }
    for (version in map.values) {
        val versionDetails = GSON.fromJson<JsonObject>(Files.newBufferedReader(MC_VERSIONS_DATA_DIR.resolve("version").resolve(version.id + ".json")))
        for (prevId in versionDetails["previous"]?.asJsonArray ?: JsonArray()) {
            val prev = map[prevId.asString]
            if (prev != null) {
                version.parents.add(prev)
            } else {
                System.err.println("Unknown version ${prevId.asString} as predecessor for $version")
            }
        }
    }
    return map
}