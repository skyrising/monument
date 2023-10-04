package de.skyrising.guardian.gen

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.streams.toList

fun <T> supplyAsync(supplier: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync({ supplier() }, { threadLocalContext.get().executor.execute(it) })
fun <T> supplyAsyncDecompile(supplier: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync({ supplier() }, { threadLocalContext.get().executor.executeDecompile(it) })

inline fun <T> immediate(futured: () -> CompletableFuture<T>): T {
    val ctx = threadLocalContext.get()
    try {
        threadLocalContext.set(Context(ImmediateExecutorService()))
        val fut = futured()
        if (!fut.isDone) throw IllegalStateException("Future was not executed immediately")
        return fut.get()
    } finally {
        threadLocalContext.set(ctx)
    }
}

interface CustomExecutorService : ExecutorService {
    fun executeDecompile(command: Runnable)
}

class ImmediateExecutorService : AbstractExecutorService(), CustomExecutorService {
    private val thread = Thread.currentThread()

    @Suppress("NOTHING_TO_INLINE")
    private inline fun checkThread() {
        if (Thread.currentThread() != thread) throw IllegalStateException("Calling immediate executor from wrong thread")
    }

    override fun isTerminated() = false

    override fun execute(command: Runnable) {
        checkThread()
        command.run()
    }

    override fun executeDecompile(command: Runnable) {
        execute(command)
    }

    override fun shutdown() {
        checkThread()
    }

    override fun shutdownNow(): List<Runnable> {
        checkThread()
        return emptyList()
    }

    override fun isShutdown(): Boolean {
        checkThread()
        return false
    }

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
        checkThread()
        return true
    }
}

fun <K, V> deduplicate(map: MutableMap<K, CompletableFuture<V>>, key: K, future: CompletableFuture<V>): CompletableFuture<V> {
    val modifiedFuture = future.thenApply {
        map.remove(key)
        it
    }
    map[key] = modifiedFuture
    return modifiedFuture
}

object CustomThreadFactory : ThreadFactory {
    val group = Thread.currentThread().threadGroup
    val subGroupCount = AtomicInteger()

    override fun newThread(r: Runnable): Thread {
        val newGroup = ThreadGroup(group, "custom-${subGroupCount.incrementAndGet()}")
        return Thread(newGroup, r, newGroup.name + "-main")
    }
}

class CustomThreadPoolExecutor(val parallelism: Int, initialDecompileParallelism: Int, threadFactory: ThreadFactory) : AbstractExecutorService(), CustomExecutorService {
    constructor(parallelism: Int) : this(parallelism, maxOf(parallelism - 2, 1), CustomThreadFactory)

    companion object {
        val DEBUG = System.getProperty("monument.scheduler.debug").toBoolean()
    }

    var decompileParallelism = initialDecompileParallelism
        set(value) {
            if (value < 1) throw IllegalArgumentException()
            val diff = value - decompileParallelism
            if (diff == 0) return
            if (diff > 0) decompileSemaphore.release(diff)
            else decompileSemaphore.acquireUninterruptibly(-diff)
            println("Decompile parallelism is now $value")
            field = value
        }
    private val decompileSemaphore = Semaphore(decompileParallelism)
    private val workers = Array(parallelism) { Worker(it, threadFactory) }
    private val runnableTasks = PriorityBlockingQueue<Task>()
    private val scheduledTasks = ConcurrentHashMap.newKeySet<Task>()
    private val runningTasks = ConcurrentHashMap.newKeySet<Task>()
    private val terminated = ReentrantLock().newCondition()
    @Volatile
    private var running = true

    init {
        for (worker in workers) worker.thread.start()
    }

    private inner class Worker(val id: Int, threadFactory: ThreadFactory): Runnable {
        val thread: Thread = threadFactory.newThread(this)

        override fun run() {
            while (running) {
                val task = try {
                    waitForNextTask()
                } catch (e: InterruptedException) {
                    continue
                }
                try {
                    if (DEBUG) output("scheduler", "Running $task in worker $id")
                    task.runnable.run()
                } finally {
                    if (task.decompile) decompileSemaphore.release()
                    runningTasks.remove(task)
                    if (!running && scheduledTasks.isEmpty() && runningTasks.isEmpty()) {
                        terminated.signalAll()
                    }
                }
            }
        }

        @Throws(InterruptedException::class)
        private fun waitForNextTask(): Task {
            if (DEBUG) output("scheduler", "Worker $id is waiting for task")
            val task = runnableTasks.take()
            try {
                if (task.decompile && !decompileSemaphore.tryAcquire()) {
                    if (DEBUG) output("scheduler", "Worker $id is awaiting semaphore for $task, queue length: ${decompileSemaphore.queueLength}")
                    decompileSemaphore.acquire()
                }
            } catch (e: InterruptedException) {
                runnableTasks.add(task)
                throw e
            }
            runningTasks.add(task)
            scheduledTasks.remove(task)
            return task
        }
    }

    data class Task(val time: Long, val decompile: Boolean, val runnable: Runnable): Comparable<Task> {
        override fun compareTo(other: Task): Int {
            if (decompile != other.decompile) return decompile.compareTo(other.decompile)
            if (time != other.time) return time.compareTo(other.time)
            if (runnable != other.runnable) return -1
            return 0
        }
    }

    override fun execute(command: Runnable) {
        schedule(Task(System.nanoTime(), false, command))
    }

    override fun executeDecompile(command: Runnable) {
        schedule(Task(System.nanoTime(), true, command))
    }

    private fun schedule(task: Task) {
        if (!running) throw RejectedExecutionException()
        if (DEBUG) output("scheduler", "Scheduling $task")
        scheduledTasks.add(task)
        runnableTasks.add(task)
    }

    override fun shutdown() {
        running = false
    }

    override fun shutdownNow(): List<Runnable> {
        running = false
        for (worker in workers) {
            worker.thread.interrupt()
        }
        return scheduledTasks.stream().map { it.runnable }.toList()
    }

    override fun isShutdown(): Boolean {
        return !running
    }

    override fun isTerminated(): Boolean {
        return !running && runningTasks.isEmpty() && scheduledTasks.isEmpty()
    }

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
        if (isTerminated) return true
        return try {
            terminated.await(timeout, unit)
        } catch (e: InterruptedException) {
            false
        }
    }
}