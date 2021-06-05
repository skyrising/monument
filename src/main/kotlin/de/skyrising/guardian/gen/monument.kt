package de.skyrising.guardian.gen

import joptsimple.OptionException
import joptsimple.OptionParser
import org.tomlj.Toml
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.stream.Collectors
import kotlin.system.exitProcess

val MONUMENT_VERSION = getMonumentVersion()

val MAVEN_CENTRAL = URI("https://repo1.maven.org/maven2/")
val FORGE_MAVEN = URI("https://maven.minecraftforge.net/")
val FABRIC_MAVEN = URI("https://maven.fabricmc.net/")
val QUILT_MAVEN = URI("https://maven.quiltmc.org/repository/release/")
val DEFAULT_DECOMPILER_MAP = mapOf<Decompiler, MavenArtifact>(
    Decompiler.CFR to MavenArtifact(MAVEN_CENTRAL, ArtifactSpec("org.benf", "cfr", "0.151")),
    Decompiler.FORGEFLOWER to MavenArtifact(FORGE_MAVEN, ArtifactSpec("net.minecraftforge", "forgeflower", "1.5.498.5")),
    Decompiler.FABRIFLOWER to MavenArtifact(FABRIC_MAVEN, ArtifactSpec("net.fabricmc", "fabric-fernflower", "1.4.0")),
    Decompiler.QUILTFLOWER to MavenArtifact(QUILT_MAVEN, ArtifactSpec("org.quiltmc", "quiltflower", "1.3.0"))
)

val OUTPUT_DIR: Path = Paths.get(System.getenv("MONUMENT_OUTPUT") ?: "output")
val REPO_DIR: Path = OUTPUT_DIR.resolve("guardian.git")
val TEMP_REPO_DIR: Path = OUTPUT_DIR.resolve("guardian-temp")
val SOURCES_DIR: Path = OUTPUT_DIR.resolve("sources")

val CACHE_DIR: Path = Paths.get(System.getenv("MONUMENT_CACHE") ?: ".cache")
val JARS_DIR: Path = CACHE_DIR.resolve("jars")

fun main(args: Array<String>) {
    val parser = OptionParser()
    val helpArg = parser.accepts("help").forHelp()
    val nonOptionsArg = parser.nonOptions()
    val recommitArg = parser.acceptsAll(listOf("r", "recommit"), "Recommit more of the history than necessary").withOptionalArg().ofType(String::class.java)
    fun printUsage() {
        System.err.println("Usage: monument [options] [branch] [action]")
        parser.printHelpOn(System.err)
    }
    var recommitFrom: String? = null
    var branch = "master"
    var action = "update"
    try {
        val options = parser.parse(*args)
        if (options.has(helpArg)) {
            printUsage()
            return
        }
        if (options.has(recommitArg)) {
            recommitFrom = ":base"
            recommitArg.valueOptional(options).ifPresent { recommitFrom = it }
        }
        val nonOptions = nonOptionsArg.values(options)
        when (nonOptions.size) {
            0 -> {}
            1 -> {
                branch = nonOptions[0]
            }
            2 -> {
                branch = nonOptions[0]
                action = nonOptions[1]
            }
            else -> throw IllegalArgumentException("Expected <branch> <action>, got ${nonOptions.size} arguments")
        }
    } catch (e: RuntimeException) {
        if (e is OptionException || e is IllegalArgumentException) {
            System.err.println(e.message)
        } else {
            e.printStackTrace()
        }
        println()
        printUsage()
        return
    }
    update(branch, action, recommitFrom)
}

fun readConfig(): Config {
    val configToml = Paths.get("config.toml")
    val configJson = Paths.get("config.json")
    val json = when {
        Files.exists(configToml) -> {
            val result = Toml.parse(configToml)
            if (result.hasErrors()) {
                System.err.println("Error reading config.toml")
                for (e in result.errors()) System.err.println(e)
                exitProcess(-1)
            }
            result.toJson()
        }
        Files.exists(configJson) -> try {
            String(Files.readAllBytes(configJson))
        } catch (e: IOException) {
            System.err.println("Error reading config.json")
            System.err.println(e)
            exitProcess(-1)
        }
        else -> {
            System.err.println("config.toml and config.json not found")
            exitProcess(-1)
        }
    }
    return GSON.fromJson(json)
}

fun update(branch: String, action: String, recommitFrom: String?) {
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
    val config = readConfig()
    val decompilerMap = HashMap(DEFAULT_DECOMPILER_MAP)
    if (config.decompilers != null) {
        decompilerMap.putAll(config.decompilers.map)
    }

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
    val (mappings, decompiler, postProcessors) = sourceConfig

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
        val srcPath = getSourcePath(it.id, mappings, decompiler)
        Files.notExists(srcPath) || Files.exists(srcPath.resolve("src/main/java-tmp"))
    }.collect(Collectors.toCollection { Collections.synchronizedSortedSet(TreeSet<VersionInfo>()) })
    println("Source code for ${spellVersions(missing.size)} missing")

    if (check) exitProcess(if (missing.isEmpty()) 1 else 0)

    if (missing.isNotEmpty()) {
        val executor = threadLocalContext.get().executor as CustomThreadPoolExecutor
        executor.decompileParallelism = 1
        val futures = mutableListOf<CompletableFuture<Path>>()
        enableOutput()
        for (version in missing) futures.add(genSources(version.id, mappings, decompiler, decompilerMap, postProcessors))
        val all = CompletableFuture.allOf(*futures.toTypedArray())
            sysOut.println("Waiting for sources to generate")
        var lines = 0
        while (!all.isDone) {
            val listedOutputs = linkedSetOf<String>()
            for (key in persistentOutputs) {
                val line = outputs[key]
                if (line != null) listedOutputs.add("$key: $line")
            }
            synchronized(outputsByThread) {
                for ((thread, key) in outputsByThread) {
                    if (!thread.isAlive) continue
                    val line = outputs[key]
                    if (line != null) listedOutputs.add("$key: $line")
                }
            }
            if (lines > 0) sysOut.print("\u001b[${lines}A\u001b[J")
            lines = 0
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
    createBranch(branch, config.git, history, recommitFrom ?: missing.firstOrNull()?.id)
    val time = (System.currentTimeMillis() - startTime) / 1000.0
    threadLocalContext.get().executor.shutdownNow()
    println(String.format(Locale.ROOT, "Done in %.3fs", time))
    dumpTimers(System.out)
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

fun genSources(version: String, provider: MappingProvider, decompiler: Decompiler, decompilerMap: Map<Decompiler, MavenArtifact>, postProcessors: List<PostProcessor>): CompletableFuture<Path> {
    val out = SOURCES_DIR.resolve(provider.name).resolve(decompiler.name).resolve(version)
    Files.createDirectories(out)
    val resOut = out.resolve("src/main/resources")
    val javaOut = out.resolve("src/main/java")
    val tmpOut = out.resolve("src/main/java-tmp")
    Files.createDirectories(tmpOut)
    Files.write(out.resolve(".monument"), listOf(
        "Monument version: $MONUMENT_VERSION",
        "Decompiler: " + decompilerMap[decompiler]!!.artifact
    ))
    return getJar(version, MappingTarget.CLIENT).thenCompose { jar ->
        if (Files.exists(resOut)) rmrf(resOut)
        time(version, "extractResources", extractResources(jar, resOut, postProcessors))
    }.thenCompose {
        val metaInf = resOut.resolve("META-INF")
        if (Files.exists(metaInf)) rmrf(metaInf)
        val jarFuture = getMappedMergedJar(version, provider)
        val libsFuture = downloadLibraries(version)
        CompletableFuture.allOf(jarFuture, libsFuture).thenCompose {
            val jar = jarFuture.get()
            val libs = libsFuture.get()
            output(version, "Decompiling with ${decompiler.name}")
            val artifact = decompilerMap[decompiler]
            decompiler.decompile(artifact, version, jar, tmpOut, libs)
        }
    }.thenCompose {
        time(version, "postProcessSources", postProcessSources(it, javaOut, postProcessors))
    }.thenCompose {
        extractGradle(version, out)
    }.thenApply {
        rmrf(tmpOut)
        closeOutput(version)
        out
    }
}

val threadLocalContext: ThreadLocal<Context> = ThreadLocal.withInitial { Context.default }

data class Context(val executor: CustomExecutorService) {
    companion object {
        val default = Context(CustomThreadPoolExecutor(Runtime.getRuntime().availableProcessors() - 2))
    }
}