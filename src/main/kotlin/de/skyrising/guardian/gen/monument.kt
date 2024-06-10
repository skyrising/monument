package de.skyrising.guardian.gen

import com.google.common.jimfs.Jimfs
import de.skyrising.guardian.gen.mappings.*
import jdk.jfr.Configuration
import jdk.jfr.FlightRecorder
import jdk.jfr.Recording
import jdk.jfr.RecordingState
import joptsimple.OptionException
import joptsimple.OptionParser
import org.jline.terminal.TerminalBuilder
import org.tomlj.Toml
import java.io.IOException
import java.net.URI
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

val INITIAL_MAX_THREADS = maxOf(Runtime.getRuntime().availableProcessors() - 2, 1)
var MAX_THREADS = INITIAL_MAX_THREADS

val threadLocalContext: ThreadLocal<Context> = ThreadLocal.withInitial { Context.default }

val _t = run {
    TraceEvents.start(Path.of("logs", "trace.json"))
    Runtime.getRuntime().addShutdownHook(Thread {
        TraceEvents.stop()
    })
}

val MONUMENT_VERSION = getMonumentVersion()

val MAVEN_CENTRAL = URI("https://repo1.maven.org/maven2/")
val FORGE_MAVEN = URI("https://maven.minecraftforge.net/")
val FABRIC_MAVEN = URI("https://maven.fabricmc.net/")
val QUILT_MAVEN = URI("https://maven.quiltmc.org/repository/release/")
val DEFAULT_DECOMPILER_MAP = mapOf<Decompiler, List<MavenArtifact>>(
    Decompiler.CFR to listOf(MavenArtifact(MAVEN_CENTRAL, ArtifactSpec("org.benf", "cfr", "0.152"))),
    Decompiler.FORGEFLOWER to listOf(MavenArtifact(FORGE_MAVEN, ArtifactSpec("net.minecraftforge", "forgeflower", "1.5.498.5"))),
    Decompiler.FABRIFLOWER to listOf(MavenArtifact(FABRIC_MAVEN, ArtifactSpec("net.fabricmc", "fabric-fernflower", "1.4.0"))),
    Decompiler.QUILTFLOWER to listOf(MavenArtifact(QUILT_MAVEN, ArtifactSpec("org.quiltmc", "quiltflower", "1.9.0"))),
    Decompiler.VINEFLOWER to listOf(MavenArtifact(MAVEN_CENTRAL, ArtifactSpec("org.vineflower", "vineflower", "1.9.3"))),
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

private val UNICODE_PROGRESS_BLOCKS = charArrayOf(' ', '▏', '▎', '▍', '▌', '▋', '▊', '▉', '█')

val TERMINAL = TerminalBuilder.terminal()

val OPENED_LIBRARIES = ConcurrentHashMap<Path, FileSystem>()

fun main(args: Array<String>) {
    FlightRecorder.register(TimerEvent::class.java)
    Files.createDirectories(Paths.get("logs"))
    Files.createDirectories(CACHE_DIR)
    if (FlightRecorder.isAvailable() && !FlightRecorder.getFlightRecorder().recordings.stream().map(Recording::getState).anyMatch { it == RecordingState.NEW || it == RecordingState.RUNNING }) {
        val conf = Configuration.create(Context::class.java.getResourceAsStream("/flightrecorder-config.jfc")!!.reader())
        val dateString = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(Date())
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
    val threadsArg = parser.acceptsAll(listOf("t", "threads"), "Maximum number of threads to use").withRequiredArg().ofType(Int::class.java)
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
        if (options.has(threadsArg)) {
            val maxThreads = threadsArg.value(options)
            if (maxThreads >= 1) {
                MAX_THREADS = maxThreads
                val executor = threadLocalContext.get().executor as CustomThreadPoolExecutor
                executor.decompileParallelism = minOf(executor.decompileParallelism, MAX_THREADS)
            }
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
        immediate { getMcVersions() }
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
    val branchVersions = Timer("", "findPredecessors").use {
        findPredecessors(head, base) {
            branchConfig.filter(it) && (
                    immediate { mappings.supportsVersion(it, MappingTarget.MERGED) }
                            || immediate { mappings.supportsVersion(it, MappingTarget.CLIENT) }
                            || immediate { mappings.supportsVersion(it, MappingTarget.SERVER) }
                    )
        }
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
        val futures = mutableListOf<CompletableFuture<Void>>()
        enableOutput()
        val executor = threadLocalContext.get().executor as CustomThreadPoolExecutor
        executor.decompileParallelism = minOf(executor.decompileParallelism, missing.size)
        val progressUnits = mutableListOf<ProgressUnit>()
        for (version in missing) {
            val unit = ProgressUnit(2, 0)
            progressUnits.add(unit)
            futures.add(genSources(unit, version, mappings, decompiler, decompilerMap, postProcessors).thenRun {
                unit.done = unit.tasks
            })
        }
        val all = CompletableFuture.allOf(*futures.toTypedArray())
        sysOut.println("Waiting for sources to generate")
        var lines = 0
        fun printStatus(full: Boolean) {
            val listedOutputs = sortedSetOf<String>()
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
            val totalUnits = progressUnits.size
            var doneUnits = 0
            var totalTasks = 0
            var doneTasks = 0
            for (unit in progressUnits) {
                val unitTasks = unit.totalTasks
                val unitDone = unit.totalDone
                if (unitDone == unitTasks) doneUnits++
                totalTasks += unitTasks
                doneTasks += unit.totalDone
            }
            val fraction = if (totalTasks == 0) 0.0 else doneTasks.toDouble() / totalTasks
            val terminalWidth = TERMINAL.width
            val progress = StringBuilder("Progress: ")
            progress.append(doneUnits).append('/').append(totalUnits).append(", ")
            progress.append(doneTasks).append('/').append(totalTasks).append(" tasks ")
            if (totalTasks > missing.size * 4) {
                val progressBarWidth = terminalWidth - progress.length - 10
                progress.append("\u001b[100m")
                val barProgress = fraction * progressBarWidth
                val completeBarUnits = barProgress.toInt()
                val partialUnit = ((barProgress - completeBarUnits) * 8).toInt()
                repeat(completeBarUnits) { progress.append(UNICODE_PROGRESS_BLOCKS[8]) }
                if (completeBarUnits < progressBarWidth) {
                    progress.append(UNICODE_PROGRESS_BLOCKS[partialUnit])
                    repeat(progressBarWidth - completeBarUnits - 1) { progress.append(' ') }
                }
                progress.append("\u001b[m")
                progress.append(String.format(" %6.2f%%", fraction * 100))
            }
            val output = StringBuilder()
            if (lines > 0) output.append("\u001b[${lines}A\u001b[J")
            output.append(progress)
            lines = 1
            if (full) {
                for (line in listedOutputs) {
                    lines++
                    output.append('\n').append(line.substring(0, minOf(line.length, terminalWidth)))
                }
            }
            sysOut.println(output)
        }
        while (!all.isDone) {
            printStatus(true)
            if (!all.isDone) Thread.sleep(100)
        }
        printStatus(false)
        disableOutput()
        if (all.isCompletedExceptionally) {
            try {
                all.get()
            } catch (e: Exception) {
                e.printStackTrace()
                exitProcess(1)
            }
        }
        for (lib in OPENED_LIBRARIES.values) {
            lib.close()
        }
        OPENED_LIBRARIES.clear()
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
    //dumpTimers(System.out)
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

fun genSources(unit: ProgressUnit, version: VersionInfo, provider: MappingProvider, decompiler: Decompiler, decompilerMap: Map<Decompiler, List<MavenArtifact>>, postProcessors: List<PostProcessor>): CompletableFuture<Path> {
    val out = SOURCES_DIR.resolve(provider.name).resolve(decompiler.name).resolve(version.id)
    unit.done++
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
    val jarFuture = unit(getMappedMergedJar(version, provider))
    val libsFuture = unit(downloadLibraries(version))
    return CompletableFuture.allOf(jarFuture, libsFuture).thenCompose {
        val extractResources = unit(getJar(version, MappingTarget.CLIENT)).thenCompose { jar ->
            if (Files.exists(resOut)) rmrf(resOut)
            unit(extractResources(version.id, jar, resOut, postProcessors))
        }
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
        val classes = HashSet<String>(4096)
        getJarFileSystem(jar).use { fs ->
            Files.walkFileTree(fs.getPath("/"), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = file.toString()
                    if (name.endsWith(".class") && !name.contains('$')) {
                        classes.add(name.substring(1, name.length - 6))
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        }
        val classesUnit = unit.subUnit(classes.size)
        for (lib in libs) {
            OPENED_LIBRARIES.computeIfAbsent(lib, ::getJarFileSystem)
        }
        val decompile = decompiler.decompile(artifacts, version.id, jar, outputDir, libs) { className, addExtra ->
            if (className.replace('.', '/') in classes) {
                TraceEvent.Instant(name = "${if (addExtra) "Preprocessing" else "Decompiled"} Class", cat = "decompile,${version.id}", args = mapOf("class" to className))
                classesUnit.done++
                if (addExtra) classesUnit.tasks++
            }
        }
        CompletableFuture.allOf(decompile, extractResources).thenApply {
            classesUnit.done = classesUnit.tasks
            val metaInf = resOut.resolve("META-INF")
            if (Files.exists(metaInf)) rmrf(metaInf)
            decompile.get()
        }
    }.thenCompose {
        unit(postProcessSources(version.id, it, javaOut, postProcessors))
    }.thenCompose {
        unit(extractGradleAndExtraSources(version, out))
    }.thenApply {
        unit.tasks++
        tmpOutFs?.close()
        tmpOutPath?.apply(::rmrf)
        closeOutput(version.id)
        unit.done++
        out
    }.exceptionally { throw RuntimeException("Error generating sources for ${version.id}", it) }
}

data class Context(val executor: CustomExecutorService) {
    companion object {
        val default = Context(CustomThreadPoolExecutor(minOf(INITIAL_MAX_THREADS, MAX_THREADS + 4)))
    }
}