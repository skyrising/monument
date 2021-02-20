package de.skyrising.guardian.gen

import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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

data class CommitTemplate(val version: VersionInfo, val source: Path)

fun createBranch(branch: String, config: GitConfig, history: List<CommitTemplate>) {
    if (Files.exists(TEMP_REPO_DIR)) rmrf(TEMP_REPO_DIR)
    Files.createDirectories(TEMP_REPO_DIR)
    git(TEMP_REPO_DIR, "init").join()
    git(TEMP_REPO_DIR, "remote", "add", "guardian", REPO_DIR.toAbsolutePath().toString()).join()
    git(TEMP_REPO_DIR, "checkout", "-b", branch).join()
    for (commit in history) {
        val destFiles = mutableMapOf<Path, Path>()
        Files.list(commit.source).forEach {
            val dest = TEMP_REPO_DIR.resolve(commit.source.relativize(it))
            destFiles[dest] = it
            Files.move(it, dest)
        }
        println("${commit.version.id}: ${commit.source.toAbsolutePath()}")
        git(TEMP_REPO_DIR, "add", ".").join()
        gitCommit(TEMP_REPO_DIR, commit.version.releaseTime, config, "-m", commit.version.id).join()
        val tag = if (branch == "master") commit.version.id else "$branch-${commit.version.id}"
        git(TEMP_REPO_DIR, "tag", tag).join()
        // for (repoFile in destFiles) rmrf(repoFile)
        for ((a, b) in destFiles) Files.move(a, b)
    }
    git(TEMP_REPO_DIR, "push", "--force", "--set-upstream", "guardian", branch).join()
    git(TEMP_REPO_DIR, "push", "--tags", "--force").join()
    rmrf(TEMP_REPO_DIR)
}

fun git(dir: Path, vararg args: String): CompletableFuture<Int> {
    val command = mutableListOf("git")
    for (arg in args) command += arg
    val pb = ProcessBuilder(command)
    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
    pb.redirectError(ProcessBuilder.Redirect.INHERIT)
    pb.directory(dir.toFile())
    val p = pb.start()
    return CompletableFuture.supplyAsync { p.waitFor() }
}

fun gitCommit(dir: Path, date: LocalDateTime, config: GitConfig, vararg args: String): CompletableFuture<Int> {
    val command = mutableListOf("git", "commit")
    for (arg in args) command += arg
    val pb = ProcessBuilder(command)
    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
    pb.redirectError(ProcessBuilder.Redirect.INHERIT)
    pb.directory(dir.toFile())
    val env = pb.environment()
    val dateString = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(date)
    env["GIT_AUTHOR_DATE"] = dateString
    env["GIT_AUTHOR_NAME"] = config.author.name
    env["GIT_AUTHOR_EMAIL"] = config.author.email
    env["GIT_COMMITTER_DATE"] = dateString
    env["GIT_COMMITTER_NAME"] = config.committer.name
    env["GIT_COMMITTER_EMAIL"] = config.committer.email
    val p = pb.start()
    return CompletableFuture.supplyAsync { p.waitFor() }
}

val threadLocalContext: ThreadLocal<Context> = ThreadLocal.withInitial { Context.default }

data class Context(val executor: ExecutorService) {
    companion object {
        val default = Context(Executors.newWorkStealingPool())
    }
}

private val outputs = mutableMapOf<String, String>()
private val outputStreams = mutableMapOf<String, PrintStream>()
private val persistentOutputs = listOf("sysout", "syserr")
private val outputsByThread = linkedMapOf<Thread, String>()
private var outputEnabled = false
private val sysOut = System.out
private val sysErr = System.err
private val outputToKey = ThreadLocal<String?>()

private fun enableOutput() {
    outputEnabled = true
    fun outStream(key: String) = PrintStream(object : OutputStream() {
        private val line = StringBuilder()
        override fun write(b: Int) {
            val k = outputToKey.get() ?: key
            getOutputPrintStream(k).write(b)
            if (b == '\n'.toInt()) {
                outputs[k] = line.toString()
                line.clear()
            } else {
                line.append(b.toChar())
            }
        }

        override fun flush() {
            val k = outputToKey.get() ?: key
            getOutputPrintStream(k).flush()
        }
    })
    System.setOut(outStream("sysout"))
    System.setErr(outStream("syserr"))
}

private fun disableOutput() {
    System.setErr(sysErr)
    System.setOut(sysOut)
    outputEnabled = false
}

fun output(key: String, line: String) {
    outputsByThread[Thread.currentThread()] = key
    if (outputEnabled) {
        outputs[key] = line
    } else {
        println("$key: $line")
    }
    getOutputPrintStream(key).println(line)
}

fun getOutputPrintStream(key: String) = outputStreams.computeIfAbsent(key) {
    Files.createDirectories(Paths.get("logs"))
    PrintStream(FileOutputStream("logs/$key.log"))
}

fun <R> outputTo(key: String, cb: () -> R): R {
    outputsByThread[Thread.currentThread()] = key
    outputToKey.set(key)
    val r = cb()
    outputToKey.set(null)
    return r
}

fun closeOutput(key: String) {
    outputs.remove(key)
}