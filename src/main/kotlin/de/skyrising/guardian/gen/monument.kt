package de.skyrising.guardian.gen

import com.google.common.jimfs.Jimfs
import de.skyrising.guardian.gen.mappings.*
import jdk.jfr.Configuration
import jdk.jfr.FlightRecorder
import jdk.jfr.Recording
import jdk.jfr.RecordingState
import joptsimple.OptionException
import joptsimple.OptionParser
import org.tomlj.Toml
import java.io.IOException
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CompletableFuture
import kotlin.system.exitProcess

val MONUMENT_VERSION = getMonumentVersion()

val MAVEN_CENTRAL = URI("https://repo1.maven.org/maven2/")
val FORGE_MAVEN = URI("https://maven.minecraftforge.net/")
val FABRIC_MAVEN = URI("https://maven.fabricmc.net/")
val QUILT_MAVEN = URI("https://maven.quiltmc.org/repository/release/")
val DEFAULT_DECOMPILER_MAP = mapOf<Decompiler, List<MavenArtifact>>(
    Decompiler.CFR to listOf(MavenArtifact(MAVEN_CENTRAL, ArtifactSpec("org.benf", "cfr", "0.151"))),
    Decompiler.FORGEFLOWER to listOf(MavenArtifact(FORGE_MAVEN, ArtifactSpec("net.minecraftforge", "forgeflower", "1.5.498.5"))),
    Decompiler.FABRIFLOWER to listOf(MavenArtifact(FABRIC_MAVEN, ArtifactSpec("net.fabricmc", "fabric-fernflower", "1.4.0"))),
    Decompiler.QUILTFLOWER to listOf(MavenArtifact(QUILT_MAVEN, ArtifactSpec("org.quiltmc", "quiltflower", "1.5.0"))),
    Decompiler.PROCYON to listOf(
        MavenArtifact(MAVEN_CENTRAL, ArtifactSpec("org.bitbucket.mstrobel", "procyon-core", "0.5.36")),
        MavenArtifact(MAVEN_CENTRAL, ArtifactSpec("org.bitbucket.mstrobel", "procyon-compilertools", "0.5.36"))
    )
)

val OUTPUT_DIR: Path = Path.of(System.getenv("MONUMENT_OUTPUT") ?: "output")
val REPO_DIR: Path = OUTPUT_DIR.resolve("guardian.git")
val TEMP_REPO_DIR: Path = OUTPUT_DIR.resolve("guardian-temp")
val SOURCES_DIR: Path = OUTPUT_DIR.resolve("sources")

val CACHE_DIR: Path = Path.of(System.getenv("MONUMENT_CACHE") ?: ".cache")
val RESOURCE_CACHE_DIR: Path = CACHE_DIR.resolve("resources")
val JARS_DIR: Path = CACHE_DIR.resolve("jars")

fun main(args: Array<String>) {
    FlightRecorder.register(TimerEvent::class.java)
    Files.createDirectories(Paths.get("logs"))
    Files.createDirectories(CACHE_DIR)
    if (FlightRecorder.isAvailable() && !FlightRecorder.getFlightRecorder().recordings.stream().map(Recording::getState).anyMatch { it == RecordingState.NEW || it == RecordingState.RUNNING }) {
        val conf = Configuration.create(Context::class.java.getResourceAsStream("/flightrecorder-config.jfc")!!.reader())
        val dateString = SimpleDateFormat("yyyy-MM-dd-hh-mm-ss").format(Date())
        val recording = Recording(conf)
        recording.dumpOnExit = true
        recording.isToDisk = true
        recording.name = "monument-$dateString"
        recording.destination = Path.of("logs", "monument-$dateString.jfr")
        recording.start()
    }
    val parser = OptionParser()
    val helpArg = parser.accepts("help").forHelp()
    val nonOptionsArg = parser.nonOptions()
    val recommitArg = parser.acceptsAll(listOf("r", "recommit"), "Recommit more of the history than necessary").withOptionalArg().ofType(String::class.java)
    val manifestArg = parser.acceptsAll(listOf("m", "manifest"), "Specify a custom version manifest file").withOptionalArg().ofType(String::class.java)
    fun printUsage() {
        System.err.println("Usage: monument [options] [branch] [action]")
        parser.printHelpOn(System.err)
    }
    var recommitFrom: String? = null
    var branch = "master"
    var action = "update"
    var manifest: Path? = null
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
        manifestArg.valueOptional(options).ifPresent { manifest = Paths.get(it) }
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
    try {
        update(branch, action, recommitFrom, manifest)
    } finally {
        threadLocalContext.get().executor.shutdownNow()
    }
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

fun update(branch: String, action: String, recommitFrom: String?, manifest: Path?) {
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

    val versions = if (manifest != null) {
        parseVersionManifest(GSON.fromJson(Files.newBufferedReader(manifest)))
    } else {
        getMcVersions().join()
    }
    val newest = linkVersions(versions)
    fun getVersion(id: String?): VersionInfo? {
        if (id == null) return null
        return versions[id] ?: throw IllegalArgumentException("Unknown version '${id}'")
    }
    val base = getVersion(branchConfig.base)
    val head = (if (branchConfig.head == null) newest else getVersion(branchConfig.head))
        ?: throw IllegalStateException("No head version found")
    println("Finding path from ${base ?: "the beginning"} to $head")
    val branchVersions = findPredecessors(head, base) {
        branchConfig.filter(it) && (
            immediate { mappings.supportsVersion(it, MappingTarget.MERGED) }
            || immediate { mappings.supportsVersion(it, MappingTarget.CLIENT) }
            || immediate { mappings.supportsVersion(it, MappingTarget.SERVER) }
        )
    }
    if (branchVersions.isEmpty()) {
        System.err.println("No path found")
        return
    }
    println("Found a graph containing ${spellVersions(branchVersions.size)}: $branchVersions")
    val missing = branchVersions.filter {
        val srcPath = getSourcePath(it.id, mappings, decompiler)
        Files.notExists(srcPath) || Files.notExists(srcPath.resolve("src/main/java")) || Files.exists(srcPath.resolve("src/main/java-tmp"))
    }
    println("Source code for ${spellVersions(missing.size)} missing: $missing")

    if (check) exitProcess(if (missing.isEmpty()) 1 else 0)

    if (missing.isNotEmpty()) {
        val executor = threadLocalContext.get().executor as CustomThreadPoolExecutor
        executor.decompileParallelism = 1
        val futures = mutableListOf<CompletableFuture<Path>>()
        enableOutput()
        for (version in missing) futures.add(genSources(version, mappings, decompiler, decompilerMap, postProcessors))
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
    val history = createCommits(branchVersions) { version, parents ->
        CommitTemplate(version, getSourcePath(version.id, mappings, decompiler), parents)
    }
    createBranch(branch, config.git, history, recommitFrom ?: missing.firstOrNull()?.id)
    val time = (System.currentTimeMillis() - startTime) / 1000.0
    threadLocalContext.get().executor.shutdownNow()
    println(String.format(Locale.ROOT, "Done in %.3fs", time))
    dumpTimers(System.out)
}

fun spellVersions(count: Int) = if (count == 1) "$count version" else "$count versions"

fun getSourcePath(version: String, mappings: MappingProvider, decompiler: Decompiler): Path = SOURCES_DIR.resolve(mappings.name).resolve(decompiler.name).resolve(version)

fun getMappedMergedJar(version: VersionInfo, provider: MappingProvider): CompletableFuture<Path> {
    val mappedJarPath = getMappedJarOutput(provider.name, JARS_MERGED_DIR.resolve("${version.id}.jar"))
    if (Files.exists(mappedJarPath)) return CompletableFuture.completedFuture(mappedJarPath)
    val jar = getJar(version, MappingTarget.MERGED)
    val mappings = getMappings(provider, version, MappingTarget.MERGED)
    return CompletableFuture.allOf(jar, mappings).thenCompose {
        val m = mappings.get() ?: throw IllegalStateException("No mappings")
        return@thenCompose mapJar(version.id, jar.get(), m, provider.name)
    }
}

fun genSources(version: VersionInfo, provider: MappingProvider, decompiler: Decompiler, decompilerMap: Map<Decompiler, List<MavenArtifact>>, postProcessors: List<PostProcessor>): CompletableFuture<Path> {
    val out = SOURCES_DIR.resolve(provider.name).resolve(decompiler.name).resolve(version.id)
    if (Files.exists(out)) rmrf(out)
    Files.createDirectories(out)
    val resOut = out.resolve("src/main/resources")
    val javaOut = out.resolve("src/main/java")
    var tmpOutFs: FileSystem? = null
    var tmpOutPath: Path? = null
    Files.write(out.resolve(".monument"), listOf(
        "Monument version: $MONUMENT_VERSION",
        "Decompiler: " + decompilerMap[decompiler]!!.first().artifact
    ))
    return getJar(version, MappingTarget.CLIENT).thenCompose { jar ->
        if (Files.exists(resOut)) rmrf(resOut)
        time(version.id, "extractResources", extractResources(jar, resOut, postProcessors))
    }.thenCompose {
        val metaInf = resOut.resolve("META-INF")
        if (Files.exists(metaInf)) rmrf(metaInf)
        val jarFuture = getMappedMergedJar(version, provider)
        val libsFuture = downloadLibraries(version)
        CompletableFuture.allOf(jarFuture, libsFuture).thenCompose {
            val jar = jarFuture.get()
            val libs = libsFuture.get()
            output(version.id, "Decompiling with ${decompiler.name}")
            val artifacts = decompilerMap[decompiler]!!
            val outputDir: (Boolean) -> Path = {
                if (it) {
                    val fs = Jimfs.newFileSystem()
                    tmpOutFs = fs
                    fs.rootDirectories.first()
                } else {
                    val tmpOut = out.resolve("src/main/java-tmp")
                    tmpOutPath = tmpOut
                    Files.createDirectories(tmpOut)
                    tmpOut
                }
            }
            decompiler.decompile(artifacts, version.id, jar, outputDir, libs)
        }
    }.thenCompose {
        time(version.id, "postProcessSources", postProcessSources(it, javaOut, postProcessors))
    }.thenCompose {
        extractGradleAndExtraSources(version, out)
    }.thenApply {
        tmpOutFs?.close()
        tmpOutPath?.apply(::rmrf)
        closeOutput(version.id)
        out
    }.exceptionally { throw RuntimeException("Error generating sources for ${version.id}", it) }
}

val threadLocalContext: ThreadLocal<Context> = ThreadLocal.withInitial { Context.default }

data class Context(val executor: CustomExecutorService) {
    companion object {
        val default = Context(CustomThreadPoolExecutor(Runtime.getRuntime().availableProcessors() - 2))
    }
}