package de.skyrising.guardian.gen

import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

interface Decompiler {
    val name: String
    fun decompile(artifact: MavenArtifact?, version: String, jar: Path, outputDir: Path, cp: List<Path>? = null): CompletableFuture<Path>

    companion object {
        val CFR = object : JavaDecompiler("cfr", false /* Workaround for https://github.com/leibnitz27/cfr/issues/250 */) {
            override fun decompile(classLoader: ClassLoader, version: String, jar: Path, outputDir: Path, cp: List<Path>?) = supplyAsync {
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
                outputTo(version) {
                    driver.javaClass.getMethod("analyse", List::class.java).invoke(driver, listOf(jar.toAbsolutePath().toString()))
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
    private val classLoaders = mutableMapOf<URL, ClassLoader>()

    abstract fun decompile(classLoader: ClassLoader, version: String, jar: Path, outputDir: Path, cp: List<Path>?): CompletableFuture<Path>

    override fun decompile(
        artifact: MavenArtifact?,
        version: String,
        jar: Path,
        outputDir: Path,
        cp: List<Path>?
    ): CompletableFuture<Path> = getMavenArtifact(artifact!!).thenCompose { url: URL ->
        val classLoader = if (allowSharing) {
            classLoaders.computeIfAbsent(url, this::createClassLoader)
        } else {
            createClassLoader(url)
        }
        decompile(classLoader, version, jar, outputDir, cp)
    }

    private fun createClassLoader(url: URL): ClassLoader = URLClassLoader(arrayOf(url))

    override fun toString() = "JavaDecompiler($name)"

}

open class FernflowerDecompiler(name: String) : JavaDecompiler(name) {
    protected fun getArgs(jar: Path, outputDir: Path, cp: List<Path>?, defaults: Map<String, Any>, doesSingleFile: Boolean): Array<String> {
        val args = mutableListOf("-ind=    ")
        if (defaults.containsKey("thr")) {
            val threads = 1; // Runtime.getRuntime().availableProcessors() - 2) / ForkJoinPool.getCommonPoolParallelism()
            args.add("-thr=$threads")
        }
        if (cp != null) {
            for (p in cp) args.add("-e=${p.toAbsolutePath()}")
        }
        args.add(jar.toAbsolutePath().toString())
        args.add((if (doesSingleFile) outputDir.resolve("tmp.jar") else outputDir).toAbsolutePath().toString())
        return args.toTypedArray()
    }

    private fun getDefaultOptions(classLoader: ClassLoader): Map<String, Any> {
        val prefsCls = Class.forName("org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences", true, classLoader)
        return prefsCls.getField("DEFAULTS").get(null) as Map<String, Any>
    }

    override fun decompile(classLoader: ClassLoader, version: String, jar: Path, outputDir: Path, cp: List<Path>?) = supplyAsync {
        outputTo(version) {
            val defaults = getDefaultOptions(classLoader)
            val doesSingleFile = try {
                Class.forName("org.jetbrains.java.decompiler.main.decompiler.SingleFileSaver", false, classLoader)
                true
            } catch (ignored: ClassNotFoundException) {
                false
            }
            val cls = Class.forName("org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler", true, classLoader)
            val main = cls.getMethod("main", Array<String>::class.java)
            main.invoke(null, getArgs(jar, outputDir, cp, defaults, doesSingleFile))
            if (doesSingleFile) {
                getJarFileSystem(outputDir.resolve("tmp.jar")).getPath("/")
            } else {
                outputDir
            }
        }
    }
}

private fun formatClassPath(cp: List<Path>) = cp.joinToString(File.pathSeparator) { it.toAbsolutePath().toString() }