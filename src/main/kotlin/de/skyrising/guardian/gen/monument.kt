package de.skyrising.guardian.gen

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.stream.Collectors
import kotlin.system.exitProcess


val OUTPUT_DIR: Path = Paths.get(System.getenv("MONUMENT_OUTPUT") ?: "output")
val REPO_DIR: Path = OUTPUT_DIR.resolve("guardian.git")
val TEMP_REPO_DIR: Path = OUTPUT_DIR.resolve("guardian-temp")
val SOURCES_DIR: Path = OUTPUT_DIR.resolve("sources")

val CACHE_DIR: Path = Paths.get(System.getenv("MONUMENT_CACHE") ?: ".cache")
val JARS_DIR: Path = CACHE_DIR.resolve("jars")

fun main(args: Array<String>) {
    when (args.size) {
        0 -> update()
        1 -> update(args[0])
        2 -> update(args[0], args[1])
        else -> println("usage: monument [branch] [action]")
    }
}

fun update(branch: String = "master", action: String = "update") {
    val startTime = System.currentTimeMillis()
    val check = action == "check"
    if (!check) {
        Files.createDirectories(OUTPUT_DIR)
        Files.createDirectories(REPO_DIR)
        if (!Files.exists(REPO_DIR.resolve("HEAD"))) {
            git(REPO_DIR, "init", "--bare").join()
            println("Created bare repository $REPO_DIR")
        }
        Files.createDirectories(SOURCES_DIR)
    }
    val config = readConfig(Files.newBufferedReader(Paths.get("config.json"))).join()
    val branchConfig = config.branches[branch]
    if (branchConfig == null) {
        System.err.println("No definition for branch '$branch'")
        return
    }
    val sourceConfig = config.sources[branchConfig.source]
    if (sourceConfig == null) {
        System.err.println("No definition for source '${branchConfig.source}'")
        return
    }
    val (mappings, decompiler, processStructures) = sourceConfig

    val versions = mcVersions.join()
    fun getVersion(id: String?): VersionInfo? {
        if (id == null) return null
        return versions[id] ?: throw IllegalArgumentException("Unknown version '${id}'")
    }
    val base = getVersion(branchConfig.base)
    val head = getVersion(branchConfig.head)
    val branchVersions = versions.filterValues {
        when {
            base != null && it < base -> false
            head != null && it > head -> false
            else -> branchConfig.filter(it)
        }
    }
    println("Filtering version list (${spellVersions(branchVersions.size)})")
    val supported = branchVersions.values.parallelStream().filter {
        immediate { mappings.supportsVersion(it.id, MappingTarget.CLIENT) } || immediate { mappings.supportsVersion(it.id, MappingTarget.SERVER) }
    }.collect(Collectors.toCollection { Collections.synchronizedSortedSet(TreeSet<VersionInfo>()) })
    println("${spellVersions(supported.size)} supported by '${mappings.name}' mappings")
    val missing = supported.parallelStream().filter {
        Files.notExists(getSourcePath(it.id, mappings, decompiler))
    }.collect(Collectors.toCollection { Collections.synchronizedSortedSet(TreeSet<VersionInfo>()) })
    println("Source code for ${spellVersions(missing.size)} missing")

    if (check) exitProcess(if (missing.isEmpty()) 1 else 0)

    if (missing.isNotEmpty()) {
        val futures = mutableListOf<CompletableFuture<Path>>()
        enableOutput()
        for (version in missing) futures.add(genSources(version.id, mappings, decompiler, processStructures))
        val all = CompletableFuture.allOf(*futures.toTypedArray())
            sysOut.println("Waiting for sources to generate")
        var lines = 0
        while (!all.isDone) {
            if (lines > 0) sysOut.print("\u001b[${lines}A\u001b[J")
            lines = 0
            val listedOutputs = linkedSetOf<String>()
            for (key in persistentOutputs) {
                val line = outputs[key]
                if (line != null) listedOutputs.add("$key: $line")
            }
            for ((thread, key) in outputsByThread) {
                if (!thread.isAlive) continue
                val line = outputs[key]
                if (line != null) listedOutputs.add("$key: $line")
            }
            for (line in listedOutputs) {
                lines++
                sysOut.println(line)
            }
            if (!all.isDone) Thread.sleep(100)
        }
        disableOutput()
        if (all.isCompletedExceptionally) {
            try {
                all.get()
            } catch (e: Exception) {
                e.printStackTrace()
                exitProcess(1)
            }
        }
        println("All sources generated")
    }
    println("Creating branch '$branch'")
    val history = mutableListOf<CommitTemplate>()
    for (version in supported) history.add(CommitTemplate(version, getSourcePath(version.id, mappings, decompiler)))
    createBranch(branch, config.git, history)
    val time = (System.currentTimeMillis() - startTime) / 1000.0
    println(String.format(Locale.ROOT, "Done in %.3fs", time))
}

fun spellVersions(count: Int) = if (count == 1) "$count version" else "$count versions"

fun getSourcePath(version: String, mappings: MappingProvider, decompiler: Decompiler): Path = SOURCES_DIR.resolve(mappings.name).resolve(decompiler.name).resolve(version)

fun getMappedMergedJar(version: String, provider: MappingProvider): CompletableFuture<Path> {
    val jar = getJar(version, MappingTarget.MERGED)
    val mappings = getMappings(provider, version, MappingTarget.MERGED)
    return CompletableFuture.allOf(jar, mappings).thenCompose {
        val m = mappings.get() ?: throw IllegalStateException("No mappings")
        return@thenCompose mapJar(version, jar.get(), m, provider.name)
    }
}

fun genSources(version: String, provider: MappingProvider, decompiler: Decompiler, processStructures: Boolean): CompletableFuture<Path> {
    val out = SOURCES_DIR.resolve(provider.name).resolve(decompiler.name).resolve(version)
    Files.createDirectories(out)
    val resOut = out.resolve("src").resolve("main").resolve("resources")
    val javaOut = out.resolve("src").resolve("main").resolve("java")
    return getJar(version, MappingTarget.CLIENT).thenCompose { jar ->
        if (Files.exists(resOut)) rmrf(resOut)
        extractResources(jar, resOut, processStructures)
    }.thenCompose {
        val metaInf = resOut.resolve("META-INF")
        if (Files.exists(metaInf)) rmrf(metaInf)
        val jarFuture = getMappedMergedJar(version, provider)
        val libsFuture = downloadLibraries(version)
        CompletableFuture.allOf(jarFuture, libsFuture).thenCompose {
            val jar = jarFuture.get()
            val libs = libsFuture.get()
            if (Files.exists(javaOut)) rmrf(javaOut)
            Files.createDirectories(javaOut)
            output(version, "Decompiling with ${decompiler.name}")
            decompiler.decompile(version, jar, javaOut, libs)
        }
    }.thenCompose {
        extractGradle(version, out)
    }.thenApply {
        closeOutput(version)
        out
    }
}

val threadLocalContext: ThreadLocal<Context> = ThreadLocal.withInitial { Context.default }

data class Context(val executor: ExecutorService) {
    companion object {
        val default = Context(Executors.newWorkStealingPool())
    }
}