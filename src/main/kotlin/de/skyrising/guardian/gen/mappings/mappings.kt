package de.skyrising.guardian.gen.mappings

import com.google.gson.JsonArray
import de.skyrising.guardian.gen.*
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

val JARS_MAPPED_DIR: Path = JARS_DIR.resolve("mapped")

interface MappingProvider {
    val name: String
    val format: MappingsParser
    fun getMappings(version: VersionInfo, mappings: String?, target: MappingTarget, cache: Path = CACHE_DIR.resolve("mappings")): CompletableFuture<MappingTree?>
    fun supportsVersion(version: VersionInfo, target: MappingTarget, cache: Path = CACHE_DIR.resolve("mappings")): CompletableFuture<Boolean> {
        return getLatestMappings(version, target, cache).thenApply { it != null }
    }
    fun getPath(cache: Path, version: VersionInfo, mappings: String?): Path = cache.resolve(name).resolve(version.id)
    fun getLatestMappingVersion(version: VersionInfo, target: MappingTarget, cache: Path = CACHE_DIR.resolve("mappings")): CompletableFuture<String?>
    fun getLatestMappings(version: VersionInfo, target: MappingTarget, cache: Path = CACHE_DIR.resolve("mappings")): CompletableFuture<MappingTree?> {
        return getLatestMappingVersion(version, target, cache).thenCompose { mv ->
            getMappings(version, mv, target, cache)
        }
    }
    companion object {
        val MOJANG = object : CommonMappingProvider("mojang", ProguardMappings, "txt", "official") {
            override fun getUrl(cache: Path, version: VersionInfo, mappings: String?, target: MappingTarget): CompletableFuture<URI?> =
                if (target == MappingTarget.MERGED) CompletableFuture.completedFuture(null)
                else getVersionManifest(version).thenApply { manifest ->
                    manifest["downloads"]?.asJsonObject?.get(target.id + "_mappings")?.asJsonObject?.get("url")?.asString?.let { URI(it) }
                }
        }
        val FABRIC_INTERMEDIARY = IntermediaryMappingProvider("fabric", URI("https://meta.fabricmc.net/v2/"), URI("https://maven.fabricmc.net/"))
        val LEGACY_INTERMEDIARY = IntermediaryMappingProvider("legacy", URI("https://meta.legacyfabric.net/v2/"), URI("https://maven.legacyfabric.net/"))
        val QUILT_INTERMEDIARY = IntermediaryMappingProvider("quilt", URI("https://meta.quiltmc.org/v3/"), URI("https://maven.quiltmc.org/repository/release/"))
        val YARN = object : JarMappingProvider("yarn", GenericTinyReader) {
            override fun getUrl(cache: Path, version: VersionInfo, mappings: String?, target: MappingTarget): CompletableFuture<URI?> {
                TODO("Not yet implemented")
            }
        }
    }
}

abstract class CommonMappingProvider(override val name: String, override val format: MappingsParser, private val ext: String, private val invert: String? = null) : MappingProvider {
    abstract fun getUrl(cache: Path, version: VersionInfo, mappings: String?, target: MappingTarget): CompletableFuture<URI?>
    override fun supportsVersion(version: VersionInfo, target: MappingTarget, cache: Path): CompletableFuture<Boolean> = getLatestMappingVersion(version, target, cache).thenCompose { mappings ->
        getUrl(getPath(cache, version, mappings), version, mappings, target).thenApply { it != null }
    }
    override fun getLatestMappingVersion(version: VersionInfo, target: MappingTarget, cache: Path): CompletableFuture<String?> = CompletableFuture.completedFuture(null)
    override fun getMappings(version: VersionInfo, mappings: String?, target: MappingTarget, cache: Path): CompletableFuture<MappingTree?> {
        val mappingsFile = getPath(cache, version, mappings).resolve("mappings-${target.id}.${ext}")
        return getUrl(getPath(cache, version, mappings), version, mappings, target).thenCompose { url ->
            if (url == null) CompletableFuture.completedFuture(Unit) else download(url, mappingsFile)
        }.thenApplyAsync {
            if (!Files.exists(mappingsFile)) return@thenApplyAsync null
            val tree = Files.newBufferedReader(mappingsFile).use(format::parse)
            if (invert != null) tree.invert(invert) else tree
        }
    }

    override fun toString() = "${javaClass.simpleName}($name)"
}

abstract class JarMappingProvider(override val name: String, override val format: MappingsParser) : CommonMappingProvider(name, format, "jar") {
    open fun getFile(version: VersionInfo, mappings: String?, target: MappingTarget, jar: FileSystem): Path = jar.getPath("mappings/mappings.tiny")
    override fun getMappings(version: VersionInfo, mappings: String?, target: MappingTarget, cache: Path): CompletableFuture<MappingTree?> {
        val jarFile = getPath(cache, version, mappings).resolve("mappings-${target.id}.jar")
        return getUrl(getPath(cache, version, mappings), version, mappings, target).thenCompose { url ->
            if (url == null) CompletableFuture.completedFuture(Unit) else download(url, jarFile)
        }.thenApplyAsync {
            if (!Files.exists(jarFile)) return@thenApplyAsync null
            getJarFileSystem(jarFile).use { fs ->
                Files.newBufferedReader(getFile(version, mappings, target, fs)).use(format::parse)
            }
        }
    }
}

class IntermediaryMappingProvider(prefix: String, private val meta: URI, private val maven: URI) : JarMappingProvider("$prefix-intermediary", GenericTinyReader) {
    override fun getUrl(cache: Path, version: VersionInfo, mappings: String?, target: MappingTarget): CompletableFuture<URI?> {
        if (target != MappingTarget.MERGED) return CompletableFuture.completedFuture(null)
        return requestJson<JsonArray>(meta.resolve("versions/intermediary/${version.id}")).handle { it, e ->
            if (e != null) null else it
        }.thenApply {
            if (it == null || it.size() == 0) return@thenApply null
            val spec: ArtifactSpec = GSON.fromJson(it[0].asJsonObject["maven"])
            MavenArtifact(maven, spec.copy(classifier = "v2")).getURL()
        }
    }
}

enum class MappingTarget(val id: String) {
    CLIENT("client"), SERVER("server"), MERGED("merged");
}

fun getMappings(provider: MappingProvider, version: VersionInfo, target: MappingTarget, cache: Path = CACHE_DIR.resolve("mappings")): CompletableFuture<MappingTree?> {
    return provider.getLatestMappings(version, target, cache).thenCompose {
        if (it != null || target != MappingTarget.MERGED) return@thenCompose CompletableFuture.completedFuture(it)
        val client = provider.getLatestMappings(version, MappingTarget.CLIENT, cache)
        val server = provider.getLatestMappings(version, MappingTarget.SERVER, cache)
        CompletableFuture.allOf(client, server).thenApply {
            val c = client.get() ?: return@thenApply null
            val s = server.get() ?: return@thenApply null
            c.merge(s)
        }
    }
}