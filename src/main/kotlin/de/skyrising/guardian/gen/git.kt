package de.skyrising.guardian.gen

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture

data class CommitTemplate(val version: VersionInfo, val source: Path)

fun createBranch(branch: String, config: GitConfig, history: List<CommitTemplate>, recommitFrom: String?) {
    if (Files.exists(TEMP_REPO_DIR)) rmrf(TEMP_REPO_DIR)
    Files.createDirectories(TEMP_REPO_DIR)
    var todo = history
    val recommitFull = recommitFrom == ":base"
    if (Files.exists(REPO_DIR.resolve("refs/heads/$branch")) && !recommitFull) {
        git(TEMP_REPO_DIR.parent, "clone", REPO_DIR.toAbsolutePath().toString(), TEMP_REPO_DIR.fileName.toString()).join()
        git(TEMP_REPO_DIR, "checkout", branch).join()
        val prevHistory = gitLines(TEMP_REPO_DIR, "log", "--format=%s", "--reverse").join()
        val recommitIndex = if (recommitFrom != null) history.indexOfFirst { it.version.id == recommitFrom } else history.size
        val commonPrefix = minOf(findCommonPrefix(prevHistory, history.map { it.version.id }), recommitIndex)
        if (commonPrefix <= 0) {
            rmrf(TEMP_REPO_DIR)
            return createBranch(branch, config, history, ":base")
        }
        val commits = gitLines(TEMP_REPO_DIR, "log", "--format=%H", "--reverse").join()
        todo = history.subList(commonPrefix, history.size)
        git(TEMP_REPO_DIR, "reset", "--hard", commits[commonPrefix - 1]).join()
        Files.list(TEMP_REPO_DIR).forEach {
            if (!it.endsWith(".git")) rmrf(it)
        }
    } else {
        git(TEMP_REPO_DIR, "init").join()
        git(TEMP_REPO_DIR, "remote", "add", "origin", REPO_DIR.toAbsolutePath().toString()).join()
        git(TEMP_REPO_DIR, "checkout", "-b", branch).join()
    }
    for (commit in todo) {
        Timer(commit.version.id, "commit").use {
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
            git(TEMP_REPO_DIR, "tag", "--force", tag).join()
            // for (repoFile in destFiles) rmrf(repoFile)
            for ((a, b) in destFiles) Files.move(a, b)
        }
    }
    git(TEMP_REPO_DIR, "push", "--force", "--set-upstream", "origin", branch).join()
    git(TEMP_REPO_DIR, "push", "--tags", "--force").join()
    val gcLock = TEMP_REPO_DIR.resolve(".git/gc.log.lock")
    while (Files.exists(gcLock)) {
        Thread.sleep(100)
    }
    rmrf(TEMP_REPO_DIR)
}

fun git(dir: Path, vararg args: String): CompletableFuture<Int> {
    val command = mutableListOf("git")
    for (arg in args) command += arg
    println("# " + command.joinToString(" "))
    val pb = ProcessBuilder(command)
    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
    pb.redirectError(ProcessBuilder.Redirect.INHERIT)
    pb.directory(dir.toFile())
    val p = pb.start()
    return CompletableFuture.supplyAsync { p.waitFor() }
}

fun gitLines(dir: Path, vararg args: String): CompletableFuture<List<String>> {
    val command = mutableListOf("git")
    for (arg in args) command += arg
    println("# " + command.joinToString(" "))
    val pb = ProcessBuilder(command)
    pb.redirectOutput(ProcessBuilder.Redirect.PIPE)
    pb.redirectError(ProcessBuilder.Redirect.PIPE)
    pb.directory(dir.toFile())
    val p = pb.start()
    val lines = mutableListOf<String>()
    p.inputStream.reader().forEachLine { lines.add(it) }
    return CompletableFuture.supplyAsync {
        p.waitFor()
        lines
    }
}

fun gitCommit(dir: Path, date: ZonedDateTime, config: GitConfig, vararg args: String): CompletableFuture<Int> {
    val command = mutableListOf("git", "commit", "--no-gpg-sign")
    for (arg in args) command += arg
    println("# " + command.joinToString(" "))
    val pb = ProcessBuilder(command)
    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
    pb.redirectError(ProcessBuilder.Redirect.INHERIT)
    pb.directory(dir.toFile())
    val env = pb.environment()
    val dateString = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(date)
    env["GIT_AUTHOR_DATE"] = dateString
    env["GIT_AUTHOR_NAME"] = config.author.name
    env["GIT_AUTHOR_EMAIL"] = config.author.email
    env["GIT_COMMITTER_DATE"] = dateString
    env["GIT_COMMITTER_NAME"] = config.committer.name
    env["GIT_COMMITTER_EMAIL"] = config.committer.email
    val p = pb.start()
    return CompletableFuture.supplyAsync { p.waitFor() }
}

fun getMonumentVersion(): String {
    return try {
        val path = getMonumentClassRoot() ?: Paths.get(".")
        val pb = ProcessBuilder(listOf("git", "describe", "--always", "--tags", "--dirty"))
        pb.directory(path.toFile())
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE)
        pb.redirectError(ProcessBuilder.Redirect.PIPE)
        val p = pb.start()
        val code = p.waitFor()
        if (code != 0) return "unknown"
        p.inputStream.bufferedReader().readText().trim()
    } catch (e: Exception) {
        e.printStackTrace()
        "unknown"
    }
}

fun <T> findCommonPrefix(a: List<T>, b: List<T>): Int {
    val limit = minOf(a.size, b.size)
    for (i in 0 until limit) {
        if (a[i] != b[i]) return i
    }
    return limit
}