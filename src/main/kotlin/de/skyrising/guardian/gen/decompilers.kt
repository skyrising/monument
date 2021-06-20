package de.skyrising.guardian.gen

import java.io.File
import java.net.URI
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
        val CFR = object : JavaDecompiler("cfr", false /* Workaround for https://github.com/leibnitz27/cfr/issues/250 */) {
            override fun decompile(classLoader: ClassLoader, version: String, jar: Path, outputDir: Path, cp: List<Path>?) = supplyAsyncDecompile {
                outputTo(version) {
                    Timer(version, "decompile").use {
                        val options = mutableMapOf(
                            "showversion" to "false",
                            "silent" to "false",
                            "comments" to "false",
                            "outputpath" to outputDir.toAbsolutePath().toString()
                        )
                        if (cp != null && cp.isNotEmpty()) options["extraclasspath"] = formatClassPath(cp)
                        val builder = Class.forName("org.benf.cfr.reader.api.CfrDriver\$Builder", true, classLoader).newInstance()
                        builder.javaClass.getMethod("withOptions", Map::class.java).invoke(builder, options)
                        val driver = builder.javaClass.getMethod("build").invoke(builder)
                        driver.javaClass.getMethod("analyse", List::class.java).invoke(driver, listOf(jar.toAbsolutePath().toString()))
                    }
                }
                outputDir
            }
        }
        val FERNFLOWER = FernflowerDecompiler("fernflower")
        val FORGEFLOWER = FernflowerDecompiler("forgeflower")
        val FABRIFLOWER = FernflowerDecompiler("fabriflower")
        val QUILTFLOWER = FernflowerDecompiler("quiltflower")
    }
}

abstract class CommonDecompiler(override val name: String) : Decompiler {
    override fun toString() = "CommonDecompiler($name)"
}

abstract class JavaDecompiler(name: String, private val allowSharing: Boolean = true) : CommonDecompiler(name) {
    private val classLoaders = ConcurrentHashMap<URI, ClassLoader>()

    abstract fun decompile(classLoader: ClassLoader, version: String, jar: Path, outputDir: Path, cp: List<Path>?): CompletableFuture<Path>

    override fun decompile(
        artifact: MavenArtifact?,
        version: String,
        jar: Path,
        outputDir: Path,
        cp: List<Path>?
    ): CompletableFuture<Path> = getMavenArtifact(artifact!!).thenCompose { url: URI ->
        val classLoader = if (allowSharing) {
            classLoaders.computeIfAbsent(url, this::createClassLoader)
        } else {
            createClassLoader(url)
        }
        decompile(classLoader, version, jar, outputDir, cp)
    }

    private fun createClassLoader(url: URI): ClassLoader = URLClassLoader(arrayOf(url.toURL()))

    override fun toString() = "JavaDecompiler($name)"

}

open class FernflowerDecompiler(name: String) : JavaDecompiler(name) {
    protected fun getArgs(jar: Path, outputDir: Path, cp: List<Path>?, defaults: Map<String, Any>): Array<String> {
        val args = mutableListOf("-ind=    ")
        val executor = threadLocalContext.get().executor as CustomThreadPoolExecutor
        executor.decompileParallelism = 4
        if (cp != null) {
            for (p in cp) args.add("-e=${p.toAbsolutePath()}")
        }
        args.add(jar.toAbsolutePath().toString())
        args.add(outputDir.toAbsolutePath().toString())
        return args.toTypedArray()
    }

    private fun getDefaultOptions(classLoader: ClassLoader): Map<String, Any> {
        val prefsCls = Class.forName("org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences", true, classLoader)
        return prefsCls.getField("DEFAULTS").get(null) as Map<String, Any>
    }

    override fun decompile(classLoader: ClassLoader, version: String, jar: Path, outputDir: Path, cp: List<Path>?) = supplyAsyncDecompile {
        outputTo(version) {
            val clsOutput = outputDir.resolve("bin")
            val srcOutput = outputDir.resolve("src")
            Timer(version, "decompile.extractClasses").use {
                getJarFileSystem(jar).use {
                    copy(it.getPath("/"), clsOutput)
                }
                Files.createDirectories(srcOutput)
            }
            Timer(version, "decompile").use {
                val defaults = getDefaultOptions(classLoader)
                val cls = Class.forName("org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler", true, classLoader)
                val main = cls.getMethod("main", Array<String>::class.java)
                main.invoke(null, getArgs(clsOutput, srcOutput, cp, defaults))
                srcOutput
            }
        }
    }
}

private fun formatClassPath(cp: List<Path>) = cp.joinToString(File.pathSeparator) { it.toAbsolutePath().toString() }