package de.skyrising.guardian.gen

import com.google.gson.JsonArray
import cuchaz.enigma.Enigma
import cuchaz.enigma.ProgressListener
import cuchaz.enigma.classprovider.ClasspathClassProvider
import cuchaz.enigma.translation.mapping.EntryMapping
import cuchaz.enigma.translation.mapping.serde.MappingFormat
import cuchaz.enigma.translation.mapping.tree.EntryTree
import cuchaz.enigma.translation.mapping.tree.HashEntryTree
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

val JARS_MAPPED_DIR: Path = JARS_DIR.resolve("mapped")


interface MappingProvider {
    val name: String
    val format: MappingFormat
    fun getMappings(mappingVersion: String, target: MappingTarget, cache: Path = CACHE_DIR.resolve("mappings")): CompletableFuture<EntryTree<EntryMapping>?>
    fun supportsVersion(version: String, target: MappingTarget, cache: Path = CACHE_DIR.resolve("mappings")): CompletableFuture<Boolean> {
        return getLatestMappings(version, target, cache).thenApply { it != null }
    }
    fun getPath(cache: Path, version: String): Path = cache.resolve(name).resolve(version)
    fun getLatestMappingVersion(version: String, target: MappingTarget, cache: Path = CACHE_DIR.resolve("mappings")): CompletableFuture<String>
    fun getLatestMappings(version: String, target: MappingTarget, cache: Path = CACHE_DIR.resolve("mappings")): CompletableFuture<EntryTree<EntryMapping>?> {
        return getLatestMappingVersion(version, target, cache).thenCompose { mv ->
            if (mv == null) return@thenCompose CompletableFuture.completedFuture(null as EntryTree<EntryMapping>?)
            getMappings(mv, target, cache)
        }
    }
    companion object {
        val MOJANG = object : CommonMappingProvider("mojang", MappingFormat.PROGUARD, "txt") {
            override fun getUrl(cache: Path, mappingVersion: String, target: MappingTarget): CompletableFuture<URI?> =
                if (target == MappingTarget.MERGED) CompletableFuture.completedFuture(null)
                else getVersionManifest(mappingVersion).thenApply { manifest ->
                    manifest["downloads"]?.asJsonObject?.get(target.id + "_mappings")?.asJsonObject?.get("url")?.asString?.let { URI(it) }
                }
        }
        val FABRIC_INTERMEDIARY = IntermediaryMappingProvider("fabric", URI("https://meta.fabricmc.net/v2/"), URI("https://maven.fabricmc.net/"))
        val LEGACY_INTERMEDIARY = IntermediaryMappingProvider("legacy", URI("https://meta.legacyfabric.net/v2/"), URI("https://maven.legacyfabric.net/"))
        val QUILT_INTERMEDIARY = IntermediaryMappingProvider("quilt", URI("https://meta.quiltmc.org/v3/"), URI("https://maven.quiltmc.org/repository/release/"))
        val YARN = object : JarMappingProvider("yarn", MappingFormat.TINY_V2) {
            override fun getUrl(cache: Path, mappingVersion: String, target: MappingTarget): CompletableFuture<URI?> {
                TODO("Not yet implemented")
            }
        }
    }
}

abstract class CommonMappingProvider(override val name: String, override val format: MappingFormat, private val ext: String) : MappingProvider {
    abstract fun getUrl(cache: Path, mappingVersion: String, target: MappingTarget): CompletableFuture<URI?>
    override fun supportsVersion(version: String, target: MappingTarget, cache: Path): CompletableFuture<Boolean> = getUrl(getPath(cache, version), version, target).thenApply { it != null }
    override fun getLatestMappingVersion(version: String, target: MappingTarget, cache: Path): CompletableFuture<String> = CompletableFuture.completedFuture(version)
    override fun getMappings(mappingVersion: String, target: MappingTarget, cache: Path): CompletableFuture<EntryTree<EntryMapping>?> {
        val mappingsFile = getPath(cache, mappingVersion).resolve("mappings-${target.id}.${ext}")
        return getUrl(getPath(cache, mappingVersion), mappingVersion, target).thenCompose { url ->
            if (url == null) CompletableFuture.completedFuture(Unit) else download(url, mappingsFile)
        }.thenApplyAsync {
            if (!Files.exists(mappingsFile)) return@thenApplyAsync null
            format.read(mappingsFile, ProgressListener.none(), null)
        }
    }

    override fun toString() = "${javaClass.simpleName}($name)"
}

abstract class JarMappingProvider(override val name: String, override val format: MappingFormat) : CommonMappingProvider(name, format, "jar") {
    open fun getFile(version: String, target: MappingTarget, jar: FileSystem): Path = jar.getPath("mappings/mappings.tiny")
    override fun getMappings(mappingVersion: String, target: MappingTarget, cache: Path): CompletableFuture<EntryTree<EntryMapping>?> {
        val jarFile = getPath(cache, mappingVersion).resolve("mappings-${target.id}.jar")
        return getUrl(getPath(cache, mappingVersion), mappingVersion, target).thenCompose { url ->
            if (url == null) CompletableFuture.completedFuture(Unit) else download(url, jarFile)
        }.thenApplyAsync {
            if (!Files.exists(jarFile)) return@thenApplyAsync null
            getJarFileSystem(jarFile).use { fs ->
                format.read(getFile(mappingVersion, target, fs), ProgressListener.none(), null)
            }
        }
    }
}

class IntermediaryMappingProvider(prefix: String, private val meta: URI, private val maven: URI) : JarMappingProvider("$prefix-intermediary", MappingFormat.TINY_V2) {
    override fun getUrl(cache: Path, mappingVersion: String, target: MappingTarget): CompletableFuture<URI?> {
        if (target != MappingTarget.MERGED) return CompletableFuture.completedFuture(null)
        return requestJson<JsonArray>(meta.resolve("versions/intermediary/$mappingVersion")).handle { it, e ->
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

fun getMappings(provider: MappingProvider, version: String, target: MappingTarget, cache: Path = CACHE_DIR.resolve("mappings")): CompletableFuture<EntryTree<EntryMapping>?> {
    return provider.getMappings(version, target, cache).thenCompose {
        if (it != null || target != MappingTarget.MERGED) return@thenCompose CompletableFuture.completedFuture(it)
        val client = provider.getMappings(version, MappingTarget.CLIENT, cache)
        val server = provider.getMappings(version, MappingTarget.SERVER, cache)
        CompletableFuture.allOf(client, server).thenApply {
            mergeMappings(version,client.get() ?: return@thenApply null, server.get() ?: return@thenApply null)
        }
    }
}

fun mergeMappings(version: String, vararg mappings: EntryTree<EntryMapping>): EntryTree<EntryMapping> {
    output(version, "Merging mappings...")
    return Timer(version, "mergingMappings").use {
        val merged = HashEntryTree<EntryMapping>()
        for (mapping in mappings) for (entry in mapping) merged.insert(entry.entry, entry.value)
        merged
    }
}

fun mapJar(version: String, input: Path, mappings: EntryTree<EntryMapping>, provider: String): CompletableFuture<Path> {
    val output = JARS_MAPPED_DIR.resolve(provider).resolve(JARS_DIR.relativize(input))
    return mapJar(version, input, output, mappings).thenApply { output }
}

fun mapJar(version: String, input: Path, output: Path, mappings: EntryTree<EntryMapping>) = supplyAsync {
    if (Files.exists(output)) return@supplyAsync
    val enigma = Enigma.create()
    val project = enigma.openJar(input, ClasspathClassProvider(), ProgressListener.none())
    project.setMappings(mappings)
    output(version, "Remapping jar...")
    val jar = Timer(version, "remapJar").use {
        project.exportRemappedJar(VersionedProgressListener(version, "Deobfuscating"))
    }
    Timer(version, "writeRemappedJar").use {
        Files.createDirectories(output.parent)
        jar.write(output, VersionedProgressListener(version, "Writing deobfuscated jar"))
    }
}