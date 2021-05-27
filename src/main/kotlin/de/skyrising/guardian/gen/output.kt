package de.skyrising.guardian.gen

import cuchaz.enigma.ProgressListener
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

val outputs = ConcurrentHashMap<String, String>()
private val outputStreams = ConcurrentHashMap<String, PrintStream>()
val persistentOutputs = listOf("sysout", "syserr")
val outputsByThread = linkedMapOf<Thread, String>()
private var outputEnabled = false
val sysOut = System.out
private val sysErr = System.err
private val outputToKey = ThreadLocal<String?>()

fun enableOutput() {
    outputEnabled = true
    Thread.currentThread().setUncaughtExceptionHandler { t, e ->
        disableOutput()
        e.printStackTrace()
    }
    fun outStream(key: String) = PrintStream(object : OutputStream() {
        private val line = StringBuilder()
        override fun write(b: Int) {
            val k = outputToKey.get() ?: Thread.currentThread().name
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
    Files.createDirectories(Paths.get("logs"))
    PrintStream(FileOutputStream("logs/$key.log"))
}

fun <R> outputTo(key: String, cb: () -> R): R {
    synchronized(outputsByThread) {
        outputsByThread[Thread.currentThread()] = key
    }
    outputToKey.set(key)
    try {
        return cb()
    } catch (t: Throwable) {
        t.printStackTrace(getOutputPrintStream(key))
        throw RuntimeException(t)
    } finally {
        outputToKey.set(null)
    }
}

fun closeOutput(key: String) {
    outputs.remove(key)
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