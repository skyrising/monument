package de.skyrising.guardian.gen

import java.nio.file.Files
import java.nio.file.Path
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture

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
    val gcLock = TEMP_REPO_DIR.resolve(".git/gc.log.lock")
    while (Files.exists(gcLock)) {
        Thread.sleep(100)
    }
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

fun gitCommit(dir: Path, date: ZonedDateTime, config: GitConfig, vararg args: String): CompletableFuture<Int> {
    val command = mutableListOf("git", "commit", "--no-gpg-sign")
    for (arg in args) command += arg
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