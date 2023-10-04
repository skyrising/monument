package de.skyrising.guardian.gen

import com.google.gson.*
import com.google.gson.reflect.TypeToken
import de.skyrising.guardian.gen.mappings.MappingProvider
import java.io.Reader
import java.lang.reflect.Type
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

inline fun <reified T> type(): Type = (object : TypeToken<T>() {}).type

inline fun <reified T> Gson.fromJson(reader: Reader): T {
    return this.fromJson(reader, type<T>())
}
inline fun <reified T> Gson.fromJson(el: JsonElement): T {
    return this.fromJson(el, type<T>())
}
inline fun <reified T> Gson.fromJson(json: String): T {
    return this.fromJson(json, type<T>())
}

inline fun <reified E : JsonElement, reified T> GsonBuilder.registerTypeAdapter(crossinline deserializer: (E, Type, JsonDeserializationContext) -> T): GsonBuilder {
    return this.registerTypeAdapter(type<T>(), JsonDeserializer { el, type, context ->
        if (el !is E) throw IllegalArgumentException("Expected '${E::class.java.simpleName}'")
        deserializer(el, type, context)
    })
}

inline fun <reified T> JsonDeserializationContext.deserialize(jsonElement: JsonElement): T {
    return this.deserialize(jsonElement, type<T>())
}

fun JsonObject.require(key: String) = this[key] ?: throw IllegalArgumentException("'$key' required")
inline fun <reified T> JsonObject.require(key: String, context: JsonDeserializationContext) = context.deserialize<T>(require(key))
inline operator fun <reified T> JsonObject.get(key: String, context: JsonDeserializationContext) = get(key)?.let { context.deserialize<T>(it) }

data class Config(val git: GitConfig, val sources: SourcesConfig, val branches: BranchesConfig, val decompilers: DecompilerMap?)
data class GitConfig(val origin: String, val author: GitPerson, val committer: GitPerson)
data class GitPerson(val name: String, val email: String)
typealias SourcesConfig = Map<String, SourceConfig>
typealias BranchesConfig = Map<String, BranchConfig>
data class SourceConfig(val mappings: MappingProvider, val decompiler: Decompiler, val postProcessors: List<PostProcessor>)
data class BranchConfig(val source: String, val head: String?, val base: String?, val filter: FilterConfig)
data class FilterConfig(val type: String?, val exclude: List<String>) : Function1<VersionInfo, Boolean> {
    override fun invoke(version: VersionInfo) = when {
        type != null && version.type != type -> false
        exclude.contains(version.id) -> false
        else -> true
    }
}
data class ArtifactSpec(val group: String, val id: String, val version: String, val classifier: String? = null) {
    override fun toString(): String {
        return if (classifier != null) {
            "$group:$id:$version:$classifier"
        } else {
            "$group:$id:$version"
        }
    }

    companion object {
        fun of(spec: String): ArtifactSpec {
            val parts = spec.split(":")
            if (parts.size == 4) {
                return ArtifactSpec(parts[0], parts[1], parts[2], parts[3])
            }
            if (parts.size != 3) throw IllegalArgumentException("Expected group:id:version, got '$spec'")
            return ArtifactSpec(parts[0], parts[1], parts[2])
        }
    }
}
data class MavenArtifact(val mavenUrl: URI, val artifact: ArtifactSpec) {
    fun getPath(): String {
        val artifact = artifact
        val id = artifact.id
        val version = artifact.version
        val versionA = version.substringBefore('/')
        val versionB = version.substringAfter('/')
        val classifier = artifact.classifier?.let { "-$it" } ?: ""
        return "${artifact.group.replace('.', '/')}/$id/$versionA/$id-$versionB$classifier.jar"
    }
    fun getURL(): URI = mavenUrl.resolve(getPath())
}
data class DecompilerMap(val map: Map<Decompiler, List<MavenArtifact>>)

val GSON: Gson = GsonBuilder()
    .registerTypeAdapter<JsonObject, GitConfig> { obj, _, context ->
        val origin: String = obj.require("origin", context)
        val author: GitPerson? = obj["author", context]
        val committer: GitPerson? = obj["committer", context]
        if (author == null && committer == null) throw IllegalArgumentException("Need at least one of 'author' or 'committer'")
        GitConfig(origin, (author ?: committer)!!, (committer ?: author)!!)
    }
    .registerTypeAdapter<JsonObject, BranchConfig> { obj, _, context ->
        val source: String = obj.require("source", context)
        val head: String? = obj["head", context]
        val base: String? = obj["base", context]
        val filter: FilterConfig = obj["filter", context] ?: FilterConfig(null, listOf())
        BranchConfig(source, head, base, filter)
    }
    .registerTypeAdapter<JsonObject, FilterConfig> { obj, _, context ->
        val type: String? = obj["type", context]
        val exclude: List<String> = obj["exclude", context] ?: listOf()
        FilterConfig(type, exclude)
    }
    .registerTypeAdapter<JsonObject, SourceConfig> { obj, _, context ->
        val mappings: MappingProvider = obj.require("mappings", context)
        val decompiler: Decompiler = obj.require("decompiler", context)
        val postProcessors = mutableListOf<PostProcessor>()
        val processStructures = obj["processStructures", context] ?: true
        if (processStructures) postProcessors.add(STRUCTURE_PROCESSOR)
        val processSources = obj["processSources", context] ?: true
        if (processSources) postProcessors.add(SOURCE_PROCESSOR)
        SourceConfig(mappings, decompiler, postProcessors)
    }
    .registerTypeAdapter<JsonObject, VersionInfo> { obj, _, context ->
        var id: String = obj.require("id", context)
        if (obj.has("omniId")) id = obj.require("omniId", context)
        VersionInfo(
            id,
            obj.require("type", context),
            obj.require("url", context),
            obj["time", context] ?: obj.require("releaseTime", context),
            obj.require("releaseTime", context)
        )
    }
    .registerTypeAdapter<JsonObject, DecompilerMap> { obj, _, context ->
        val map = mutableMapOf<Decompiler, List<MavenArtifact>>()
        for (key in obj.keySet()) {
            val decompiler = getDecompiler(key)
            val value = obj[key]
            val arr = if (value.isJsonArray) value.asJsonArray.toList() else listOf(value)
            val artifacts = arr.map {
                if (it.isJsonObject) {
                    context.deserialize(it)
                } else {
                    MavenArtifact(MAVEN_CENTRAL, context.deserialize(it))
                }
            }
            map[decompiler] = artifacts
        }
        DecompilerMap(map)
    }
    .registerTypeAdapter<JsonObject, MavenArtifact> { obj, _, context ->
        MavenArtifact(URI(obj.require("url", context)), obj.require("artifact", context))
    }
    .registerTypeAdapter<JsonPrimitive, MappingProvider> { prim, _, _ ->
        when (val s = prim.asString) {
            "mojang" -> MappingProvider.MOJANG
            "fabric-intermediary" -> MappingProvider.FABRIC_INTERMEDIARY
            "legacy-intermediary" -> MappingProvider.LEGACY_INTERMEDIARY
            "quilt-intermediary" -> MappingProvider.QUILT_INTERMEDIARY
            else -> throw IllegalArgumentException("Unknown mapping provider '$s'")
        }
    }
    .registerTypeAdapter<JsonPrimitive, Decompiler> { prim, _, _ ->
        getDecompiler(prim.asString)
    }
    .registerTypeAdapter<JsonPrimitive, ArtifactSpec> { prim, _, _ ->
        ArtifactSpec.of(prim.asString)
    }
    .registerTypeAdapter<JsonPrimitive, ZonedDateTime> { prim, _, _ ->
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(prim.asString, ZonedDateTime::from)
    }
    .create()

fun getDecompiler(id: String) = when (id) {
    "cfr" -> Decompiler.CFR
    "fernflower" -> Decompiler.FERNFLOWER
    "forgeflower" -> Decompiler.FORGEFLOWER
    "fabriflower" -> Decompiler.FABRIFLOWER
    "quiltflower" -> Decompiler.QUILTFLOWER
    "vineflower" -> Decompiler.VINEFLOWER
    "procyon" -> Decompiler.PROCYON
    else -> throw IllegalArgumentException("Unknown decompiler '$id'")
}