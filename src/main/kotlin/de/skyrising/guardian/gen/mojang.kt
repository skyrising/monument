package de.skyrising.guardian.gen

import com.google.gson.JsonObject
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture

val JARS_CLIENT_DIR: Path = JARS_DIR.resolve("client")
val JARS_SERVER_DIR: Path = JARS_DIR.resolve("server")
val JARS_MERGED_DIR: Path = JARS_DIR.resolve("merged")

val MOJANG_CACHE_DIR: Path = CACHE_DIR.resolve("mojang")
val LIBS_CACHE_DIR: Path = MOJANG_CACHE_DIR.resolve("libraries")

val mcGameVersionManifest: CompletableFuture<JsonObject> by lazy { requestJson<JsonObject>(URI("https://launchermeta.mojang.com/mc/game/version_manifest.json")) }
val mcVersions: CompletableFuture<Map<String, VersionInfo>> by lazy { getMojangVersions() }

private fun getMojangVersions(): CompletableFuture<Map<String, VersionInfo>> = mcGameVersionManifest.thenApply(::parseVersionManifest)

private fun cachedFile(url: URI): Path? {
    if (url.host != "launchermeta.mojang.com" || !url.path.startsWith("/v1/packages/")) return null
    val path = url.path.substring("/v1/packages/".length)
    val hash = path.substringBefore('/')
    return MOJANG_CACHE_DIR.resolve(hash.substring(0, 2)).resolve(hash.substring(2)).resolve(path.substringAfter('/'))
}

private fun getOrFetch(url: URI): CompletableFuture<Path> {
    if (url.scheme == "file") return CompletableFuture.completedFuture(Paths.get(url))
    val path = cachedFile(url) ?: throw IllegalArgumentException("$url is not cacheable")
    return download(url, path).thenApply { path }
}

fun downloadFile(version: VersionInfo, download: String, file: Path, listener: ((DownloadProgress) -> Unit)? = null): CompletableFuture<Boolean> = getMojangVersionManifest(version).thenCompose {
    downloadFile(it, download, file, listener)
}

fun downloadLibraries(version: VersionInfo): CompletableFuture<List<Path>> = getMojangVersionManifest(version).thenCompose(::downloadLibraries)

fun getMojangVersionManifest(version: VersionInfo): CompletableFuture<JsonObject> {
    return getOrFetch(version.url).thenApply { path ->
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            GSON.fromJson<JsonObject>(reader)
        }
    }
}