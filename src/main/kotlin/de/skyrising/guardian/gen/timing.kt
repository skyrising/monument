package de.skyrising.guardian.gen

import com.sun.management.GarbageCollectionNotificationInfo
import jdk.jfr.Category
import jdk.jfr.Event
import jdk.jfr.Label
import jdk.jfr.Name
import java.io.PrintStream
import java.io.Writer
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.zip.GZIPOutputStream
import javax.management.NotificationEmitter
import javax.management.openmbean.CompositeData

private val timers = mutableListOf<ManualTimer>()

open class ManualTimer(val version: String, val id: String, val args: Map<String, Any?> = mapOf()) {
    var start = 0L
    var end = 0L
    protected val tid = gettid()
    protected val event = TimerEvent(version, id)

    fun start() {
        start = System.nanoTime()
        if (event.isEnabled) event.begin()
        TraceEvent.Begin(name = id, cat = "timer,$version", ts = start / 1e3, args = args, tid = tid)
    }

    fun stop() {
        end = System.nanoTime()
        if (event.shouldCommit()) event.commit()
        TraceEvent.End(name = id, ts = end / 1e3, tid = tid)
        timers.add(this)
    }
}

class Timer(version: String, id: String, args: Map<String, Any?> = mapOf()) : ManualTimer(version, id, args), AutoCloseable {
    init {
        start()
    }

    override fun close() {
        stop()
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
    val byVersion = mutableMapOf<String, MutableList<ManualTimer>>()
    val latestOfVersion = mutableMapOf<String, ManualTimer>()
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

object TraceEvents {
    private const val GZ = true
    private lateinit var writer: Writer
    private var eventCount = 0
    private val threadSeen = ThreadLocal.withInitial { false }
    private var active = false

    @Synchronized
    fun start(path: Path) {
        writer = if (GZ) GZIPOutputStream(Files.newOutputStream(path.resolveSibling(path.fileName.toString() + ".gz"))).bufferedWriter() else Files.newBufferedWriter(path)
        writer.write("[\n")
        active = true
        TraceEvent.Metadata("process_name", mapOf("name" to "monument"), tid = 0)
        try {
            Class.forName("com.sun.management.GcInfo")
            ManagementFactory.getGarbageCollectorMXBeans().forEach { gc ->
                (gc as NotificationEmitter).addNotificationListener({ notification, _ ->
                    if (notification.type != GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION) return@addNotificationListener
                    val info = GarbageCollectionNotificationInfo.from(notification.userData as CompositeData)
                    val gcInfo = info.gcInfo
                    val usedBefore = gcInfo.memoryUsageBeforeGc.values.sumOf { it.used }
                    val usedAfter = gcInfo.memoryUsageAfterGc.values.sumOf { it.used }
                    TraceEvent.Instant(info.gcName, cat = "gc", s = 'g', args = mapOf(
                        "name" to info.gcName,
                        "cause" to info.gcCause,
                        "action" to info.gcAction,
                        "used_before" to usedBefore,
                        "used_after" to usedAfter,
                        "duration" to gcInfo.duration
                    ))
                    TraceEvent.Counter("Heap", ts = System.nanoTime() / 1e3 - gcInfo.duration * 1e3, args = gcInfo.memoryUsageBeforeGc.mapValues { (_, v) -> v.used })
                    TraceEvent.Counter("Heap", args = gcInfo.memoryUsageAfterGc.mapValues { (_, v) -> v.used })
                }, null, null)
            }
        } catch (_: ClassNotFoundException) {}
    }

    @Synchronized
    fun emit(event: TraceEvent) {
        if (!active) return
        if (!threadSeen.get()) {
            threadSeen.set(true)
            TraceEvent.Metadata("thread_name", mapOf("name" to Thread.currentThread().name))
        }
        if (eventCount++ > 0) writer.write(",\n")
        GSON.toJson(event, writer)
        writer.flush()
    }

    @Synchronized
    fun stop() {
        active = false
        writer.write("]\n")
        writer.close()
    }
}

val PID = ProcessHandle.current().pid()

fun gettid() = Thread.currentThread().threadId().toInt()

sealed interface TraceEvent {
    val ph: Char
    data class Begin(val name: String, val cat: String = "", val ts: Double = System.nanoTime() / 1e3, val pid: Long = PID, val tid: Int = gettid(), val args: Map<String, Any?> = mapOf()) : TraceEvent {
        override val ph = 'B'
        init { TraceEvents.emit(this) }
    }
    data class End(val name: String, val ts: Double = System.nanoTime() / 1e3, val pid: Long = PID, val tid: Int = gettid()) : TraceEvent {
        override val ph = 'E'
        init { TraceEvents.emit(this) }
    }
    data class Metadata(val name: String, val args: Map<String, Any?>, val cat: String = "__metadata", val ts: Double = System.nanoTime() / 1e3, val pid: Long = PID, val tid: Int = gettid()) : TraceEvent {
        override val ph = 'M'
        init { TraceEvents.emit(this) }
    }
    data class Instant(val name: String, val cat: String = "", val s: Char = 't', val ts: Double = System.nanoTime() / 1e3, val pid: Long = PID, val tid: Int = gettid(), val args: Map<String, Any?> = mapOf()) : TraceEvent {
        override val ph = 'i'
        init { TraceEvents.emit(this) }
    }
    data class Counter(val name: String, val cat: String = "", val ts: Double = System.nanoTime() / 1e3, val pid: Long = PID, val args: Map<String, Any?> = mapOf()) : TraceEvent {
        override val ph = 'C'
        init { TraceEvents.emit(this) }
    }
}