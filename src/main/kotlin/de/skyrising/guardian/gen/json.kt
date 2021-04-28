package de.skyrising.guardian.gen

import com.google.gson.*
import com.google.gson.reflect.TypeToken
import java.io.Reader
import java.lang.reflect.Type
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

inline fun <reified T> type(): Type = (object : TypeToken<T>() {}).type

inline fun <reified T> Gson.fromJson(reader: Reader): T {
    return this.fromJson(reader, type<T>())
}
inline fun <reified T> Gson.fromJson(el: JsonElement): T {
    return this.fromJson(el, type<T>())
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

data class Config(val git: GitConfig, val sources: SourcesConfig, val branches: BranchesConfig)
data class GitConfig(val origin: String, val author: GitPerson, val committer: GitPerson)
data class GitPerson(val name: String, val email: String)
typealias SourcesConfig = Map<String, SourceConfig>
typealias BranchesConfig = Map<String, BranchConfig>
data class SourceConfig(val mappings: MappingProvider, val decompiler: Decompiler, val processStructures: Boolean)
data class BranchConfig(val source: String, val head: String?, val base: String?, val filter: FilterConfig)
data class FilterConfig(val type: String?, val exclude: List<String>) : Function1<VersionInfo, Boolean> {
    override fun invoke(version: VersionInfo) = when {
        type != null && version.type != type -> false
        exclude.contains(version.id) -> false
        else -> true
    }
}

fun readConfig(reader: Reader) = supplyAsync {
    GSON.fromJson<Config>(reader)
}

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
        val processStructures = obj["processStructures", context] ?: true
        SourceConfig(mappings, decompiler, processStructures)
    }
    .registerTypeAdapter<JsonPrimitive, MappingProvider> { prim, _, _ ->
        when (val s = prim.asString) {
            "mojang" -> MappingProvider.MOJANG
            else -> throw IllegalArgumentException("Unknown mapping provider '$s'")
        }
    }
    .registerTypeAdapter<JsonPrimitive, Decompiler> { prim, _, _ ->
        when (val s = prim.asString) {
            "cfr" -> Decompiler.CFR
            else -> throw IllegalArgumentException("Unknown decompiler '$s'")
        }
    }
    .registerTypeAdapter<JsonPrimitive, ZonedDateTime> { prim, _, _ ->
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(prim.asString, ZonedDateTime::from)
    }
    .create()