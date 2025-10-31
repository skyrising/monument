package de.skyrising.guardian.gen

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

fun <T> supplyAsync(type: TaskType, supplier: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync({ supplier() }, { threadLocalContext.get().executor.execute(it, type) })

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
    val parallelism: Int

    fun execute(command: Runnable, type: TaskType)

    @Deprecated("Use execute(command, type) instead", ReplaceWith("execute(command, TaskType.UNKNOWN)"))
    override fun execute(command: Runnable) {
        execute(command, TaskType.UNKNOWN)
    }

    fun <T : Any?> invokeAll(type: TaskType, tasks: Collection<Callable<T>>): MutableList<Future<T>> {
        val futures = ArrayList<Future<T>>(tasks.size)
        return try {
            for (t in tasks) {
                val f: RunnableFuture<T> = FutureTask(t)
                futures.add(f)
                execute(f, type)
            }
            var i = 0
            val size = futures.size
            while (i < size) {
                val f = futures[i]
                if (!f.isDone) {
                    try {
                        f.get()
                    } catch (_: CancellationException) {
                    } catch (_: ExecutionException) {
                    }
                }
                i++
            }
            futures
        } catch (t: Throwable) {
            for (f in futures) f.cancel(true)
            throw t
        }
    }
}

class ImmediateExecutorService : AbstractExecutorService(), CustomExecutorService {
    private val thread = Thread.currentThread()

    override val parallelism = 1

    @Suppress("NOTHING_TO_INLINE")
    private inline fun checkThread() {
        if (Thread.currentThread() != thread) throw IllegalStateException("Calling immediate executor from wrong thread")
    }

    override fun isTerminated() = false

    override fun execute(command: Runnable, type: TaskType) {
        checkThread()
        Task(System.nanoTime(), type, command).run()
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
    val group: ThreadGroup = Thread.currentThread().threadGroup
    val subGroupCount = AtomicInteger()

    override fun newThread(r: Runnable): Thread {
        val newGroup = ThreadGroup(group, "custom-${subGroupCount.incrementAndGet()}")
        return Thread(newGroup, r, newGroup.name + "-main")
    }
}

enum class TaskType {
    DOWNLOAD,
    READ_MERGE_INPUT_JAR,
    MERGE_JAR,
    EXTRACT_RESOURCE,
    READ_MAPPINGS,
    REMAP,
    DECOMPILE,
    POST_PROCESS,
    UNKNOWN,
    GIT,
}

data class Task(val time: Long, val type: TaskType, private val runnable: Runnable): Runnable, Comparable<Task> {
    override fun compareTo(other: Task): Int {
        if (type != other.type) return type.compareTo(other.type)
        if (time != other.time) return time.compareTo(other.time)
        if (runnable != other.runnable) return -1
        return 0
    }

    override fun run() {
        TraceEvent.Begin(name = type.name, cat = "task,${type.name}", ts = time / 1e3)
        try {
            runnable.run()
        } finally {
            TraceEvent.End(name = type.name)
        }
    }
}

class CustomThreadPoolExecutor(override val parallelism: Int, initialDecompileParallelism: Int, threadFactory: ThreadFactory) : AbstractExecutorService(), CustomExecutorService {
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
    private val semaphores = Array(TaskType.entries.size) { Semaphore(maxTasks(TaskType.entries[it])) }
    private val decompileSemaphore = Semaphore(decompileParallelism)
    private val workers = Array(parallelism) { Worker(it, threadFactory) }
    private val runnableTasks = Array(TaskType.entries.size) { PriorityBlockingQueue<Task>() }
    private val scheduledTasks = ConcurrentHashMap.newKeySet<Task>()
    private val runningTasks = ConcurrentHashMap.newKeySet<Task>()
    private val terminated = Object()
    private val runnableTasksChanged = Object()
    @Volatile
    private var running = true
    private val runningWorkers = AtomicInteger()

    private fun maxTasks(type: TaskType): Int {
        return when (type) {
            TaskType.DECOMPILE -> decompileParallelism
            TaskType.UNKNOWN -> 1
            else -> parallelism - 1
        }
    }

    private inner class Worker(val id: Int, private val threadFactory: ThreadFactory): Runnable {
        var thread: Thread? = null

        fun start() {
            if (thread != null) return
            thread = threadFactory.newThread(this)
            thread!!.start()
            runningWorkers.incrementAndGet()
        }

        override fun run() {
            while (running) {
                val task = try {
                    waitForNextTask()
                } catch (_: InterruptedException) {
                    continue
                }
                try {
                    if (DEBUG) output("scheduler", "Running $task in worker $id")
                    task.run()
                } finally {
                    semaphores[task.type.ordinal].release()
                    runningTasks.remove(task)
                    synchronized(runnableTasksChanged) {
                        runnableTasksChanged.notify()
                    }
                    if (!running && scheduledTasks.isEmpty() && runningTasks.isEmpty()) {
                        synchronized(terminated) {
                            terminated.notifyAll()
                        }
                    }
                }
            }
        }

        @Throws(InterruptedException::class)
        private fun waitForNextTask(): Task {
            if (DEBUG) output("scheduler", "Worker $id is waiting for task")
            while (true) {
                for (type in TaskType.entries) {
                    try {
                        val semaphore = semaphores[type.ordinal]
                        if (semaphore.tryAcquire()) {
                            val task = runnableTasks[type.ordinal].poll()
                            if (task != null) {
                                runningTasks.add(task)
                                scheduledTasks.remove(task)
                                return task
                            } else {
                                semaphore.release()
                            }
                        }
                    } catch (e: InterruptedException) {
                        throw e
                    }
                }
                synchronized(runnableTasksChanged) {
                    runnableTasksChanged.wait()
                }
            }
        }
    }

    override fun execute(command: Runnable, type: TaskType) {
        schedule(Task(System.nanoTime(), type, command))
    }

    private fun schedule(task: Task) {
        if (!running) throw RejectedExecutionException()
        if (DEBUG || task.type == TaskType.UNKNOWN) output("scheduler", "Scheduling $task")
        scheduledTasks.add(task)
        runnableTasks[task.type.ordinal].add(task)
        val neededWorkers = runningTasks.size + scheduledTasks.size
        val runningWorkers = runningWorkers.get()
        if (semaphores[task.type.ordinal].availablePermits() > 0 && neededWorkers > runningWorkers && runningWorkers < parallelism && workers[runningWorkers].thread == null) {
            workers[runningWorkers].start()
        } else {
            synchronized(runnableTasksChanged) {
                runnableTasksChanged.notify()
            }
        }
    }

    override fun shutdown() {
        running = false
    }

    override fun shutdownNow(): List<Runnable> {
        running = false
        for (worker in workers) {
            worker.thread?.interrupt()
        }
        return scheduledTasks.toList()
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
            synchronized(terminated) {
                terminated.wait(unit.toMillis(timeout), (unit.toNanos(timeout) % 1000000L).toInt())
            }
            true
        } catch (_: InterruptedException) {
            false
        }
    }
}