package de.skyrising.guardian.gen

import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

interface Decompiler {
    val name: String
    fun decompile(artifact: MavenArtifact?, version: String, jar: Path, outputDir: Path, cp: List<Path>? = null): CompletableFuture<Unit>

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
                outputTo<Unit>(version) {
                    driver.javaClass.getMethod("analyse", List::class.java).invoke(driver, listOf(jar.toAbsolutePath().toString()))
                }
            }
        }
    }
}

abstract class CommonDecompiler(override val name: String) : Decompiler {
    override fun toString() = "CommonDecompiler($name)"
}

abstract class JavaDecompiler(name: String, private val allowSharing: Boolean = true) : CommonDecompiler(name) {
    private val classLoaders = mutableMapOf<URL, URLClassLoader>()

    abstract fun decompile(classLoader: ClassLoader, version: String, jar: Path, outputDir: Path, cp: List<Path>?): CompletableFuture<Unit>

    override fun decompile(
        artifact: MavenArtifact?,
        version: String,
        jar: Path,
        outputDir: Path,
        cp: List<Path>?
    ): CompletableFuture<Unit> = getMavenArtifact(artifact!!).thenCompose { url: URL ->
        val classLoader = if (allowSharing) {
            classLoaders.computeIfAbsent(url) { URLClassLoader(arrayOf(it)) }
        } else {
            URLClassLoader(arrayOf(url))
        }
        decompile(classLoader, version, jar, outputDir, cp)
    }

    override fun toString() = "CommonDecompiler($name)"

}

private fun formatClassPath(cp: List<Path>) = cp.joinToString(File.pathSeparator) { it.toAbsolutePath().toString() }