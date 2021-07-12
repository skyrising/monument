package de.skyrising.guardian.gen

import org.benf.cfr.reader.api.CfrDriver
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

interface Decompiler {
    val name: String
    val maxParallelism get() = Integer.MAX_VALUE
    fun decompile(artifact: MavenArtifact?, version: String, jar: Path, outputDir: Path, cp: List<Path>? = null): CompletableFuture<Path>

    companion object {
        val CFR = JavaDecompiler("cfr", "de.skyrising.guardian.gen.CfrDecompileTask", false /* Workaround for https://github.com/leibnitz27/cfr/issues/250 */)
        val FERNFLOWER = JavaDecompiler("fernflower", "de.skyrising.guardian.gen.FernflowerDecompileTask")
        val FORGEFLOWER = JavaDecompiler("forgeflower", "de.skyrising.guardian.gen.FernflowerDecompileTask")
        val FABRIFLOWER = JavaDecompiler("fabriflower", "de.skyrising.guardian.gen.FernflowerDecompileTask")
        val QUILTFLOWER = JavaDecompiler("quiltflower", "de.skyrising.guardian.gen.FernflowerDecompileTask")
    }
}

@Suppress("unused")
class CfrDecompileTask : DecompileTask {
    override fun decompile(version: String, jar: Path, outputDir: Path, cp: List<Path>?): Path {
        Timer(version, "decompile").use {
            val options = mutableMapOf(
                "showversion" to "false",
                "silent" to "false",
                "comments" to "false",
                "outputpath" to outputDir.toAbsolutePath().toString()
            )
            if (cp != null && cp.isNotEmpty()) options["extraclasspath"] = formatClassPath(cp)
            val driver = CfrDriver.Builder().withOptions(options).build()
            driver.analyse(listOf(jar.toAbsolutePath().toString()))
        }
        return outputDir
    }
}

abstract class CommonDecompiler(override val name: String) : Decompiler {
    override fun toString() = "CommonDecompiler($name)"
}

class JavaDecompiler(name: String, private val taskClassName: String, private val allowSharing: Boolean = true) : CommonDecompiler(name) {
    private val classLoaders = ConcurrentHashMap<URI, ClassLoader>()

    override fun decompile(
        artifact: MavenArtifact?,
        version: String,
        jar: Path,
        outputDir: Path,
        cp: List<Path>?
    ): CompletableFuture<Path> = getMavenArtifact(artifact!!).thenCompose { url: URI ->
        supplyAsyncDecompile {
            outputTo(version) {
                val classLoader = if (allowSharing) {
                    classLoaders.computeIfAbsent(url, this::createClassLoader)
                } else {
                    createClassLoader(url)
                }
                val task = Class.forName(taskClassName, true, classLoader).newInstance()
                if (task.javaClass.classLoader != classLoader) {
                    throw IllegalStateException("$taskClassName loaded by incorrect class loader: ${task.javaClass.classLoader}")
                }
                if (task !is DecompileTask) throw IllegalStateException("$task is not a DecompileTask")
                task.decompile(version, jar, outputDir, cp)
            }
        }
    }

    private fun createClassLoader(url: URI) = DecompileTaskClassLoader(URLClassLoader(arrayOf(
        getBaseUrl(JavaDecompiler::class.java),
        url.toURL()
    )))

    override fun toString() = "JavaDecompiler($name)"
}

class DecompileTaskClassLoader(parent: ClassLoader) : ClassLoader(parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (!shouldLoad(name)) {
            return super.loadClass(name, resolve)
        }
        synchronized(getClassLoadingLock(name)) {
            var c = findLoadedClass(name)
            if (c == null) {
                val path = name.replace('.', '/') + ".class"
                val stream = parent.getResourceAsStream(path)
                c = if (stream == null) {
                    super.loadClass(name, false)
                } else {
                    val bytes = stream.readBytes()
                    defineClass(name, bytes, 0, bytes.size)
                }
            }
            if (c == null) throw ClassNotFoundException(name)
            if (resolve) resolveClass(c)
            return c
        }
    }

    private fun shouldLoad(name: String): Boolean {
        if (!name.startsWith("de.skyrising.guardian.gen.")) return false
        val outerClass = name.substringBefore('$')
        if (outerClass == DecompileTask::class.java.name) return false
        return outerClass.endsWith("DecompileTask")
    }
}

private fun getBaseUrl(cls: Class<*>): URL {
    val path = "/" + cls.name.replace('.', '/') + ".class"
    val url = cls.getResource(path)?.toExternalForm() ?: throw FileNotFoundException("Can't find $path")
    return URL(url.substring(0, url.lastIndexOf(path)))
}

interface DecompileTask {
    fun decompile(version: String, jar: Path, outputDir: Path, cp: List<Path>?): Path
}

@Suppress("unused")
open class FernflowerDecompileTask : DecompileTask {
    protected fun getArgs(jar: Path, outputDir: Path, cp: List<Path>?, defaults: Map<String, Any>): Array<String> {
        val args = mutableListOf("-ind=    ")
        //if (defaults.containsKey("iec")) args.add("-iec=1")
        val executor = threadLocalContext.get().executor as CustomThreadPoolExecutor
        executor.decompileParallelism = 4
        if (cp != null) {
            for (p in cp) args.add("-e=${p.toAbsolutePath()}")
        }
        args.add(jar.toAbsolutePath().toString())
        args.add(outputDir.toAbsolutePath().toString())
        return args.toTypedArray()
    }

    override fun decompile(version: String, jar: Path, outputDir: Path, cp: List<Path>?): Path {
        val clsOutput = outputDir.resolve("bin")
        val srcOutput = outputDir.resolve("src")
        Timer(version, "decompile.extractClasses").use {
            getJarFileSystem(jar).use {
                copy(it.getPath("/"), clsOutput)
            }
            Files.createDirectories(srcOutput)
        }
        Timer(version, "decompile").use {
            ConsoleDecompiler.main(getArgs(clsOutput, srcOutput, cp, IFernflowerPreferences.DEFAULTS))
        }
        return srcOutput
    }
}

private fun formatClassPath(cp: List<Path>) = cp.joinToString(File.pathSeparator) { it.toAbsolutePath().toString() }