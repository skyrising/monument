package de.skyrising.guardian.gen

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

fun <T> supplyAsync(supplier: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync({ supplier() }, { threadLocalContext.get().executor.submit(it) })

inline fun <T> immediate(futured: () -> CompletableFuture<T>): T {
    val ctx = threadLocalContext.get()
    threadLocalContext.set(Context(ImmediateExecutorService()))
    val fut = futured()
    if (!fut.isDone) throw IllegalStateException("Future was not executed immediately")
    threadLocalContext.set(ctx)
    return fut.get()
}

class ImmediateExecutorService : AbstractExecutorService() {
    private val thread = Thread.currentThread()

    private inline fun checkThread() {
        if (Thread.currentThread() != thread) throw IllegalStateException("Calling immediate executor from wrong thread")
    }

    override fun isTerminated() = false

    override fun execute(command: Runnable) {
        checkThread()
        command.run()
    }

    override fun shutdown() {
        checkThread()
    }

    override fun shutdownNow(): MutableList<Runnable> {
        checkThread()
        return mutableListOf()
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