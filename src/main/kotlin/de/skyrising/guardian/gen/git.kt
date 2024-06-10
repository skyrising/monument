package de.skyrising.guardian.gen

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture

data class CommitTemplate(val version: VersionInfo, val source: Path, override val parents: List<CommitTemplate>) : DagNode<CommitTemplate> {
    override fun toString() = version.toString()
    override fun hashCode() = version.hashCode()
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CommitTemplate) return false
        return version == other.version && source == other.source && parents == other.parents
    }
}

fun createBranch(branch: String, config: GitConfig, history: List<CommitTemplate>, recommitFrom: String?) {
    if (Files.exists(TEMP_REPO_DIR)) rmrf(TEMP_REPO_DIR)
    Files.createDirectories(TEMP_REPO_DIR)
    val recommitIndex = if (recommitFrom == ":base") 0 else history.indexOfFirst { it.version.id == recommitFrom }
    val recommitFull = recommitFrom == ":base"
    val trees = linkedMapOf<String, String>()
    val commits = linkedMapOf<String, String>()
    if (Files.exists(REPO_DIR.resolve("refs/heads/$branch")) && !recommitFull) {
        immediate {
            git(
                TEMP_REPO_DIR.parent,
                "clone",
                REPO_DIR.toAbsolutePath().toString(),
                TEMP_REPO_DIR.fileName.toString()
            )
        }
        immediate { git(TEMP_REPO_DIR, "checkout", branch) }
        for (line in immediate { gitLines(TEMP_REPO_DIR, "log", "--format=%H%T%s", "--reverse") }) {
            val message = line.substring(80)
            commits[message] = line.substring(0, 40)
            trees[message] = line.substring(40, 80)
        }
        Files.list(TEMP_REPO_DIR).forEach {
            if (!it.endsWith(".git")) rmrf(it)
        }
    } else {
        immediate { git(TEMP_REPO_DIR, "init") }
        immediate { git(TEMP_REPO_DIR, "remote", "add", "origin", REPO_DIR.toAbsolutePath().toString()) }
        immediate { git(TEMP_REPO_DIR, "checkout", "-b", branch) }
    }
    if (recommitIndex >= 0) {
        for (i in recommitIndex until history.size) {
            val id = history[i].version.id
            trees.remove(id)
            commits.remove(id)
        }
    }

    for (commit in history) {
        val id = commit.version.id
        if (id in commits) continue
        Timer(id, "commit").use {
            val destFiles = mutableMapOf<Path, Path>()
            Files.list(commit.source).forEach {
                val dest = TEMP_REPO_DIR.resolve(commit.source.relativize(it))
                destFiles[dest] = it
                Files.move(it, dest)
            }
            println("$id: ${commit.source.toAbsolutePath()}")
            immediate { git(TEMP_REPO_DIR, "add", ".") }
            val tree = immediate { gitWriteTree(TEMP_REPO_DIR) }
            trees[id] = tree
            val commitHash = immediate {
                gitCommitTree(TEMP_REPO_DIR, tree, commit.version.releaseTime, config, commit.parents.map {
                    commits[it.version.id] ?: throw IllegalStateException("Cannot find previous commit for $it")
                }, id)
            }
            commits[id] = commitHash
            val tag = if (branch == "master") id else "$branch-$id"
            immediate { git(TEMP_REPO_DIR, "tag", "--force", tag, commitHash) }
            // for (repoFile in destFiles) rmrf(repoFile)
            for ((a, b) in destFiles) Files.move(a, b)
        }
    }
    immediate { git(TEMP_REPO_DIR, "reset", "--hard", commits[history.last().version.id]!!) }
    immediate { git(TEMP_REPO_DIR, "push", "--force", "--set-upstream", "origin", branch) }
    immediate { git(TEMP_REPO_DIR, "push", "--tags", "--force") }
    val gcLock = TEMP_REPO_DIR.resolve(".git/gc.log.lock")
    while (Files.exists(gcLock)) {
        Thread.sleep(100)
    }
    //rmrf(TEMP_REPO_DIR)
}

fun git(dir: Path, vararg args: String) = git(dir, null, *args)

fun git(dir: Path, env: Map<String, String>?, vararg args: String): CompletableFuture<Int> {
    val command = mutableListOf("git")
    for (arg in args) command += arg
    return run(dir, env, command).thenApply {
        it.second.inputStream.copyTo(System.out)
        it.first
    }
}

fun run(dir: Path, env: Map<String, String>?, command: List<String>): CompletableFuture<Pair<Int, Process>> {
    println("# " + command.joinToString(" "))
    val pb = ProcessBuilder(command)
    if (env != null) pb.environment().putAll(env)
    pb.redirectOutput(ProcessBuilder.Redirect.PIPE)
    pb.redirectError(ProcessBuilder.Redirect.INHERIT)
    pb.directory(dir.toFile())
    val p = pb.start()
    return supplyAsync(TaskType.GIT) {
        Timer("", "${command[0]} ${command[1]}", mapOf("command" to command)).use { Pair(p.waitFor(), p) }
    }
}

fun gitLines(dir: Path, vararg args: String): CompletableFuture<List<String>> {
    val command = mutableListOf("git")
    for (arg in args) command += arg
    println("# " + command.joinToString(" "))
    val pb = ProcessBuilder(command)
    pb.redirectOutput(ProcessBuilder.Redirect.PIPE)
    pb.redirectError(ProcessBuilder.Redirect.INHERIT)
    pb.directory(dir.toFile())
    val p = pb.start()
    val lines = mutableListOf<String>()
    p.inputStream.reader().forEachLine { lines.add(it) }
    return supplyAsync(TaskType.GIT) {
        Timer("", "${command[0]} ${command[1]}", mapOf("command" to command)).use {
            val code = p.waitFor()
            if (code != 0) throw RuntimeException("Git call failed: $code")
            lines
        }
    }
}

fun getCommitEnv(date: ZonedDateTime, config: GitConfig): Map<String, String> {
    val dateString = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(date)
    return mapOf(
        "GIT_AUTHOR_DATE" to dateString,
        "GIT_AUTHOR_NAME" to config.author.name,
        "GIT_AUTHOR_EMAIL" to config.author.email,
        "GIT_COMMITTER_DATE" to dateString,
        "GIT_COMMITTER_NAME" to config.committer.name,
        "GIT_COMMITTER_EMAIL" to config.committer.email
    )
}

fun gitWriteTree(dir: Path): CompletableFuture<String> = run(dir, mapOf(), listOf("git", "write-tree")).thenApply {
    if (it.first != 0) throw RuntimeException("Git call failed: ${it.first}")
    val result = it.second.inputStream.bufferedReader().readText().trim()
    println(result)
    result
}

fun gitCommitTree(
    dir: Path,
    tree: String,
    date: ZonedDateTime,
    config: GitConfig,
    parents: List<String>,
    message: String
): CompletableFuture<String> {
    val command = mutableListOf("git", "commit-tree", tree, "-m")
    command += message
    for (parent in parents) {
        command += "-p"
        command += parent
    }
    return run(dir, getCommitEnv(date, config), command).thenApply {
        if (it.first != 0) throw RuntimeException("Git call failed: ${it.first}")
        val result = it.second.inputStream.bufferedReader().readText().trim()
        println(result)
        result
    }
}

fun getMonumentVersion(): String {
    return try {
        var path = getMonumentClassRoot() ?: Paths.get(".")
        if (!Files.isDirectory(path)) path = path.parent
        val (code, p) = immediate { run(path, null, listOf("git", "describe", "--always", "--tags", "--dirty")) }
        if (code != 0) return "unknown"
        p.inputStream.bufferedReader().readText().trim()
    } catch (e: Exception) {
        e.printStackTrace()
        "unknown"
    }
}