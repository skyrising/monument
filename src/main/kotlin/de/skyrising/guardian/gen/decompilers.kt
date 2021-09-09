package de.skyrising.guardian.gen

import com.strobel.assembler.metadata.ITypeLoader
import com.strobel.decompiler.DecompilerSettings
import com.strobel.decompiler.PlainTextOutput
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
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

interface Decompiler {
    val name: String
    val maxParallelism get() = Integer.MAX_VALUE
    fun decompile(artifacts: List<MavenArtifact>, version: String, jar: Path, outputDir: (Boolean) -> Path, cp: List<Path>? = null): CompletableFuture<Path>

    companion object {
        val CFR = JavaDecompiler("cfr", "de.skyrising.guardian.gen.CfrDecompileTask", false /* Workaround for https://github.com/leibnitz27/cfr/issues/250 */)
        val FERNFLOWER = JavaDecompiler("fernflower", "de.skyrising.guardian.gen.FernflowerDecompileTask")
        val FORGEFLOWER = JavaDecompiler("forgeflower", "de.skyrising.guardian.gen.FernflowerDecompileTask")
        val FABRIFLOWER = JavaDecompiler("fabriflower", "de.skyrising.guardian.gen.FernflowerDecompileTask")
        val QUILTFLOWER = JavaDecompiler("quiltflower", "de.skyrising.guardian.gen.FernflowerDecompileTask")
        val PROCYON = JavaDecompiler("procyon", "de.skyrising.guardian.gen.ProcyonDecompileTask")
    }
}

@Suppress("unused")
class CfrDecompileTask : DecompileTask {
    override fun decompile(version: String, jar: Path, outputDir: (Boolean) -> Path, cp: List<Path>?): Path {
        val outDir = outputDir(false)
        Timer(version, "decompile").use {
            val options = mutableMapOf(
                "showversion" to "false",
                "silent" to "false",
                "comments" to "false",
                "outputpath" to outDir.toAbsolutePath().toString()
            )
            if (cp != null && cp.isNotEmpty()) options["extraclasspath"] = formatClassPath(cp)
            val driver = CfrDriver.Builder().withOptions(options).build()
            driver.analyse(listOf(jar.toAbsolutePath().toString()))
        }
        return outDir
    }
}

abstract class CommonDecompiler(override val name: String) : Decompiler {
    override fun toString() = "CommonDecompiler($name)"
}

class JavaDecompiler(name: String, private val taskClassName: String, private val allowSharing: Boolean = true) : CommonDecompiler(name) {
    private val classLoaders = ConcurrentHashMap<List<URI>, ClassLoader>()

    override fun decompile(
        artifacts: List<MavenArtifact>,
        version: String,
        jar: Path,
        outputDir: (Boolean) -> Path,
        cp: List<Path>?
    ): CompletableFuture<Path> = getMavenArtifacts(artifacts).thenCompose { urls: List<URI> ->
        supplyAsyncDecompile {
            outputTo(version) {
                val classLoader = if (allowSharing) {
                    classLoaders.computeIfAbsent(urls, this::createClassLoader)
                } else {
                    createClassLoader(urls)
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

    private fun createClassLoader(urls: List<URI>) = DecompileTaskClassLoader(URLClassLoader(arrayOf(
        getBaseUrl(JavaDecompiler::class.java),
        *urls.map(URI::toURL).toTypedArray()
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
    fun decompile(version: String, jar: Path, outputDir: (Boolean) -> Path, cp: List<Path>?): Path
}

@Suppress("unused")
open class FernflowerDecompileTask : DecompileTask {
    protected fun getArgs(jar: Path, outputDir: Path, cp: List<Path>?, defaults: Map<String, Any>): Array<String> {
        val args = mutableListOf("-${IFernflowerPreferences.INDENT_STRING}=    ")
        // QuiltFlower has fast iec
        if (IFernflowerPreferences.WARN_INCONSISTENT_INNER_CLASSES in defaults) {
            args.add("-${IFernflowerPreferences.INCLUDE_ENTIRE_CLASSPATH}=1")
        }
        args.add("-${IFernflowerPreferences.REMOVE_SYNTHETIC}=1")
        args.add("-${IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES}=1")
        val executor = threadLocalContext.get().executor as CustomThreadPoolExecutor
        executor.decompileParallelism = 4
        if (cp != null) {
            for (p in cp) args.add("-e=${p.toAbsolutePath()}")
        }
        args.add(jar.toAbsolutePath().toString())
        args.add(outputDir.toAbsolutePath().toString())
        return args.toTypedArray()
    }

    override fun decompile(version: String, jar: Path, outputDir: (Boolean) -> Path, cp: List<Path>?): Path {
        val outDir = outputDir(false)
        val clsOutput = outDir.resolve("bin")
        val srcOutput = outDir.resolve("src")
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

@Suppress("unused")
class ProcyonDecompileTask : DecompileTask {
    override fun decompile(version: String, jar: Path, outputDir: (Boolean) -> Path, cp: List<Path>?): Path {
        val outDir = outputDir(true)
        val executor = threadLocalContext.get().executor as CustomThreadPoolExecutor
        executor.decompileParallelism = minOf(
            Runtime.getRuntime().availableProcessors() - 2,
            (Runtime.getRuntime().maxMemory() / (768 * 1024 * 1024)).toInt()
        )
        Timer(version, "decompile").use {
            getJarFileSystem(jar).use {
                val settings = DecompilerSettings.javaDefaults()
                settings.isUnicodeOutputEnabled = true
                settings.typeLoader = ITypeLoader { name, buffer ->
                    val path = it.getPath("$name.class")
                    if (!Files.exists(path)) return@ITypeLoader false
                    val bytes = Files.readAllBytes(path)
                    buffer.putByteArray(bytes, 0, bytes.size)
                    buffer.position(0)
                    true
                }
                val errors = TreeMap<String, Throwable>()
                Files.walk(it.rootDirectories.first())
                    .map(Path::toString)
                    .filter { str -> str.endsWith(".class") && !str.contains('$') }
                    .forEach { p ->
                        val internalName = p.substring(1, p.length - 6)
                        println("Decompiling ${internalName.replace('/', '.')}")
                        val outPath = outDir.resolve("$internalName.java")
                        try {
                            Files.createDirectories(outPath.parent)
                            Files.newBufferedWriter(outPath).use { outWriter ->
                                com.strobel.decompiler.Decompiler.decompile(
                                    internalName,
                                    PlainTextOutput(outWriter),
                                    settings
                                )
                            }
                        } catch (t: Throwable) {
                            errors[internalName] = t
                        }
                    }
                if (errors.isNotEmpty()) {
                    val sb = StringBuilder()
                    for ((file, error) in errors.entries) {
                        sb.append(file).append('\n')
                        sb.append(error.message).append('\n')
                    }
                    Files.write(outDir.resolve("errors.txt"), sb.toString().toByteArray())
                }
            }
        }
        return outDir
    }
}