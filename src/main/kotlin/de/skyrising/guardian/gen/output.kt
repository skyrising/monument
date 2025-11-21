package de.skyrising.guardian.gen

import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

val outputs = ConcurrentHashMap<String, String>()
private val outputStreams = ConcurrentHashMap<String, PrintStream>()
val persistentOutputs = listOf("sysout", "syserr", "progress")
val outputsByThread = linkedMapOf<Thread, String>()
private var outputEnabled = false
val sysOut: PrintStream = System.out
private val sysErr = System.err
private val outputToKey = ConcurrentHashMap<ThreadGroup, String>()
private val outputListener = ConcurrentHashMap<ThreadGroup, ((String) -> Unit)>()

fun getOutputKey(thread: Thread = Thread.currentThread()): String {
    return outputToKey[thread.threadGroup] ?: thread.threadGroup.name
}

fun enableOutput() {
    outputEnabled = true
    Thread.currentThread().setUncaughtExceptionHandler { _, e ->
        disableOutput()
        e.printStackTrace()
    }
    fun outStream() = PrintStream(object : OutputStream() {
        private val line = StringBuilder()
        override fun write(b: Int) {
            val k = getOutputKey()
            getOutputPrintStream(k).write(b)
            if (b == '\n'.code) {
                val str = line.toString()
                outputListener[Thread.currentThread().threadGroup]?.invoke(str)
                outputs[k] = str
                line.clear()
            } else {
                line.append(b.toChar())
            }
        }

        override fun flush() {
            val k = getOutputKey()
            getOutputPrintStream(k).flush()
        }
    }, false, StandardCharsets.UTF_8)
    System.setOut(outStream())
    System.setErr(outStream())
}

fun disableOutput() {
    System.setErr(sysErr)
    System.setOut(sysOut)
    outputEnabled = false
}

fun output(key: String, line: String) {
    synchronized(outputsByThread) {
        outputsByThread[Thread.currentThread()] = key
    }
    if (outputEnabled) {
        outputs[key] = line
    } else {
        println("$key: $line")
    }
    getOutputPrintStream(key).println(line)
}

fun getOutputPrintStream(key: String) = outputStreams.computeIfAbsent(key) {
    PrintStream(FileOutputStream("logs/$key.log").buffered()).also { it.println(Thread.currentThread().threadGroup.name) }
}

fun <R> outputTo(key: String, cb: () -> R): R {
    synchronized(outputsByThread) {
        outputsByThread[Thread.currentThread()] = key
    }
    val group = Thread.currentThread().threadGroup
    outputToKey[group] = key
    try {
        return cb()
    } catch (t: Throwable) {
        t.printStackTrace(getOutputPrintStream(key))
        throw RuntimeException(t)
    } finally {
        outputToKey.remove(group)
    }
}

fun <R> listen(listener: (String) -> Unit, cb: () -> R): R {
    val group = Thread.currentThread().threadGroup
    outputListener[group] = listener
    try {
        return cb()
    } finally {
        outputListener.remove(group)
    }
}

fun closeOutput(key: String) {
    outputs.remove(key)
}

interface ProgressListener {
    fun init(totalWork: Int, title: String?)
    fun step(numDone: Int, message: String?)
}

class VersionedProgressListener(val version: String, initialTitle: String) : ProgressListener {
    var totalWork = 0
    var title = initialTitle
    override fun init(totalWork: Int, title: String?) {
        this.totalWork = totalWork
        if (title != null) this.title = title
    }

    override fun step(numDone: Int, message: String?) {
        when {
            totalWork > 0 -> output(version, String.format("%s: %.1f%%", title, 100.0 * numDone / totalWork))
            message != null -> output(version, "$title: $message")
            else -> output(version, "$title: $numDone")
        }
    }
}

data class ProgressUnit(var tasks: Int, var done: Int = 0) {
    val totalTasks: Int get() = maxOf(tasks, done) + subUnits.sumOf { it.totalTasks }
    val totalDone: Int get() = done + subUnits.sumOf { it.totalDone }

    val subUnits = mutableListOf<ProgressUnit>()

    fun subUnit(tasks: Int, done: Int = 0): ProgressUnit {
        val u = ProgressUnit(tasks, done)
        subUnits.add(u)
        return u
    }

    operator fun <T> invoke(future: CompletableFuture<T>): CompletableFuture<T> {
        tasks++
        return future.thenApply {
            done++
            it
        }
    }
}