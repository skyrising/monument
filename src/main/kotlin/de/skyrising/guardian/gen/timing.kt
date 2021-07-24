package de.skyrising.guardian.gen

import jdk.jfr.Category
import jdk.jfr.Event
import jdk.jfr.Label
import jdk.jfr.Name
import java.io.PrintStream
import java.util.*
import java.util.concurrent.CompletableFuture

private val timers = mutableListOf<Timer>()

fun <T> time(version: String, id: String, future: CompletableFuture<T>): CompletableFuture<T> {
    val timer = Timer(version, id)
    return future.thenApply {
        timer.close()
        it
    }
}

class Timer(val version: String, val id: String) : AutoCloseable {
    var start = System.nanoTime()
    var end = 0L
    private val event = TimerEvent(version, id)

    init {
        if (event.isEnabled) event.begin()
    }

    override fun close() {
        if (event.shouldCommit()) event.commit()
        end = System.nanoTime()
        timers.add(this)
    }
}

@Name("monument.Timer")
@Label("Timer")
@Category("Monument")
data class TimerEvent(
    @Label("Minecraft Version") val version: String,
    @Label("Task") val id: String
) : Event()

fun dumpTimers(out: PrintStream) {
    var earliestStart = Long.MAX_VALUE
    val byVersion = mutableMapOf<String, MutableList<Timer>>()
    val latestOfVersion = mutableMapOf<String, Timer>()
    for (timer in timers) {
        earliestStart = minOf(earliestStart, timer.start)
        byVersion.computeIfAbsent(timer.version) { mutableListOf() }.add(timer)
        latestOfVersion.compute(timer.version) { _, other ->
            if (other == null || other.end < timer.end) timer
            else other
        }
    }
    val sortedVersions = latestOfVersion.values.sortedBy { it.end }.map { it.version }
    for (version in sortedVersions) {
        out.print(version)
        val timers = byVersion[version]!!
        if (timers.size != 1) {
            out.println()
        } else {
            out.print(' ')
        }
        timers.sortBy { it.start }
        for (timer in timers) {
            out.printf(Locale.ROOT, "%s: %.3fs-%.3fs (%.3fms)\n",
                timer.id,
                (timer.start - earliestStart) / 1e9,
                (timer.end - earliestStart) / 1e9,
                (timer.end - timer.start) / 1e6
            )
        }
    }
    out.flush()
}